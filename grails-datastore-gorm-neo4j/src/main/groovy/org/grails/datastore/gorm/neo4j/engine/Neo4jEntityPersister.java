package org.grails.datastore.gorm.neo4j.engine;

import org.codehaus.groovy.runtime.DefaultGroovyMethods;
import org.grails.datastore.gorm.GormEntity;
import org.grails.datastore.gorm.neo4j.*;
import org.grails.datastore.gorm.neo4j.collection.*;
import org.grails.datastore.gorm.neo4j.mapping.config.DynamicToOneAssociation;
import org.grails.datastore.gorm.neo4j.mapping.reflect.Neo4jNameUtils;
import org.grails.datastore.gorm.neo4j.util.IteratorUtil;
import org.grails.datastore.gorm.schemaless.DynamicAttributes;
import org.grails.datastore.mapping.collection.PersistentCollection;
import org.grails.datastore.mapping.core.IdentityGenerationException;
import org.grails.datastore.mapping.core.Session;
import org.grails.datastore.mapping.core.impl.*;
import org.grails.datastore.mapping.dirty.checking.DirtyCheckable;
import org.grails.datastore.mapping.dirty.checking.DirtyCheckableCollection;
import org.grails.datastore.mapping.engine.EntityAccess;
import org.grails.datastore.mapping.engine.EntityPersister;
import org.grails.datastore.mapping.model.MappingContext;
import org.grails.datastore.mapping.model.PersistentEntity;
import org.grails.datastore.mapping.model.PersistentProperty;
import org.grails.datastore.mapping.model.config.GormProperties;
import org.grails.datastore.mapping.model.types.*;
import org.grails.datastore.mapping.proxy.EntityProxy;
import org.grails.datastore.mapping.query.Query;
import org.neo4j.driver.v1.Record;
import org.neo4j.driver.v1.StatementResult;
import org.neo4j.driver.v1.StatementRunner;
import org.neo4j.driver.v1.types.Node;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.convert.ConversionService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.util.Assert;

import javax.persistence.LockModeType;
import java.io.Serializable;
import java.util.*;

import static org.grails.datastore.mapping.query.Query.*;

/**
 * Core {@link EntityPersister} implementation responsible for CRUD operations against the Graph.
 *
 * @author Stefan Armbruster (stefan@armbruster-it.de)
 * @author Graeme Rocher
 * @since 1.0
 */
public class Neo4jEntityPersister extends EntityPersister {

    public static final String RETURN_NODE_ID = " RETURN ID(n) as id";

    public static final String DYNAMIC_ASSOCIATION_PARAM = "org.grails.neo4j.DYNAMIC_ASSOCIATIONS";

    private static Logger log = LoggerFactory.getLogger(Neo4jEntityPersister.class);


    public Neo4jEntityPersister(MappingContext mappingContext, PersistentEntity entity, Session session, ApplicationEventPublisher publisher) {
        super(mappingContext, entity, session, publisher);
    }

    @Override
    public Neo4jSession getSession() {
        return (Neo4jSession) super.session;
    }

    @Override
    protected List<Object> retrieveAllEntities(PersistentEntity pe, Serializable[] keys) {
        return retrieveAllEntities(pe, Arrays.asList(keys));
    }

    @Override
    protected List<Object> retrieveAllEntities(PersistentEntity pe, Iterable<Serializable> keys) {
        List<Criterion> criterions = new ArrayList<Criterion>(1);
        criterions.add(new In(GormProperties.IDENTITY, DefaultGroovyMethods.toList(keys)));
        Junction junction = new Conjunction(criterions);
        return new Neo4jQuery(getSession(), pe, this).executeQuery(pe, junction);
    }

