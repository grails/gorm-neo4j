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
import org.grails.datastore.gorm.neo4j.Neo4jDatastore
import org.grails.datastore.gorm.neo4j.Neo4jSession
import org.grails.datastore.gorm.neo4j.api.Neo4jGormStaticApi
import org.grails.datastore.gorm.neo4j.collection.Neo4jResultList
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
        DynamicAttributes.super.getAt(name)
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
     * perform a cypher query
     * @param queryString
     * @param params
     * @return
     */
    StatementResult cypher(String queryString, Map params) {
        GormEnhancer.findDatastore(getClass()).withSession { Neo4jSession session ->
            StatementRunner boltSession = getStatementRunner(session)


            params['this'] = session.getObjectIdentifier(this)
            includeTenantIdIfNecessary(session, queryString, params)
            boltSession.run(queryString, (Map<String, Object>) params)
        }
    }

    /**
     * perform a cypher query
     * @param queryString
     * @param params
     * @return
     */
    StatementResult cypher(String queryString, List params) {
        GormEnhancer.findDatastore(getClass()).withSession { Neo4jSession session ->
            StatementRunner boltSession = getStatementRunner(session)

            Map<String, Object> paramsMap = new LinkedHashMap()
            paramsMap.put("this", session.getObjectIdentifier(this))

            includeTenantIdIfNecessary(session, queryString, paramsMap)

            int i = 0
            for (p in params) {
                paramsMap.put(String.valueOf(++i), p)
            }
            boltSession.run(queryString, paramsMap)
        }
    }

    /**
     * Execute cypher that finds a path to the given entity
     *
     * @param cypher The cypher
     * @return The path
     */
    static Path<D, D> findPath(CharSequence cypher) {
        ((Neo4jGormStaticApi) GormEnhancer.findStaticApi(this)).findPath(cypher, Collections.emptyMap())
    }

    /**
     * Execute cypher that finds a path to the given entity
     *
     * @param from The from entity (can be a proxy)
     * @param to The to entity (can be a proxy)
     * @param maxDistance The maximum distance to traverse (defaults to 10)
     * @return The path or null if non exists
     */
    static <F, T> Path<F, T> findShortestPath(F from, T to, int maxDistance = 10) {
        ((Neo4jGormStaticApi) GormEnhancer.findStaticApi(this)).findShortestPath(from, to, maxDistance)
    }
    /**
     * Execute cypher that finds a path to the given entity
     *
     * @param cypher The cypher
     * @param params The parameters
     * @return The path or null if non exists
     */
    static Path<D, D> findPath(CharSequence cypher, Map params) {
        ((Neo4jGormStaticApi) GormEnhancer.findStaticApi(this)).findPath(cypher, params)
    }

    /**
     * Execute cypher that finds a path to the given entity
     *
     * @param type The type to find a path to
     * @param cypher The cypher
     * @return The path
     */
    static <T> Path<D, T> findPathTo(Class<T> type, CharSequence cypher) {
        ((Neo4jGormStaticApi) GormEnhancer.findStaticApi(this)).findPathTo(type, cypher, Collections.emptyMap())
    }

    /**
     * Execute cypher that finds a path to the given entity
     *
     * @param cypher The cypher
     * @return The path
     */
    static <T> Path<D, T> findPathTo(Class<T> type, CharSequence cypher, Map params) {
        ((Neo4jGormStaticApi) GormEnhancer.findStaticApi(this)).findPathTo(type, cypher, params)
    }

    /**
     * perform a cypher query
     * @param queryString
     * @return
     */
    StatementResult cypher(String queryString) {
        GormEnhancer.findDatastore(getClass()).withSession { Neo4jSession session ->
            Map<String, Object> arguments
            if (session.getDatastore().multiTenancyMode == MultiTenancySettings.MultiTenancyMode.DISCRIMINATOR) {
                if (!queryString.contains("{tenantId}")) {
                    throw new TenantNotFoundException("Query does not specify a tenant id, but multi tenant mode is DISCRIMINATOR!")
                } else {
                    arguments = new LinkedHashMap<String, Object>()
                    arguments.put(GormProperties.TENANT_IDENTITY, Tenants.currentId(Neo4jDatastore))
                    arguments.put("this", session.getObjectIdentifier(this))
                }
            } else {
                arguments = (Map<String, Object>) Collections.singletonMap("this", session.getObjectIdentifier(this))
            }
            StatementRunner boltSession = getStatementRunner(session)
            boltSession.run(queryString, arguments)
        }
    }

    /**
     * perform a cypher query
     *
     * @param queryString
     * @return
     */
    static StatementResult cypherStatic(CharSequence queryString, Map params) {
        ((Neo4jGormStaticApi) GormEnhancer.findStaticApi(this)).cypherStatic(queryString, params)
    }

    /**
     * perform a cypher query
     *
     * @param queryString
     * @return
     */
    static StatementResult cypherStatic(CharSequence queryString, List params) {
        ((Neo4jGormStaticApi) GormEnhancer.findStaticApi(this)).cypherStatic(queryString, params)
    }

    /**
     * perform a cypher query
     *
     * @param queryString
     * @return
     */
    static StatementResult cypherStatic(CharSequence queryString) {
        ((Neo4jGormStaticApi) GormEnhancer.findStaticApi(this)).cypherStatic(queryString)
    }

    /**
     * Varargs version of {@link #findAll(java.lang.String, java.util.Collection, java.util.Map)}
     */
    static List<D> findAll(CharSequence query, Object[] params) {
        ((Neo4jGormStaticApi) GormEnhancer.findStaticApi(this)).findAll(query, Arrays.asList(params))
    }

    /**
     * Varargs version of {@link #findAll(java.lang.String, java.util.Collection, java.util.Map)}
     */
    static List<D> findAll(CharSequence query, Map params) {
        ((Neo4jGormStaticApi) GormEnhancer.findStaticApi(this)).findAll(query, params)
    }
    /**
     * Varargs version of {@link #findAll(java.lang.String, java.util.Collection, java.util.Map)}
     */
    static D find(CharSequence query, Object[] params) {
        ((Neo4jGormStaticApi) GormEnhancer.findStaticApi(this)).find(query, Arrays.asList(params))
    }

    /**
     * Varargs version of {@link #findAll(java.lang.String, java.util.Collection, java.util.Map)}
     */
    static D find(CharSequence query, Map params) {
        ((Neo4jGormStaticApi) GormEnhancer.findStaticApi(this)).find(query, params)
    }
    /**
     * Perform an operation with the given connection
     *
     * @param connectionName The name of the connection
     * @param callable The operation
     * @return The return value of the closure
     */
    static <T> T withConnection(String connectionName, @DelegatesTo(GormAllOperations) Closure callable) {
        def staticApi = GormEnhancer.findStaticApi(this, connectionName)
        return (T) staticApi.withNewSession {
            callable.setDelegate(staticApi)
            return callable.call()
        }
    }

    private StatementRunner getStatementRunner(Neo4jSession session) {
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
}