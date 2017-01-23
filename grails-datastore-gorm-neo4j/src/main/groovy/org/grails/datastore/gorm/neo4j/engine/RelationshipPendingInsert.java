/*
 * Copyright 2015 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.grails.datastore.gorm.neo4j.engine;

import grails.neo4j.Relationship;
import org.grails.datastore.gorm.neo4j.*;
import org.grails.datastore.gorm.neo4j.mapping.config.DynamicToOneAssociation;
import org.grails.datastore.mapping.core.impl.PendingInsertAdapter;
import org.grails.datastore.mapping.engine.EntityAccess;
import org.grails.datastore.mapping.model.PersistentProperty;
import org.grails.datastore.mapping.model.types.Association;
import org.grails.datastore.mapping.model.types.Basic;
import org.grails.datastore.mapping.model.types.Simple;
import org.grails.datastore.mapping.model.types.ToOne;
import org.grails.datastore.mapping.reflect.EntityReflector;
import org.neo4j.driver.v1.Session;
import org.neo4j.driver.v1.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.*;

/**
 * Represents a pending relationship insert
 *
 * @author Stefan
 * @author Graeme Rocher
 *
 */
public class RelationshipPendingInsert extends PendingInsertAdapter<Object, Serializable> {
    /**
     * The name of the from property
     */
    public static final String FROM = "from";
    /**
     * The name of the to property
     */
    public static final String TO = "to";
    public static final String CYPHER_DELETE_RELATIONSHIP = "MATCH (from%s {"+CypherBuilder.IDENTIFIER+": {start}})%s() DELETE r";
    public static final String CYPHER_DELETE_NATIVE_RELATIONSHIP = "MATCH (from%s)%s() WHERE ID(from) = {start} DELETE r";
    public static final String SOURCE_TYPE = "sourceType";
    public static final String TARGET_TYPE = "targetType";

    private static Logger log = LoggerFactory.getLogger(RelationshipPendingInsert.class);
    private final Transaction boltTransaction;
    private final Association association;
    private final Collection<Serializable> targetIdentifiers;
    private final boolean isUpdate;



    public RelationshipPendingInsert(EntityAccess parent, Association association, Collection<Serializable> pendingInserts, Transaction boltTransaction, boolean isUpdate) {
        super(parent.getPersistentEntity(), -1L, parent.getEntity(), parent);

        this.boltTransaction = boltTransaction;
        this.targetIdentifiers = pendingInserts;
        this.association = association;
        this.isUpdate = isUpdate;
    }

    public RelationshipPendingInsert(EntityAccess parent, Association association, Collection<Serializable> pendingInserts, Transaction boltTransaction) {
        this(parent, association, pendingInserts, boltTransaction, false);
    }