    @Override
    protected List<Serializable> persistEntities(final PersistentEntity pe, @SuppressWarnings("rawtypes") final Iterable objs) {

        GraphPersistentEntity graphPersistentEntity = (GraphPersistentEntity) pe;

        List<Serializable> idList = new ArrayList<Serializable>();
        if(graphPersistentEntity.getIdGenerator() == null) {
            List<EntityAccess> entityAccesses = new ArrayList<EntityAccess>();
            // optimize batch inserts for multiple entities with native id
            final Neo4jSession session = getSession();

            StringBuilder createCypher = new StringBuilder(CypherBuilder.CYPHER_CREATE);
            int listIndex = 0;
            List<PendingOperation<Object,Serializable>> cascadingOperations = new ArrayList<PendingOperation<Object,Serializable>>();
            final Map<String, Object> params = new HashMap<String, Object>(1);
            final Map<Integer, Integer> indexMap = new HashMap<Integer, Integer>();
            int insertIndex = 0;
            final Iterator iterator = objs.iterator();
            while (iterator.hasNext()) {
                Object obj = iterator.next();
                listIndex++;
                if (shouldIgnore(session, obj)) {
                    EntityAccess entityAccess = createEntityAccess(pe, obj);
                    idList.add((Serializable) entityAccess.getIdentifier());
                    continue;
                }

                final EntityAccess entityAccess = createEntityAccess(pe, obj);
                if (getMappingContext().getProxyFactory().isProxy(obj)) {
                    idList.add(((EntityProxy) obj).getProxyKey());
                    continue;
                }

                session.registerPending(obj);

                Serializable identifier = (Serializable) entityAccess.getIdentifier();
                boolean isUpdate = identifier != null;
                if (isUpdate) {

                    registerPendingUpdate(session, pe, entityAccess, obj, identifier);
                    idList.add(identifier);
                }
                else {
                    final PendingInsertAdapter<Object, Serializable> pendingInsert = new PendingInsertAdapter<Object, Serializable>(pe, identifier, obj, entityAccess) {
                        @Override
                        public void run() {
                            if (cancelInsert(pe, entityAccess)) {
                                setVetoed(true);
                            }
                        }
                    };
                    pendingInsert.addCascadeOperation(new PendingOperationAdapter<Object, Serializable>(pe, identifier, obj) {
                        @Override
                        public void run() {
                            firePostInsertEvent(pe, entityAccess);
                        }
                    });

                    cascadingOperations.addAll(pendingInsert.getCascadeOperations());
                    final List<PendingOperation<Object, Serializable>> preOperations = pendingInsert.getPreOperations();
                    for (PendingOperation preOperation : preOperations) {
                        preOperation.run();
                    }

                    pendingInsert.run();
                    pendingInsert.setExecuted(true);

                    // temporarily add null so it is replaced later
                    idList.add(null);

                    if(pendingInsert.isVetoed()) {
                        continue;
                    }

                    session.addPendingInsert(pendingInsert);

                    indexMap.put(insertIndex++, listIndex - 1);
                    entityAccesses.add(entityAccess);
                    session.buildEntityCreateOperation(createCypher, String.valueOf(insertIndex), pe, pendingInsert, params, cascadingOperations, (Neo4jMappingContext)getMappingContext());
                    if(iterator.hasNext()) {
                        createCypher.append(CypherBuilder.COMMAND_SEPARATOR);
                    }

                }

            }
            if(insertIndex > 0) {

                StatementRunner statementRunner = session.hasTransaction() ? session.getTransaction().getNativeTransaction() : session.getNativeInterface();

                if(log.isDebugEnabled()) {
                    log.debug("CREATE Cypher [{}] for parameters [{}]", createCypher, params);
                }
                final String finalCypher = createCypher.toString() + " RETURN *";
                final StatementResult result = statementRunner.run(finalCypher, params);

                if(!result.hasNext()) {
                    throw new IdentityGenerationException("CREATE operation did not generate an identifier for entity " + pe.getJavaClass());
                }

                final Record record = result.next();
                for (int j = 0; j < insertIndex; j++) {
                    final Integer targetIndex = indexMap.get(j);
                    Assert.notNull(targetIndex, "Should never be null. Please file an issue");

                    final Node node = record.get("n" + (j + 1)).asNode();
                    if(node == null) {
                        throw new IdentityGenerationException("CREATE operation did not generate an identifier for entity " + pe.getJavaClass());
                    }
                    final long identifier = node.id();
                    final EntityAccess entityAccess = entityAccesses.get(j);
                    entityAccess.setIdentifier(identifier);
                    idList.set(targetIndex, identifier);
                    persistAssociationsOfEntity((GraphPersistentEntity) pe, entityAccess, false);
                }
            }
        }
        else {
            for (Object obj: objs) {
                idList.add(persistEntity(pe, obj));
            }
        }
        return idList;
    }

    @Override
    protected Object retrieveEntity(PersistentEntity pe, Serializable key) {

        GraphPersistentEntity graphPersistentEntity = (GraphPersistentEntity) pe;
        if(graphPersistentEntity.getIdGenerator() == null) {
            getSession().assertTransaction();

            final Neo4jSession session = getSession();
            final ConversionService conversionService = getMappingContext().getConversionService();
            if(log.isDebugEnabled()) {
                log.debug("Retrieving entity [{}] by node id [{}]", pe.getJavaClass(), key);
            }
            Long convertedId = conversionService.convert(key, Long.class);
            StatementRunner statementRunner = session.hasTransaction() ? session.getTransaction().getNativeTransaction() : session.getNativeInterface();
            StatementResult result = statementRunner.run("MATCH (n) WHERE ID(n)={id} RETURN n", Collections.<String, Object>singletonMap(GormProperties.IDENTITY, convertedId));
            if(result.hasNext()) {
                final Node node = result.next().get("n").asNode();
                return unmarshallOrFromCache(pe, node);
            }
            else {
                return null;
            }
        }
        else {
            final Neo4jQuery query = new Neo4jQuery(getSession(), pe, this);
            query.idEq(key);
            return query.max(1).singleResult();
        }
    }

