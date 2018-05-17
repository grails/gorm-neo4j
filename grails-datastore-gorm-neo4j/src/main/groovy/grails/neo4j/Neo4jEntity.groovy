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
package grails.neo4j

import grails.gorm.MultiTenant
import grails.gorm.api.GormAllOperations
import grails.gorm.multitenancy.Tenants
import groovy.transform.CompileStatic
import org.grails.datastore.gorm.GormEnhancer
import org.grails.datastore.gorm.GormEntity
import org.grails.datastore.gorm.GormStaticApi
import org.grails.datastore.gorm.neo4j.GraphPersistentEntity
import org.grails.datastore.gorm.neo4j.Neo4jDatastore
import org.grails.datastore.gorm.neo4j.Neo4jSession
import org.grails.datastore.gorm.neo4j.collection.Neo4jResultList
import org.grails.datastore.gorm.neo4j.engine.DynamicAssociationSupport
import org.grails.datastore.gorm.neo4j.engine.Neo4jEntityPersister
import org.grails.datastore.gorm.neo4j.extensions.Neo4jExtensions
import org.grails.datastore.gorm.schemaless.DynamicAttributes
import org.grails.datastore.mapping.core.AbstractDatastore
import org.grails.datastore.mapping.model.config.GormProperties
import org.grails.datastore.mapping.multitenancy.MultiTenancySettings
import org.grails.datastore.mapping.multitenancy.exceptions.TenantNotFoundException
import org.neo4j.driver.v1.StatementResult
import org.neo4j.driver.v1.StatementRunner
/**
 * Extends the default {@org.grails.datastore.gorm.GormEntity} trait, adding new methods specific to Neo4j
 *
 * @author Graeme Rocher
 * @since 5.0
 */
@CompileStatic
trait Neo4jEntity<D> implements GormEntity<D>, DynamicAttributes {

    /**
     * Allows accessing to dynamic properties with the dot operator
     *
     * @param instance The instance
     * @param name The property name
     * @return The property value
     */
    def propertyMissing(String name) {
        return getAtt(name)
    }


    /**
     * Allows setting a dynamic property via the dot operator
     * @param instance The instance
     * @param name The property name
     * @param val The value
     */
    def propertyMissing(String name, val) {
        DynamicAttributes.super.putAt(name, val)
    }


    /**
     * Obtains a dynamic attribute
     *
     * @param name The name of the attribute
     * @return The value of the attribute
     */
    @Override
    def getAt(String name) {
        return getAtt(name)
    }

    def getAtt(String name) {
        def val = DynamicAttributes.super.getAt(name)
        if(val == null) {
            GormStaticApi staticApi = GormEnhancer.findStaticApi(getClass())
            GraphPersistentEntity entity = (GraphPersistentEntity) staticApi.gormPersistentEntity
            if(entity.hasDynamicAssociations()) {
                def id = ident()
                if(id != null) {
                    staticApi.withSession { Neo4jSession session ->
                        DynamicAssociationSupport.loadDynamicAssociations(session, entity, this, id)
                    }
                    return DynamicAttributes.super.getAt(name)
                }
            }
        }
        return val
    }

    /**
     * perform a cypher query
     * @param queryString
     * @param params
     * @return
     */
    StatementResult cypher(String queryString, Map params ) {
        Neo4jSession session = (Neo4jSession)AbstractDatastore.retrieveSession(Neo4jDatastore)
        StatementRunner boltSession = getStatementRunner(session)
        params['this'] = session.getObjectIdentifier(this)
        includeTenantIdIfNecessary(session, queryString, params)
        boltSession.run(queryString, (Map<String,Object>)params)
    }

    /**
     * perform a cypher query
     * @param queryString
     * @param params
     * @return
     */
    StatementResult cypher(String queryString, List params ) {
        Neo4jSession session = (Neo4jSession)AbstractDatastore.retrieveSession(Neo4jDatastore)
        StatementRunner boltSession = getStatementRunner(session)

        Map<String,Object> paramsMap = new LinkedHashMap()
        paramsMap.put("this", session.getObjectIdentifier(this))

        includeTenantIdIfNecessary(session, queryString, paramsMap)

        int i = 0
        for(p in params) {
            paramsMap.put(String.valueOf(++i), p)
        }
        boltSession.run(queryString, paramsMap)
    }

    /**
     * perform a cypher query
     * @param queryString
     * @return
     */
    StatementResult cypher(String queryString) {
        Neo4jSession session = (Neo4jSession)AbstractDatastore.retrieveSession(Neo4jDatastore)
        Map<String,Object> arguments
        if(session.getDatastore().multiTenancyMode == MultiTenancySettings.MultiTenancyMode.DISCRIMINATOR) {
            if(!queryString.contains("{tenantId}")) {
                throw new TenantNotFoundException("Query does not specify a tenant id, but multi tenant mode is DISCRIMINATOR!")
            }
            else {
                arguments = new LinkedHashMap<String,Object>()
                arguments.put(GormProperties.TENANT_IDENTITY, Tenants.currentId(Neo4jDatastore))
                arguments.put("this", session.getObjectIdentifier(this))
            }
        }
        else {
            arguments = (Map<String,Object>)Collections.singletonMap("this", session.getObjectIdentifier(this))
        }
        StatementRunner boltSession = getStatementRunner(session)
        boltSession.run(queryString, arguments)
    }

