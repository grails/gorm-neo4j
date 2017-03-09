package org.grails.datastore.gorm.neo4j.api

import grails.gorm.multitenancy.Tenants
import groovy.transform.CompileStatic
import groovy.transform.InheritConstructors
import groovy.util.logging.Slf4j
import org.grails.datastore.gorm.GormStaticApi
import org.grails.datastore.gorm.finders.FinderMethod
import org.grails.datastore.gorm.neo4j.Neo4jDatastore
import org.grails.datastore.gorm.neo4j.Neo4jSession
import org.grails.datastore.gorm.neo4j.collection.Neo4jResultList
import org.grails.datastore.gorm.neo4j.extensions.Neo4jExtensions
import org.grails.datastore.mapping.core.Datastore
import org.grails.datastore.mapping.core.SessionCallback
import org.grails.datastore.mapping.model.config.GormProperties
import org.grails.datastore.mapping.multitenancy.MultiTenancySettings
import org.grails.datastore.mapping.multitenancy.exceptions.TenantNotFoundException
import org.grails.datastore.mapping.query.QueryException
import org.neo4j.driver.v1.Record
import org.neo4j.driver.v1.StatementResult
import org.neo4j.driver.v1.StatementRunner
import org.neo4j.driver.v1.util.Function
import org.springframework.transaction.PlatformTransactionManager

/**
 * Static API implementation for Neo4j
 *
 * @author Graeme Rocher
 * @since 6.1
 */
@CompileStatic
@Slf4j
class Neo4jGormStaticApi<D> extends GormStaticApi<D> {
    Neo4jGormStaticApi(Class<D> persistentClass, Datastore datastore, List<FinderMethod> finders) {
        super(persistentClass, datastore, finders)
    }

    Neo4jGormStaticApi(Class<D> persistentClass, Datastore datastore, List<FinderMethod> finders, PlatformTransactionManager transactionManager) {
        super(persistentClass, datastore, finders, transactionManager)
    }

    @Override
    List<D> findAll(CharSequence query, Map params, Map args) {
        (List<D>)execute({ Neo4jSession session ->
            StatementRunner boltSession = getStatementRunner(session)
            params = new LinkedHashMap(params)
            String queryString
            if(query instanceof GString) {
                queryString = buildNamedParameterQueryFromGString((GString) query, params)
            }
            else {
                queryString = query.toString()
            }

            includeTenantIdIfNecessary(session, queryString, params)
            if(log.isDebugEnabled()) {
                log.debug("QUERY Cypher [$queryString] for params [$params]")
            }
            StatementResult result = boltSession.run( queryString, (Map<String,Object>)params)
            def persister = session
                    .getEntityPersister(persistentEntity)

            if(result.hasNext()) {
                return new Neo4jResultList(0, result, persister)
            }
            else {
                return Collections.emptyList()
            }
        } as SessionCallback)
    }


    @Override
    List<D> findAll(CharSequence query, Collection params, Map args) {
        if(query instanceof GString) {
            throw new QueryException("Unsafe query [$query]. GORM cannot automatically escape a GString value when combined with ordinal parameters, so this query is potentially vulnerable to HQL injection attacks. Please embed the parameters within the GString so they can be safely escaped.");
        }
        (List<D>)execute({ Neo4jSession session ->
            StatementRunner boltSession = getStatementRunner(session)
            if(log.isDebugEnabled()) {
                log.debug("QUERY Cypher [$query] for params [$params]")
            }

            StatementResult result = Neo4jExtensions.execute(boltSession, query.toString(), (List<Object>)params.toList())
            def persister = session
                    .getEntityPersister(persistentEntity)

            if(result.hasNext()) {
                return new Neo4jResultList(0, result, persister)
            }
            else {
                return Collections.emptyList()
            }
        } as SessionCallback)
    }

    @Override
    D find(CharSequence query, Map params, Map args) {
        (D)execute({ Neo4jSession session ->
            StatementRunner boltSession = getStatementRunner(session)
            params = new LinkedHashMap(params)
            String queryString
            if(query instanceof GString) {
                queryString = buildNamedParameterQueryFromGString((GString) query, params)
            }
            else {
                queryString = query.toString()
            }
            includeTenantIdIfNecessary(session, queryString, params)
            if(log.isDebugEnabled()) {
                log.debug("QUERY Cypher [$queryString] for params [$params]")
            }

            StatementResult result = boltSession.run( queryString, (Map<String,Object>)params)
            def persister = session
                    .getEntityPersister(persistentEntity)

            def resultList = new Neo4jResultList(0, 1, (Iterator<Object>)result, persister)
            if( !resultList.isEmpty() ) {
                return (D)resultList.get(0)
            }
        } as SessionCallback)
    }

    @Override
    D find(CharSequence query, Collection params, Map args) {
        if(query instanceof GString) {
            throw new QueryException("Unsafe query [$query]. GORM cannot automatically escape a GString value when combined with ordinal parameters, so this query is potentially vulnerable to HQL injection attacks. Please embed the parameters within the GString so they can be safely escaped.");
        }

        (D)execute({ Neo4jSession session ->
            StatementRunner boltSession = getStatementRunner(session)
            if(log.isDebugEnabled()) {
                log.debug("QUERY Cypher [$query] for params [$params]")
            }

            StatementResult result = Neo4jExtensions.execute(boltSession, query.toString(), (List<Object>)params.toList())

            def persister = session
                    .getEntityPersister(persistentEntity)

            def resultList = new Neo4jResultList(0, 1, (Iterator<Object>)result, persister)
            if( !resultList.isEmpty() ) {
                return (D)resultList.get(0)
            }
            return null

        } as SessionCallback)
    }