    public Object unmarshallOrFromCache(PersistentEntity defaultPersistentEntity, Map<String, Object> resultData) {

        Node data = (Node)resultData.get(CypherBuilder.NODE_DATA);
        return unmarshallOrFromCache(defaultPersistentEntity, data, resultData);
    }

    public Object unmarshallOrFromCache(PersistentEntity defaultPersistentEntity, Node data) {
        return unmarshallOrFromCache(defaultPersistentEntity, data, Collections.<String,Object>emptyMap());
    }

    public Object unmarshallOrFromCache(PersistentEntity defaultPersistentEntity, Node data, Map<String, Object> resultData) {
        return unmarshallOrFromCache(defaultPersistentEntity, data, resultData, Collections.<Association, Object>emptyMap());
    }

    public Object unmarshallOrFromCache(PersistentEntity defaultPersistentEntity, Node data, Map<String, Object> resultData, Map<Association, Object> initializedAssociations ) {
        return unmarshallOrFromCache(defaultPersistentEntity, data, resultData, initializedAssociations, LockModeType.NONE);
    }
    public Object unmarshallOrFromCache(PersistentEntity defaultPersistentEntity, Node data, Map<String, Object> resultData, Map<Association, Object> initializedAssociations, LockModeType lockModeType) {
        final Neo4jSession session = getSession();
        final Neo4jTransaction neo4jTransaction = session.assertTransaction();

        if (LockModeType.PESSIMISTIC_WRITE.equals(lockModeType)) {
            if(log.isDebugEnabled()) {
                log.debug("Locking entity [{}] node [{}] for pessimistic write", defaultPersistentEntity.getName(), data.id());
            }
//            neo4jTransaction.getTransaction().acquireWriteLock(data);
            // TODO: Write lock support?
            throw new UnsupportedOperationException("Write locks are not supported by the Bolt Java Driver.");
        }

        final Iterable<String> labels = data.labels();
        GraphPersistentEntity graphPersistentEntity = (GraphPersistentEntity) defaultPersistentEntity;
        PersistentEntity persistentEntity = mostSpecificPersistentEntity(defaultPersistentEntity, labels);
        final Serializable id;
        if(graphPersistentEntity.getIdGenerator() == null) {
            id = data.id();
        }
        else {
            id = data.get(CypherBuilder.IDENTIFIER).asNumber();
        }
        Object instance = session.getCachedInstance(persistentEntity.getJavaClass(), id);

        if (instance == null) {
            instance = unmarshall(persistentEntity, id, data, resultData, initializedAssociations);
        }
        return instance;
    }

    private PersistentEntity mostSpecificPersistentEntity(PersistentEntity pe, Iterable<String> labels) {
        PersistentEntity result = null;
        int longestInheritenceChain = -1;

        for (String l: labels) {
            PersistentEntity persistentEntity = findDerivedPersistentEntityWithLabel(pe, l);
            if (persistentEntity!=null) {
                int inheritenceChain = calcInheritenceChain(persistentEntity);
                if (inheritenceChain > longestInheritenceChain) {
                    longestInheritenceChain = inheritenceChain;
                    result = persistentEntity;
                }
            }
        }
        if(result != null) {
            return result;
        }
        else {
            return pe;
        }
    }

    private PersistentEntity findDerivedPersistentEntityWithLabel(PersistentEntity parent, String label) {
        for (PersistentEntity pe: getMappingContext().getPersistentEntities()) {
            if (isInParentsChain(parent, pe)) {
                if (((GraphPersistentEntity)pe).getLabels().contains(label)) {
                    return pe;
                }
            }
        }
        return null;
    }

    private boolean isInParentsChain(PersistentEntity parent, PersistentEntity it) {
        if (it==null) {
            return false;
        } else if (it.equals(parent)) {
            return true;
        } else return isInParentsChain(parent, it.getParentEntity());
    }

    private int calcInheritenceChain(PersistentEntity current) {
        if (current == null) {
            return 0;
        } else {
            return calcInheritenceChain(current.getParentEntity()) + 1;
        }
    }