    /**
     * perform a cypher query
     *
     * @param queryString
     * @return
     */
    static StatementResult cypherStatic(String queryString, Map params ) {
        Neo4jSession session = (Neo4jSession)AbstractDatastore.retrieveSession(Neo4jDatastore)
        StatementRunner boltSession = getStatementRunner(session)
        includeStaticTenantIdIfNecessary(session, queryString, params)
        boltSession.run(queryString, params)
    }

    /**
     * perform a cypher query
     *
     * @param queryString
     * @return
     */
    static StatementResult cypherStatic(String queryString, List params) {
        Neo4jSession session = (Neo4jSession)AbstractDatastore.retrieveSession(Neo4jDatastore)
        Map paramsMap = new LinkedHashMap()
        int i = 0
        for(p in params) {
            paramsMap.put(String.valueOf(++i), p)
        }
        includeStaticTenantIdIfNecessary(session, queryString, (Map)paramsMap)
        StatementRunner boltSession = getStatementRunner(session)
        boltSession.run(queryString, paramsMap)
    }

    /**
     * perform a cypher query
     *
     * @param queryString
     * @return
     */
    static StatementResult cypherStatic(String queryString) {
        Neo4jSession session = (Neo4jSession)AbstractDatastore.retrieveSession(Neo4jDatastore)
        if (MultiTenant.isAssignableFrom(this) && session.getDatastore().multiTenancyMode == MultiTenancySettings.MultiTenancyMode.DISCRIMINATOR) {
            if (!queryString.contains("{tenantId}")) {
                throw new TenantNotFoundException("Query does not specify a tenant id, but multi tenant mode is DISCRIMINATOR!")
            } else {
                Map<String,Object> paramsMap = new LinkedHashMap<>()
                paramsMap.put(GormProperties.TENANT_IDENTITY, Tenants.currentId(Neo4jDatastore))
                StatementRunner boltSession = getStatementRunner(session)
                boltSession.run(queryString, paramsMap)
            }
        }
        else {
            StatementRunner boltSession = getStatementRunner(session)
            boltSession.run(queryString)
        }
    }

    /**
     * @see {@link #cypherStatic(java.lang.String, java.util.Map)}
     */
    static List<D> executeQuery(String query, Map params = [:], Map args = Collections.emptyMap()) {
        Neo4jSession session =  (Neo4jSession)AbstractDatastore.retrieveSession(Neo4jDatastore)
        includeStaticTenantIdIfNecessary(session, query, params)
        def result = cypherStatic(query, params)
        return new Neo4jResultList(0, result,(Neo4jEntityPersister) session.getPersister(this))
    }

    /**
     * @see {@link #cypherStatic(java.lang.String, java.util.Map)}
     */
    static List<D> executeQuery(String query, Collection params, Map args) {
        def result = cypherStatic(query, params.toList())
        Neo4jSession session = (Neo4jSession)AbstractDatastore.retrieveSession(Neo4jDatastore)
        return new Neo4jResultList(0, result, (Neo4jEntityPersister)session.getPersister(this))
    }

    /**
     * Finds all results using the given cypher query, converting each result to a domain instance
     *
     * @param query The cypher query
     * @param params The positional parameters
     * @param args The arguments to the query
     * @return The results
     */
    static List<D> findAll(String query, Collection params, Map args) {
        Neo4jSession session = (Neo4jSession)AbstractDatastore.retrieveSession(Neo4jDatastore)

        StatementRunner boltSession = getStatementRunner(session)
        StatementResult result = Neo4jExtensions.execute(boltSession, query, (List<Object>)params.toList())
        def persister = session
                .getEntityPersister(this)

        if(result.hasNext()) {
            return new Neo4jResultList(0, result, persister)
        }
        else {
            return Collections.emptyList()
        }
    }

    /**
     * Varargs version of {@link #findAll(java.lang.String, java.util.Collection, java.util.Map)}
     */
    static List<D> findAll(String query, Object[] params) {
        Neo4jSession session = (Neo4jSession)AbstractDatastore.retrieveSession(Neo4jDatastore)

        StatementRunner boltSession = getStatementRunner(session)
        StatementResult result = Neo4jExtensions.execute(boltSession, query, Arrays.asList(params))
        def persister = session
                .getEntityPersister(this)

        if(result.hasNext()) {
            return new Neo4jResultList(0, result, persister)
        }
        else {
            return Collections.emptyList()
        }
    }

