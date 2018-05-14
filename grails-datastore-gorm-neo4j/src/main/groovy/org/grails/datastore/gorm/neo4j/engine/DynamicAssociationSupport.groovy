package org.grails.datastore.gorm.neo4j.engine

import java.util.Map.Entry

import grails.neo4j.Neo4jEntity

import org.grails.datastore.gorm.GormEnhancer
import org.grails.datastore.gorm.GormStaticApi
import org.grails.datastore.gorm.neo4j.CypherBuilder
import org.grails.datastore.gorm.neo4j.GraphPersistentEntity
import org.grails.datastore.gorm.neo4j.Neo4jMappingContext
import org.grails.datastore.gorm.neo4j.Neo4jSession
import org.grails.datastore.gorm.neo4j.RelationshipUtils
import org.grails.datastore.gorm.neo4j.TypeDirectionPair
import org.grails.datastore.gorm.neo4j.collection.Neo4jList
import org.grails.datastore.gorm.neo4j.mapping.config.DynamicToManyAssociation
import org.grails.datastore.gorm.neo4j.mapping.reflect.Neo4jNameUtils
import org.grails.datastore.gorm.neo4j.util.IteratorUtil
import org.grails.datastore.mapping.engine.EntityAccess
import org.grails.datastore.mapping.engine.NonPersistentTypeException
import org.grails.datastore.mapping.model.PersistentProperty
import org.grails.datastore.mapping.model.config.GormProperties
import org.grails.datastore.mapping.model.types.Association

import org.neo4j.driver.v1.Record
import org.neo4j.driver.v1.StatementResult
import org.neo4j.driver.v1.StatementRunner

import org.slf4j.Logger
import org.slf4j.LoggerFactory

class DynamicAssociationSupport {

    public static
    final String DYNAMIC_ASSOCIATIONS_QUERY = "MATCH (m%s {" + CypherBuilder.IDENTIFIER + ":{id}})-[r]->(o) RETURN type(r) as relType, startNode(r)=m as out," +
                                              " r.targetType as targetType, {ids: collect(o." + CypherBuilder.IDENTIFIER + ")," +
                                              "labels: collect (labels(o))} as values"

    public static final String DYNAMIC_ASSOCIATION_PARAM = "org.grails.neo4j.DYNAMIC_ASSOCIATIONS"

    private static final Logger log = LoggerFactory.getLogger(DynamicAssociationSupport.class)

    static void loadDynamicAssociations(Neo4jEntity neo4jEntity) {
        GormStaticApi staticApi = GormEnhancer.findStaticApi(neo4jEntity.getClass())
        GraphPersistentEntity entity = (GraphPersistentEntity) staticApi.gormPersistentEntity
        if (entity.hasDynamicAssociations()) {
            def id = neo4jEntity.ident()
            if (id != null) {
                staticApi.withSession { Neo4jSession session ->
                    Object alreadyLoaded = session.getAttribute(entity, DYNAMIC_ASSOCIATION_PARAM)
                    if (alreadyLoaded == null) {
                        Map<String, Object> dynamicAssociations = getDynamicAssociations(session, entity, id)
                        if (dynamicAssociations != null) {
                            neo4jEntity.attributes(dynamicAssociations)
                        }
                        session.setAttribute(entity, DYNAMIC_ASSOCIATION_PARAM, Boolean.TRUE)
                    }
                }
            }
        }
    }