    protected Object unmarshall(PersistentEntity persistentEntity, Serializable id, Node node, Map<String, Object> resultData, Map<Association, Object> initializedAssociations) {

        if(log.isDebugEnabled()) {
            log.debug( "unmarshalling entity [{}] with id [{}], props {}, {}", persistentEntity.getName(), id, node);
        }
        final Neo4jSession session = getSession();
        EntityAccess entityAccess = session.createEntityAccess(persistentEntity, persistentEntity.newInstance());
        entityAccess.setIdentifierNoConversion(id);
        final Object entity = entityAccess.getEntity();
        session.cacheInstance(persistentEntity.getJavaClass(), id, entity);

        Map<TypeDirectionPair, Map<String, Object>> relationshipsMap = new HashMap<>();
        final List<String> nodeProperties = DefaultGroovyMethods.toList(node.keys());

        for (PersistentProperty property: entityAccess.getPersistentEntity().getPersistentProperties()) {

            String propertyName = property.getName();
            if ( (property instanceof Simple) || (property instanceof TenantId)) {
                // implicitly sets version property as well
                if(node.containsKey(propertyName)) {

                    entityAccess.setProperty(propertyName, node.get(propertyName).asObject());

                    nodeProperties.remove(propertyName);
                }
            } else if (property instanceof Association) {

                Association association = (Association) property;

                final String associationName = association.getName();

                if(initializedAssociations.containsKey(association)) {
                    entityAccess.setPropertyNoConversion(associationName, initializedAssociations.get(association));
                    removeFromRelationshipMap(association, relationshipsMap);
                    continue;
                }

                final String associationNodesKey = associationName + "Nodes";
                final String associationIdsKey = associationName + "Ids";

                // if the node key is present we have an eager fetch, so initialise the association
                if(resultData.containsKey(associationNodesKey)) {
                    if (association instanceof ToOne) {
                        final PersistentEntity associatedEntity = association.getAssociatedEntity();
                        final Neo4jEntityPersister associationPersister = session.getEntityPersister(associatedEntity.getJavaClass());
                        final Iterable<Node> associationNodes = (Iterable<Node>) resultData.get(associationNodesKey);
                        final Node associationNode = IteratorUtil.singleOrNull(associationNodes);
                        if(associationNode != null) {
                            entityAccess.setPropertyNoConversion(
                                    associationName,
                                    associationPersister.unmarshallOrFromCache(associatedEntity, associationNode)
                            );
                        }
                    }
                    else if(association instanceof ToMany) {
                        Collection values;
                        final Class type = association.getType();
                        final Collection<Object> associationNodes = (Collection<Object>) resultData.get(associationNodesKey);
                        final Neo4jResultList resultSet = new Neo4jResultList(0, associationNodes.size(), associationNodes.iterator(), session.getEntityPersister(association.getAssociatedEntity()));
                        if(association.isBidirectional()) {
                            final Association inverseSide = association.getInverseSide();
                            if(inverseSide instanceof ToOne) {
                                resultSet.setInitializedAssociations(Collections.singletonMap(
                                        inverseSide, entity
                                ));
                            }
                        }
                        if(List.class.isAssignableFrom(type)) {
                            values = new Neo4jList(entityAccess, association, resultSet, session);
                        }
                        else if(SortedSet.class.isAssignableFrom(type)) {
                            values = new Neo4jSortedSet(entityAccess, association, new TreeSet<Object>(resultSet), session);
                        }
                        else {
                            values = new Neo4jSet(entityAccess, association, new HashSet<Object>(resultSet), session);
                        }
                        entityAccess.setPropertyNoConversion(propertyName, values);
                    }
                }
                else if(resultData.containsKey(associationIdsKey)) {

                    final Object associationValues = resultData.get(associationIdsKey);
                    List<Serializable> targetIds = Collections.emptyList();
                    if(associationValues instanceof Collection) {
                        targetIds = (List<Serializable>) associationValues;
                    }
                    if (association instanceof ToOne) {
                        ToOne toOne = (ToOne) association;
                        if (!targetIds.isEmpty()) {
                            Serializable targetId;
                            try {
                                targetId = IteratorUtil.singleOrNull(targetIds);
                            } catch (NoSuchElementException e) {
                                throw new DataIntegrityViolationException("Single-ended association has more than one associated identifier: " + association);
                            }
                            entityAccess.setPropertyNoConversion(propertyName,
                                    getMappingContext().getProxyFactory().createProxy(
                                            this.session,
                                            toOne.getAssociatedEntity().getJavaClass(),
                                            targetId
                                    )
                            );
                        }
                    } else if (association instanceof ToMany) {

                        Collection values;
                        final Class type = association.getType();
                        if(List.class.isAssignableFrom(type)) {
                            values = new Neo4jPersistentList(targetIds, session, entityAccess, (ToMany) association);
                        }
                        else if(SortedSet.class.isAssignableFrom(type)) {
                            values = new Neo4jPersistentSortedSet(targetIds, session, entityAccess, (ToMany) association);
                        }
                        else {
                            values = new Neo4jPersistentSet(targetIds, session, entityAccess, (ToMany) association);
                        }
                        entityAccess.setPropertyNoConversion(propertyName, values);
                    } else {
                        throw new IllegalArgumentException("association " + associationName + " is of type " + association.getClass().getSuperclass().getName());
                    }
                }
                else {
                    // No OPTIONAL MATCH specified so the association queries are lazily executed
                    if(association instanceof ToOne) {
                        // first check whether the object has already been loaded from the cache

                        // if a lazy proxy should be created for this association then create it,
                        // note that this strategy does not allow for null checks
                        final Neo4jAssociationQueryExecutor associationQueryExecutor = new Neo4jAssociationQueryExecutor(session, association);
                        if(association.getMapping().getMappedForm().isLazy()) {
                            final Object proxy = getMappingContext().getProxyFactory().createProxy(
                                    this.session,
                                    associationQueryExecutor,
                                    id
                            );
                            entityAccess.setPropertyNoConversion(propertyName,
                                    proxy
                            );
                        }
                        else {
                            final List<Object> results = associationQueryExecutor.query(id);
                            if(!results.isEmpty()) {
                                entityAccess.setPropertyNoConversion(propertyName, results.get(0));
                            }
                        }
                    }
                    else if(association instanceof ToMany) {
                        Collection values;
                        final Class type = association.getType();
                        if(List.class.isAssignableFrom(type)) {
                            values = new Neo4jPersistentList(id, session, entityAccess, (ToMany) association);
                        }
                        else if(SortedSet.class.isAssignableFrom(type)) {
                            values = new Neo4jPersistentSortedSet(id, session, entityAccess, (ToMany) association);
                        }
                        else {
                            values = new Neo4jPersistentSet(id, session, entityAccess, (ToMany) association);
                        }
                        entityAccess.setPropertyNoConversion(propertyName, values);
                    }
                }


            } else {
                throw new IllegalArgumentException("property " + property.getName() + " is of type " + property.getClass().getSuperclass().getName());

            }
        }

        Map<String,Object> undeclared = new LinkedHashMap<String, Object>();

        if (!nodeProperties.isEmpty()) {
            for (String nodeProperty : nodeProperties) {
                if(!nodeProperty.equals(CypherBuilder.IDENTIFIER)) {
                    undeclared.put(nodeProperty, node.get(nodeProperty).asObject());
                }
            }
        }

        final Object obj = entity;
        if(!undeclared.isEmpty()) {
            if(obj instanceof DynamicAttributes) {
                ((DynamicAttributes)obj).attributes(undeclared);
            }
        }

        firePostLoadEvent(entityAccess.getPersistentEntity(), entityAccess);
        return obj;
    }