    @Override
    List executeQuery(CharSequence query, Map params, Map args) {
        StatementResult result = cypherStatic(query, params)
        result.list({ Record r ->
            r.asMap()
        } as Function<Record, Map>)
    }

    @Override
    List executeQuery(CharSequence query, Collection params, Map args) {
        StatementResult result = cypherStatic(query, params.toList())
        result.list({ Record r ->
            r.asMap()
        } as Function<Record, Map>)

    }

    @Override
    Integer executeUpdate(CharSequence query, Map params, Map args) {
        return super.executeUpdate(query, params, args)
    }

    @Override
    Integer executeUpdate(CharSequence query, Collection params, Map args) {
        return super.executeUpdate(query, params, args)
    }

    /**
     * perform a cypher query
     *
     * @param query
     * @return
     */
    StatementResult cypherStatic(CharSequence query, List params) {
        if(query instanceof GString) {
            throw new QueryException("Unsafe query [$query]. GORM cannot automatically escape a GString value when combined with ordinal parameters, so this query is potentially vulnerable to HQL injection attacks. Please embed the parameters within the GString so they can be safely escaped.");
        }

        execute({ Neo4jSession session ->
            Map paramsMap = new LinkedHashMap()
            int i = 0
            for(p in params) {
                paramsMap.put(String.valueOf(++i), p)
            }
            String queryString = query.toString()
            if(log.isDebugEnabled()) {
                log.debug("QUERY Cypher [$queryString] for params [$params]")
            }

            includeTenantIdIfNecessary(session, queryString, (Map)paramsMap)
            StatementRunner boltSession = getStatementRunner(session)
            boltSession.run(queryString, paramsMap)
        } as SessionCallback<StatementResult>)
    }

    /**
     * perform a cypher query
     *
     * @param query
     * @return
     */
    StatementResult cypherStatic(CharSequence query, Map params ) {
        execute({ Neo4jSession session ->
            StatementRunner boltSession = getStatementRunner(session)
            params = new LinkedHashMap(params)
            String queryString
            if(query instanceof GString) {
                queryString = buildNamedParameterQueryFromGString((GString) query, params)
            }
            else {
                queryString = query.toString()
            }
            includeTenantIdIfNecessary(session, queryString, params)
            if(log.isDebugEnabled()) {
                log.debug("QUERY Cypher [$queryString] for params [$params]")
            }

            boltSession.run(queryString, params)
        } as SessionCallback<StatementResult>)
    }


    /**
     * perform a cypher query
     *
     * @param query
     * @return
     */
    StatementResult cypherStatic(CharSequence query) {
        execute({ Neo4jSession session ->
            Map params = [:]
            String queryString
            if(query instanceof GString) {
                queryString = buildNamedParameterQueryFromGString((GString) query, params)
            }
            else {
                queryString = query.toString()
            }
            if (persistentEntity.isMultiTenant() && session.getDatastore().multiTenancyMode == MultiTenancySettings.MultiTenancyMode.DISCRIMINATOR) {
                if (!queryString.contains("{tenantId}")) {
                    throw new TenantNotFoundException("Query does not specify a tenant id, but multi tenant mode is DISCRIMINATOR!")
                } else {
                    Map<String,Object> paramsMap = new LinkedHashMap<>()
                    paramsMap.put(GormProperties.TENANT_IDENTITY, Tenants.currentId(Neo4jDatastore))
                    StatementRunner boltSession = getStatementRunner(session)
                    if(log.isDebugEnabled()) {
                        log.debug("QUERY Cypher [$queryString] for params [$paramsMap]")
                    }

                    boltSession.run(queryString, paramsMap)
                }
            }
            else {
                StatementRunner boltSession = getStatementRunner(session)
                if(log.isDebugEnabled()) {
                    log.debug("QUERY Cypher [$queryString]")
                }

                boltSession.run(queryString)
            }

        } as SessionCallback<StatementResult>)
    }

    /**
     * Processes a query converting GString expressions into parameters
     *
     * @param query The query
     * @param params The parameters
     * @return The final String
     */
    protected String buildNamedParameterQueryFromGString(GString query, Map params) {
        StringBuilder sqlString = new StringBuilder()
        int i = 0
        Object[] values = query.values
        def strings = query.getStrings()
        for (str in strings) {
            sqlString.append(str)
            if (i < values.length) {
                String parameterName = "p$i"
                sqlString.append('{').append(parameterName).append('}')
                params.put(parameterName, values[i++])
            }
        }
        return sqlString.toString()
    }

    private StatementRunner getStatementRunner(Neo4jSession session) {
        return session.hasTransaction() ? session.getTransaction().getNativeTransaction() : session.getNativeInterface()
    }

    private void includeTenantIdIfNecessary(Neo4jSession session, String queryString, Map<String, Object> paramsMap) {
        if (persistentEntity.isMultiTenant() && session.getDatastore().multiTenancyMode == MultiTenancySettings.MultiTenancyMode.DISCRIMINATOR) {
            if (!queryString.contains("{tenantId}")) {
                throw new TenantNotFoundException("Query does not specify a tenant id, but multi tenant mode is DISCRIMINATOR!")
            } else {
                paramsMap.put(GormProperties.TENANT_IDENTITY, Tenants.currentId(Neo4jDatastore))
            }
        }
    }

}