    static private Map<String, Object> getDynamicAssociations(
        Neo4jSession session,
        GraphPersistentEntity persistentEntity,
        Serializable id) {
        Map<TypeDirectionPair, Map<String, Object>> relationshipsMap = new HashMap<>()

        Map<String, Object> undeclared = new LinkedHashMap<String, Object>()

        Object entity = session.getCachedInstance(persistentEntity.getJavaClass(), id)
        EntityAccess entityAccess = session.createEntityAccess(persistentEntity, entity)
        entityAccess.setIdentifierNoConversion(id)

        final String cypher = String.format(DYNAMIC_ASSOCIATIONS_QUERY, ((GraphPersistentEntity) persistentEntity).getLabelsAsString())

        final Map<String, Object> isMap = Collections.<String, Object> singletonMap(GormProperties.IDENTITY, id)
        final StatementRunner boltSession = session.hasTransaction() ? session.getTransaction().getNativeTransaction() : session.getNativeInterface()

        if (log.isDebugEnabled()) {
            log.debug("QUERY Cypher [{}] for parameters [{}]", cypher, isMap)
        }

        final StatementResult relationships = boltSession.run(cypher, isMap)
        while (relationships.hasNext()) {
            final Record row = relationships.next()
            String relType = row.get("relType").asString()
            Boolean outGoing = row.get("out").asBoolean()
            Map<String, Object> values = row.get("values").asMap()
            TypeDirectionPair key = new TypeDirectionPair(relType, outGoing)
            if (row.containsKey(RelationshipPendingInsert.TARGET_TYPE) && !row.get(RelationshipPendingInsert.TARGET_TYPE).isNull()) {
                key.setTargetType(
                    row.get(RelationshipPendingInsert.TARGET_TYPE).asString()
                )
                relationshipsMap.put(key, values)
            }
        }

        for (PersistentProperty property : persistentEntity.getPersistentProperties()) {
            if (property instanceof Association) {
                Association association = (Association) property
                removeFromRelationshipMap(association, relationshipsMap)
            }
        }

        // if the relationship map is not empty as this point there are dynamic relationships that need to be loaded as undeclared
        if (!relationshipsMap.isEmpty()) {
            for (Entry<TypeDirectionPair, Map<String, Object>> entry : relationshipsMap.entrySet()) {
                Neo4jMappingContext mappingContext = session.getMappingContext()
                TypeDirectionPair key = entry.getKey()
                if (key.isOutgoing()) {
                    Map<String, Object> relationshipData = entry.getValue()
                    Object idsObject = relationshipData.get("ids")
                    Object labelsObject = relationshipData.get("labels")
                    if ((idsObject instanceof Iterable) && (labelsObject instanceof Iterable)) {

                        Iterator<Serializable> idIter = ((Iterable<Serializable>) idsObject).iterator()
                        String targetType = key.getTargetType()
                        Iterator<Collection<String>> labelIter = ((Iterable<Collection<String>>) labelsObject).iterator()

                        List values = new ArrayList()
                        GraphPersistentEntity associatedEntity = null
                        while (idIter.hasNext() && labelIter.hasNext()) {
                            Serializable targetId = idIter.next()
                            Collection<String> nextLabels = labelIter.next()
                            Collection<String> labels = nextLabels.isEmpty() ? Collections.singletonList(targetType) : nextLabels
                            associatedEntity = mappingContext.findPersistentEntityForLabels(labels)
                            if (associatedEntity == null) {
                                associatedEntity = mappingContext.findPersistentEntityForLabels(Collections.singletonList(targetType))
                            }
                            if (associatedEntity == null) {
                                throw new NonPersistentTypeException(labels.toString())
                            }
                            Object proxy = mappingContext.getProxyFactory().createProxy(
                                session,
                                associatedEntity.getJavaClass(),
                                targetId
                            )
                            values.add(proxy)
                        }
                        // for single instances and singular property name do not use an array
                        Object value
                        if (values.size() == 1 && isSingular(key.getType())) {
                            value = IteratorUtil.singleOrNull(values)
                        }
                        else {
                            DynamicToManyAssociation dynamicAssociation = new DynamicToManyAssociation(
                                persistentEntity,
                                persistentEntity.getMappingContext(),
                                key.getType(),
                                associatedEntity)
                            value = new Neo4jList(entityAccess, dynamicAssociation, values, session)
                        }
                        undeclared.put(key.getType(), value)
                    }

                }
            }
        }
        return undeclared
    }

    private static boolean isSingular(String key) {
        return Neo4jNameUtils.isSingular(key)
    }

    private static void removeFromRelationshipMap(Association association, Map<TypeDirectionPair, Map<String, Object>> relationshipsMap) {
        TypeDirectionPair typeDirectionPair = new TypeDirectionPair(
            RelationshipUtils.relationshipTypeUsedFor(association),
            !RelationshipUtils.useReversedMappingFor(association)
        );
        relationshipsMap.remove(typeDirectionPair);
    }
}