    private void removeFromRelationshipMap(Association association, Map<TypeDirectionPair, Map<String, Object>> relationshipsMap) {
        TypeDirectionPair typeDirectionPair = new TypeDirectionPair(
                RelationshipUtils.relationshipTypeUsedFor(association),
                !RelationshipUtils.useReversedMappingFor(association)
        );
        relationshipsMap.remove(typeDirectionPair);
    }

    private Collection createCollection(Association association) {
        return association.isList() ? new ArrayList() : new HashSet();
    }

    private Collection createDirtyCheckableAwareCollection(EntityAccess entityAccess, Association association, Collection delegate) {
        if (delegate==null) {
            delegate = createCollection(association);
        }

        if( !(delegate instanceof DirtyCheckableCollection)) {

            final Object entity = entityAccess.getEntity();
            if(entity instanceof DirtyCheckable) {
                final Neo4jSession session = getSession();
                for( Object o : delegate ) {
                    final EntityAccess associationAccess = getSession().createEntityAccess(association.getAssociatedEntity(), o);
                    session.addPendingRelationshipInsert((Serializable) entityAccess.getIdentifier(), association, (Serializable) associationAccess.getIdentifier());
                }

                delegate = association.isList() ?
                        new Neo4jList(entityAccess, association, (List)delegate, session) :
                        new Neo4jSet(entityAccess, association, (Set)delegate, session);
            }
        }
        else {
            final DirtyCheckableCollection dirtyCheckableCollection = (DirtyCheckableCollection) delegate;
            final Neo4jSession session = getSession();
            if(dirtyCheckableCollection.hasChanged()) {
                for (Object o : ((Iterable)dirtyCheckableCollection)) {
                    final EntityAccess associationAccess = getSession().createEntityAccess(association.getAssociatedEntity(), o);
                    session.addPendingRelationshipInsert((Serializable) entityAccess.getIdentifier(),association, (Serializable) associationAccess.getIdentifier());
                }
            }
        }
        return delegate;
    }