    @Override
    public void run() {


        GraphPersistentEntity graphParent = (GraphPersistentEntity) getEntity();
        GraphPersistentEntity graphChild = (GraphPersistentEntity) association.getAssociatedEntity();
        Map<String,Object> params = new LinkedHashMap<>(2);
        EntityAccess entityAccess = getEntityAccess();
        Object parentId = entityAccess.getIdentifier();

        final boolean isRelationshipAssociation = Relationship.class.isAssignableFrom(graphParent.getJavaClass());
        if(isRelationshipAssociation) {
            if(association.getName().equals(FROM)) {
                Association endProperty = (Association) graphParent.getPropertyByName(TO);
                Association startProperty = (Association) graphParent.getPropertyByName(FROM);

                graphChild = (GraphPersistentEntity) endProperty.getAssociatedEntity();
                graphParent = (GraphPersistentEntity) startProperty.getAssociatedEntity();

                Object endEntity = entityAccess.getProperty(TO);
                Object startEntity = entityAccess.getProperty(FROM);
                parentId = graphParent.getReflector().getIdentifier(startEntity);
                this.targetIdentifiers.clear();
                this.targetIdentifiers.add( graphChild.getReflector().getIdentifier(endEntity) );
            }
            else {
                // don't do anything for the 'to' end
                return;
            }
        }

        params.put(CypherBuilder.START, parentId);
        params.put(CypherBuilder.END, targetIdentifiers);

        final boolean nativeParent = graphParent.getIdGenerator() == null;
        String labelsFrom = graphParent.getLabelsAsString();
        String labelsTo = graphChild.getLabelsAsString();

        final String relMatch;

        if(association instanceof DynamicToOneAssociation) {
            LinkedHashMap<String, String> attrs = new LinkedHashMap<String, String>();
            attrs.put(SOURCE_TYPE, graphParent.getJavaClass().getSimpleName());
            attrs.put(TARGET_TYPE, graphChild.getJavaClass().getSimpleName());
            relMatch = RelationshipUtils.matchForAssociation(association, "r", attrs);
        }
        else if(isRelationshipAssociation) {
            LinkedHashMap<String, Object> attrs = new LinkedHashMap<>();
            GraphPersistentEntity relEntity = (GraphPersistentEntity) getEntity();
            EntityReflector reflector = relEntity.getReflector();
            Neo4jMappingContext mappingContext = (Neo4jMappingContext)relEntity.getMappingContext();
            for (PersistentProperty pp : relEntity.getPersistentProperties()) {
                if(pp instanceof Simple || pp instanceof Basic) {
                    String propertyName = pp.getName();
                    if(RelationshipPersistentEntity.TYPE.equals(propertyName)) continue;
                    Object v = reflector.getProperty(getNativeEntry(), propertyName);
                    if(v != null) {
                        attrs.put(propertyName, mappingContext.convertToNative(v));
                    }
                }
            }
            Relationship relationship = (Relationship) getEntityAccess().getEntity();
            attrs.putAll( relationship.attributes() );

            params.put("rProps", attrs);
            relMatch = RelationshipUtils.toMatch(association, relationship);
        }
        else {
            relMatch = RelationshipUtils.matchForAssociation(association, "r");
        }

        boolean reversed = RelationshipUtils.useReversedMappingFor(association);
        if(!reversed && (association instanceof ToOne) && isUpdate) {
            // delete any previous

            String cypher;
            if(nativeParent) {
                cypher = String.format(CYPHER_DELETE_NATIVE_RELATIONSHIP, labelsFrom, relMatch);
            }
            else {
                cypher = String.format(CYPHER_DELETE_RELATIONSHIP, labelsFrom, relMatch);
            }

            Map<String, Object> deleteParams = Collections.singletonMap(CypherBuilder.START, parentId);
            if(log.isDebugEnabled()) {
                log.debug("DELETE Cypher [{}] for parameters [{}]", cypher, deleteParams);
            }
            boltTransaction.run(cypher, deleteParams);
        }

        StringBuilder cypherQuery = new StringBuilder("MATCH (from").append(labelsFrom).append("), (to").append(labelsTo).append(") WHERE ");

        if(nativeParent) {
            cypherQuery.append("ID(from) = {start}");
        }
        else {
            cypherQuery.append("from.").append(CypherBuilder.IDENTIFIER).append(" = {start}");
        }
        cypherQuery.append(" AND ");
        if(graphChild.getIdGenerator() == null) {
            cypherQuery.append(" ID(to) IN {end} ");
        }
        else {
            cypherQuery.append("to.").append(CypherBuilder.IDENTIFIER).append(" IN {end}");
        }
        cypherQuery.append(" MERGE (from)").append(relMatch).append("(to)");
        if(isRelationshipAssociation) {
            cypherQuery.append(" ON CREATE SET r={rProps}");
        }
        String cypher = cypherQuery.toString();

        if (log.isDebugEnabled()) {
            log.debug("MERGE Cypher [{}] for parameters [{}]", cypher, params);
        }
        boltTransaction.run(cypher, params);
    }

}
