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
package org.grails.datastore.gorm.neo4j.engine

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.grails.datastore.gorm.neo4j.CypherBuilder
import org.grails.datastore.gorm.neo4j.GraphPersistentEntity
import org.grails.datastore.gorm.neo4j.Neo4jSession
import org.grails.datastore.gorm.neo4j.RelationshipPersistentEntity
import org.grails.datastore.gorm.neo4j.RelationshipUtils
import org.grails.datastore.gorm.neo4j.collection.Neo4jResultList
import org.grails.datastore.mapping.engine.AssociationQueryExecutor
import org.grails.datastore.mapping.model.PersistentEntity
import org.grails.datastore.mapping.model.config.GormProperties
import org.grails.datastore.mapping.model.types.Association
import org.grails.datastore.mapping.model.types.Basic
import org.grails.datastore.mapping.model.types.ToMany
import org.grails.datastore.mapping.model.types.ToOne
import org.neo4j.driver.v1.Session
import org.neo4j.driver.v1.StatementResult
import org.neo4j.driver.v1.StatementRunner


/**
 * Responsible for lazy loading associations
 *
 * @author Graeme Rocher
 * @since 5.0
 */
@CompileStatic
@Slf4j
class Neo4jAssociationQueryExecutor implements AssociationQueryExecutor<Serializable, Object> {

    final Neo4jSession session
    final PersistentEntity indexedEntity
    final Association association
    final boolean lazy
    final boolean singleResult

    Neo4jAssociationQueryExecutor(Neo4jSession session, Association association, boolean lazy = association.mapping.mappedForm.isLazy(), boolean singleResult = false) {
        this.session = session
        PersistentEntity associatedEntity = association.associatedEntity
        if(associatedEntity != null) {
            this.indexedEntity = associatedEntity
        }
        else if(association instanceof Basic) {
            this.indexedEntity = session.mappingContext.getPersistentEntity(((Basic)association).componentType.name)
        }
        if(this.indexedEntity == null) {
            throw new IllegalStateException("Cannot create association query loader for ${association}. No mapped associated entity could be located.")
        }
        this.association = association
        this.lazy = lazy
        this.singleResult = singleResult
    }

    @Override
    boolean doesReturnKeys() {
        return false
    }

    @Override
    List<Object> query(Serializable primaryKey) {

        StatementRunner statementRunner = session.hasTransaction() ? session.getTransaction().getNativeTransaction() : (Session)session.nativeInterface
        String relType

        GraphPersistentEntity parent = (GraphPersistentEntity)association.owner
        GraphPersistentEntity related = (GraphPersistentEntity)indexedEntity

        boolean isRelationship = related.isRelationshipEntity()

        if(isRelationship) {
            RelationshipPersistentEntity relEntity = (RelationshipPersistentEntity)related
            GraphPersistentEntity fromEntity = (GraphPersistentEntity) relEntity.getFrom().getAssociatedEntity()
            GraphPersistentEntity toEntity = (GraphPersistentEntity) relEntity.getTo().getAssociatedEntity()
            if(parent == fromEntity) {
                relType = "-[rel]->"
                related = toEntity
            }
            else {
                relType = "<-[rel]-"
                parent = toEntity
                related = fromEntity
            }
        }
        else {
            relType = RelationshipUtils.matchForAssociation(association)
        }

        String relationship = CypherBuilder.buildRelationship(parent.labelsAsString, relType, related.labelsAsString)

        StringBuilder cypher = new StringBuilder(CypherBuilder.buildRelationshipMatch(parent.labelsAsString, relType, related.labelsAsString))
        cypher.append(parent.formatId(RelationshipPersistentEntity.FROM))
              .append(" = {id} RETURN ")

        boolean isLazyToMany = lazy && !isRelationship && association instanceof ToMany
        if(isLazyToMany) {
            cypher.append(related.formatId(RelationshipPersistentEntity.TO))
                  .append(" as id")
        }
        else {
            if(!isRelationship) {
                cypher.append('to as data')
            }
            else {
                cypher.append('rel as rel')
            }
        }
        cypher.append(singleResult ? 'LIMIT 1' : '')


        Map<String,Object> params = (Map<String,Object>)Collections.singletonMap(GormProperties.IDENTITY, primaryKey)


        log.debug("Lazy loading association [${association}] using relationship $relationship")
        log.debug("QUERY Cypher [$cypher] for params [$params]")

        StatementResult result = statementRunner.run(cypher.toString(), params)
        if(isLazyToMany) {
            List<Object> results = []
            while(result.hasNext()) {
                def id = result.next().get(GormProperties.IDENTITY).asObject()
                results.add( session.proxy(related.javaClass, id as Serializable) )
            }
            return results
        }
        else {
            def resultList = new Neo4jResultList(0, result, isRelationship ? session.getEntityPersister(indexedEntity) : session.getEntityPersister(related))
            if(association.isBidirectional()) {
                def inverseSide = association.inverseSide
                if(inverseSide instanceof ToOne) {
                    def parentObject = session.getCachedInstance(association.getOwner().getJavaClass(), primaryKey)
                    if(parentObject != null) {
                        resultList.setInitializedAssociations(Collections.<Association,Object>singletonMap(inverseSide, parentObject))
                    }
                }
            }
            return resultList
        }
    }

}