    private boolean isSingular(String key) {
        return Neo4jNameUtils.isSingular(key);
    }

    @Override
    protected Serializable persistEntity(final PersistentEntity pe, Object obj) {
        if (obj == null) {
            throw new IllegalStateException("obj is null");
        }

        final Neo4jSession session = getSession();
        if (shouldIgnore(session, obj)) {
            EntityAccess entityAccess = createEntityAccess(pe, obj);
            return (Serializable) entityAccess.getIdentifier();
        }

        if (getMappingContext().getProxyFactory().isProxy(obj)) {
            return ((EntityProxy) obj).getProxyKey();
        }

        final EntityAccess entityAccess = createEntityAccess(pe, obj);
        Object identifier = entityAccess.getIdentifier();


        session.registerPending(obj);

        // cancel operation if vetoed
        boolean isUpdate = identifier != null;
        if (isUpdate) {

            registerPendingUpdate(session, pe, entityAccess, obj, (Serializable) identifier);

        } else {
            GraphPersistentEntity graphPersistentEntity = (GraphPersistentEntity)pe;
            final IdGenerator idGenerator = graphPersistentEntity.getIdGenerator();
            final boolean isNativeId = idGenerator == null;
            if(!isNativeId) {

                identifier = idGenerator.nextId();
                entityAccess.setIdentifier(identifier);
            }

            final PendingInsertAdapter<Object, Serializable> pendingInsert = new PendingInsertAdapter<Object, Serializable>(pe, (Serializable) identifier, obj, entityAccess) {
                @Override
                public void run() {
                    if (cancelInsert(pe, entityAccess)) {
                        setVetoed(true);
                    }
                }
            };
            pendingInsert.addCascadeOperation(new PendingOperationAdapter<Object, Serializable>(pe, (Serializable) identifier, obj) {
                @Override
                public void run() {
                    firePostInsertEvent(pe, entityAccess);
                }
            });

            if(isNativeId) {
                // if we have a native identifier then we have to perform an insert to obtain the id
                final List<PendingOperation<Object, Serializable>> preOperations = pendingInsert.getPreOperations();
                for (PendingOperation preOperation : preOperations) {
                    preOperation.run();
                }

                pendingInsert.run();
                pendingInsert.setExecuted(true);

                if(pendingInsert.isVetoed()) {
                    return null;
                }



                final Map<String, Object> params = new HashMap<String, Object>(1);

                final String cypher = session.buildEntityCreateOperation(pe, pendingInsert, params, pendingInsert.getCascadeOperations());
                final StatementRunner boltSession = session.hasTransaction() ? session.getTransaction().getNativeTransaction() : session.getNativeInterface();
                final String finalCypher = cypher + RETURN_NODE_ID;
                if(log.isDebugEnabled()) {
                    log.debug("CREATE Cypher [{}] for parameters [{}]", finalCypher, params);
                }
                final StatementResult result = boltSession.run(finalCypher, params);

                if(!result.hasNext()) {
                    throw new IdentityGenerationException("CREATE operation did not generate an identifier for entity " + entityAccess.getEntity());
                }

                Record idMap = result.next();
                if(idMap != null) {
                    identifier = idMap.get(GormProperties.IDENTITY).asObject();
                    if(identifier == null) {
                        throw new IdentityGenerationException("CREATE operation did not generate an identifier for entity " + entityAccess.getEntity());
                    }
                    entityAccess.setIdentifier(identifier);
                    persistAssociationsOfEntity((GraphPersistentEntity) pe, entityAccess, false);
                }
                else {
                    throw new IdentityGenerationException("CREATE operation did not generate an identifier for entity " + entityAccess.getEntity());
                }
                session.addPendingInsert(pendingInsert);
            }
            else {
                session.addPendingInsert(pendingInsert);
                persistAssociationsOfEntity((GraphPersistentEntity) pe, entityAccess, false);
            }

        }

        return (Serializable) identifier;
    }