    /**
     * Finds all results using the given cypher query, converting each result to a domain instance
     *
     * @param query The cypher query
     * @param params The parameters
     * @param args The arguments to the query
     * @return The results
     */
    static List<D> findAll(String query, Map params = [:], Map args = Collections.emptyMap()) {
        Neo4jSession session = (Neo4jSession)AbstractDatastore.retrieveSession(Neo4jDatastore)

        StatementRunner boltSession = getStatementRunner(session)
        includeStaticTenantIdIfNecessary(session, query, params)
        StatementResult result = boltSession.run( query, (Map<String,Object>)params)
        def persister = session
                .getEntityPersister(this)

        if(result.hasNext()) {
            return new Neo4jResultList(0, result, persister)
        }
        else {
            return Collections.emptyList()
        }
    }

    /**
     * Finds a single result using the given cypher query, converting the result to a domain instance
     *
     * @param query The cypher query
     * @param params The positional parameters
     * @param args The arguments to the query
     * @return The results
     */
    static D find(String query, Collection params, Map args = Collections.emptyMap()) {
        Neo4jSession session = (Neo4jSession)AbstractDatastore.retrieveSession(Neo4jDatastore)

        StatementRunner boltSession = getStatementRunner(session)
        StatementResult result = Neo4jExtensions.execute(boltSession, query, (List<Object>)params.toList())

        def persister = session
                .getEntityPersister(this)

        def resultList = new Neo4jResultList(0, 1, (Iterator<Object>)result, persister)
        if( !resultList.isEmpty() ) {
            return (D)resultList.get(0)
        }
        return null
    }

    /**
     * Finds a single result using the given cypher query, converting the result to a domain instance
     *
     * @param query The cypher query
     * @param params The parameters
     * @param args The arguments to the query
     * @return The results
     */
    static D find(String query, Map params = [:], Map args = Collections.emptyMap()) {
        Neo4jSession session = (Neo4jSession)AbstractDatastore.retrieveSession(Neo4jDatastore)

        StatementRunner boltSession = getStatementRunner(session)
        includeStaticTenantIdIfNecessary(session, query, params)
        StatementResult result = boltSession.run( query, (Map<String,Object>)params)
        def persister = session
                .getEntityPersister(this)

        def resultList = new Neo4jResultList(0, 1, (Iterator<Object>)result, persister)
        if( !resultList.isEmpty() ) {
            return (D)resultList.get(0)
        }
        return null
    }

    /**
     * Varargs version of {@link #find(java.lang.String, java.util.Collection, java.util.Map)}
     */
    static D find(String query, Object[] params) {
        Neo4jSession session = (Neo4jSession)AbstractDatastore.retrieveSession(Neo4jDatastore)

        StatementRunner boltSession = getStatementRunner(session)
        StatementResult result = Neo4jExtensions.execute(boltSession, query, Arrays.asList(params))

        def persister = session
                .getEntityPersister(this)

        def resultList = new Neo4jResultList(0, 1, (Iterator<Object>)result, persister)
        if( !resultList.isEmpty() ) {
            return (D)resultList.get(0)
        }
        return null
    }

    /**
     * Perform an operation with the given connection
     *
     * @param connectionName The name of the connection
     * @param callable The operation
     * @return The return value of the closure
     */
    static <T> T withConnection(String connectionName, @DelegatesTo(GormAllOperations)Closure callable) {
        def staticApi = GormEnhancer.findStaticApi(this, connectionName)
        return (T)staticApi.withNewSession {
            callable.setDelegate(staticApi)
            return callable.call()
        }
    }

    private static StatementRunner getStatementRunner(Neo4jSession session) {
        return session.hasTransaction() ? session.getTransaction().getNativeTransaction() : session.getNativeInterface()
    }

    private void includeTenantIdIfNecessary(Neo4jSession session, String queryString, Map<String, Object> paramsMap) {
        if ((this instanceof MultiTenant) && session.getDatastore().multiTenancyMode == MultiTenancySettings.MultiTenancyMode.DISCRIMINATOR) {
            if (!queryString.contains("{tenantId}")) {
                throw new TenantNotFoundException("Query does not specify a tenant id, but multi tenant mode is DISCRIMINATOR!")
            } else {
                paramsMap.put(GormProperties.TENANT_IDENTITY, Tenants.currentId(Neo4jDatastore))
            }
        }
    }

    private static void includeStaticTenantIdIfNecessary(Neo4jSession session, String queryString, Map paramsMap) {
        if (MultiTenant.isAssignableFrom(this) && session.getDatastore().multiTenancyMode == MultiTenancySettings.MultiTenancyMode.DISCRIMINATOR) {
            if (!queryString.contains("{tenantId}")) {
                throw new TenantNotFoundException("Query does not specify a tenant id, but multi tenant mode is DISCRIMINATOR!")
            } else {
                paramsMap.put(GormProperties.TENANT_IDENTITY, Tenants.currentId(Neo4jDatastore))
            }
        }
    }
}