    private void registerPendingUpdate(Neo4jSession session, final PersistentEntity pe, final EntityAccess ea, final Object obj, final Serializable identifier) {
        final Neo4jModificationTrackingEntityAccess entityAccess = new Neo4jModificationTrackingEntityAccess(ea);
        final Object notEqualMarker = new Object();
        final PendingUpdateAdapter<Object, Serializable> pendingUpdate = new PendingUpdateAdapter<Object, Serializable>(pe, identifier, obj, ea) {
            @Override
            public void run() {
                if (cancelUpdate(pe, entityAccess)) {
                    setVetoed(true);
                } else {
                    Object entity = entityAccess.getEntity();
                    if (entity instanceof DirtyCheckable) {
                        DirtyCheckable dirtyCheckable = (DirtyCheckable) entity;
                        for (Map.Entry<String, Object> entry: entityAccess.getModifiedProperties().entrySet()) {
                            dirtyCheckable.markDirty(entry.getKey(), notEqualMarker, entry.getValue());
                        }
                    }
                }
            }
        };
        pendingUpdate.addCascadeOperation(new PendingOperationAdapter<Object, Serializable>(pe, identifier, obj) {
            @Override
            public void run() {
                firePostUpdateEvent(pe, ea);
            }
        });
        session.addPendingUpdate(pendingUpdate);

        persistAssociationsOfEntity((GraphPersistentEntity) pe, ea, true);
    }

    private boolean shouldIgnore(Neo4jSession session, Object obj) {
        boolean isDirty = obj instanceof DirtyCheckable ? ((DirtyCheckable)obj).hasChanged() : true;
        return session.isPendingAlready(obj) || (!isDirty);
    }

    private void persistAssociationsOfEntity(GraphPersistentEntity pe, EntityAccess entityAccess, boolean isUpdate) {

        Object obj = entityAccess.getEntity();
        DirtyCheckable dirtyCheckable = null;
        if (obj instanceof DirtyCheckable) {
            dirtyCheckable = (DirtyCheckable)obj;
        }

        Neo4jSession neo4jSession = getSession();
        if(pe.hasDynamicAssociations()) {
            MappingContext mappingContext = pe.getMappingContext();
            DynamicAttributes dynamicAttributes = (DynamicAttributes) obj;
            Map<String, Object> attributes = dynamicAttributes.attributes();
            for (Map.Entry<String, Object> entry : attributes.entrySet()) {
                Object value = entry.getValue();
                if(value == null) {
                    String associationName = entry.getKey();
                    Object originalValue = ((DirtyCheckable) obj).getOriginalValue(associationName);
                    if(originalValue != null && mappingContext.isPersistentEntity(originalValue)) {
                        processDynamicAssociationRemoval(pe, entityAccess, neo4jSession, mappingContext, associationName, originalValue);
                    }
                    else if(originalValue instanceof Iterable) {
                        Iterable i = (Iterable) originalValue;
                        for (Object o : i) {
                            processDynamicAssociationRemoval(pe, entityAccess, neo4jSession, mappingContext, associationName, o);
                        }
                    }
                }
                else if(mappingContext.isPersistentEntity(value)) {
                    if( ((DirtyCheckable)value).hasChanged() ) {
                        neo4jSession.persist(value);
                    }
                }
                else if(value instanceof Iterable) {
                    boolean collectionChanged = false;
                    for(Object o : ((Iterable)value)) {
                        if(mappingContext.isPersistentEntity(o)) {
                            collectionChanged = ((DirtyCheckable)o).hasChanged();
                            if(collectionChanged) break;
                        }
                    }

                    if(collectionChanged) {
                        neo4jSession.persist((Iterable)value);
                    }
                }
            }
        }

        for (PersistentProperty pp: pe.getAssociations()) {
            String propertyName = pp.getName();
            if ((!isUpdate) || ((dirtyCheckable!=null) && dirtyCheckable.hasChanged(propertyName))) {

                Object propertyValue = entityAccess.getProperty(propertyName);

                if ((pp instanceof OneToMany) || (pp instanceof ManyToMany)) {
                    Association association = (Association) pp;

                    if (propertyValue!= null) {

                        if(propertyValue instanceof PersistentCollection) {
                            PersistentCollection pc = (PersistentCollection) propertyValue;
                            if(!pc.isInitialized()) continue;
                        }

                        if (association.isBidirectional()) {
                            // Populate other side of bidi
                            for (Object associatedObject: (Iterable)propertyValue) {
                                EntityAccess assocEntityAccess = createEntityAccess(association.getAssociatedEntity(), associatedObject);
                                String referencedPropertyName = association.getReferencedPropertyName();
                                if(association instanceof ManyToMany) {
                                    ((GormEntity)associatedObject).addTo(referencedPropertyName, obj);
                                }
                                else {
                                    assocEntityAccess.setPropertyNoConversion(referencedPropertyName, obj);
                                    ((DirtyCheckable)associatedObject).markDirty(referencedPropertyName);
                                }
                            }
                        }

                        Collection targets = (Collection) propertyValue;
                        persistEntities(association.getAssociatedEntity(), targets);

                        boolean reversed = RelationshipUtils.useReversedMappingFor(association);

                        if (!reversed) {
                            Collection dcc = createDirtyCheckableAwareCollection(entityAccess, association, targets);
                            entityAccess.setProperty(association.getName(), dcc);
                        }
                    }
                } else if (pp instanceof ToOne) {
                    if (propertyValue != null) {
                        ToOne to = (ToOne) pp;

                        if (to.isBidirectional()) {  // Populate other side of bidi
                            EntityAccess assocEntityAccess = createEntityAccess(to.getAssociatedEntity(), propertyValue);
                            if (to instanceof OneToOne) {
                                assocEntityAccess.setProperty(to.getReferencedPropertyName(), obj);
                            } else {
                                Collection collection = (Collection) assocEntityAccess.getProperty(to.getReferencedPropertyName());
                                if (collection == null ) {
                                    collection = new ArrayList();
                                    assocEntityAccess.setProperty(to.getReferencedPropertyName(), collection);
                                }
                                if (!collection.contains(obj)) {
                                    collection.add(obj);
                                }
                            }
                        }

                        persistEntity(to.getAssociatedEntity(), propertyValue);

                        boolean reversed = RelationshipUtils.useReversedMappingFor(to);

                        if (!reversed) {
                            final EntityAccess assocationAccess = neo4jSession.createEntityAccess(to.getAssociatedEntity(), propertyValue);
                            neo4jSession.addPendingRelationshipInsert((Serializable) entityAccess.getIdentifier(), to, (Serializable) assocationAccess.getIdentifier());
                        }

                    }
                    else if(isUpdate) {
                        Object previousValue = dirtyCheckable.getOriginalValue(propertyName);
                        if(previousValue != null) {
                            ToOne to = (ToOne) pp;
                            Serializable associationId = neo4jSession.getEntityPersister(previousValue).getObjectIdentifier(previousValue);
                            if(associationId != null) {
                                neo4jSession.addPendingRelationshipDelete((Serializable) entityAccess.getIdentifier(), to, associationId);
                            }
                        }
                    }
                } else {
                    throw new IllegalArgumentException("GORM for Neo4j doesn't support properties of the given type " + pp + "(" + pp.getClass().getSuperclass() +")" );
                }
            }


        }
    }

    private void processDynamicAssociationRemoval(GraphPersistentEntity graphEntity, EntityAccess entityAccess, Neo4jSession neo4jSession, MappingContext mappingContext, String associationName, Object originalValue) {
        final GraphPersistentEntity associated = (GraphPersistentEntity) mappingContext.getPersistentEntity(originalValue.getClass().getName());
        if (associated != null) {

            Object childId = getSession().getEntityPersister(originalValue).getObjectIdentifier(originalValue);
            if(childId != null) {
                Serializable parentId = (Serializable) entityAccess.getIdentifier();
                neo4jSession.addPendingRelationshipDelete(parentId,new DynamicToOneAssociation(graphEntity, mappingContext, associationName, associated), (Serializable)childId );
            }
        }
    }

    @Override
    protected void deleteEntity(final PersistentEntity pe, final Object obj) {
        final EntityAccess entityAccess = createEntityAccess(pe, obj);


        final Neo4jSession session = getSession();
        session.clear(obj);
        final PendingDeleteAdapter pendingDelete = createPendingDeleteOne(session, pe, entityAccess, obj);
        pendingDelete.addCascadeOperation(new PendingOperationAdapter<Object, Serializable>(pe, (Serializable) entityAccess.getIdentifier(), obj) {
            @Override
            public void run() {
                firePostDeleteEvent(pe, entityAccess);
            }
        });
        session.addPendingDelete(pendingDelete);

    }

    @SuppressWarnings("unchecked")
    private PendingDeleteAdapter createPendingDeleteOne(final Neo4jSession session, final PersistentEntity pe, final EntityAccess entityAccess, final Object obj) {
        return new PendingDeleteAdapter(pe, entityAccess.getIdentifier(), obj) {
            @Override
            public void run() {
                if (cancelDelete(pe, entityAccess)) {
                    setVetoed(true);
                }
            }
        };
    }



    @Override
    protected void deleteEntities(PersistentEntity pe, @SuppressWarnings("rawtypes") Iterable objects) {
        for (Object object : objects) {
            deleteEntity(pe, object);
        }
    }

    @Override
    public Query createQuery() {
        return new Neo4jQuery(getSession(), getPersistentEntity(), this);
    }

    @Override
    public Serializable refresh(Object o) {
        throw new UnsupportedOperationException();
    }
}
