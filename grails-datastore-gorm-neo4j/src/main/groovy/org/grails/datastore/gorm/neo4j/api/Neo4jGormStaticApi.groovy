package org.grails.datastore.gorm.neo4j.api

import grails.gorm.multitenancy.Tenants
import grails.neo4j.Path
import grails.neo4j.Relationship
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.grails.datastore.gorm.GormEntity
import org.grails.datastore.gorm.GormStaticApi
import org.grails.datastore.gorm.finders.FinderMethod
import org.grails.datastore.gorm.neo4j.CypherBuilder
import org.grails.datastore.gorm.neo4j.GraphPersistentEntity
import org.grails.datastore.gorm.neo4j.Neo4jDatastore
import org.grails.datastore.gorm.neo4j.Neo4jSession
import org.grails.datastore.gorm.neo4j.RelationshipPersistentEntity
import org.grails.datastore.gorm.neo4j.collection.Neo4jPath
import org.grails.datastore.gorm.neo4j.collection.Neo4jRelationship
import org.grails.datastore.gorm.neo4j.collection.Neo4jResultList
import org.grails.datastore.gorm.neo4j.engine.Neo4jEntityPersister
import org.grails.datastore.gorm.neo4j.extensions.Neo4jExtensions
import org.grails.datastore.mapping.core.Datastore
import org.grails.datastore.mapping.core.SessionCallback
import org.grails.datastore.mapping.engine.EntityPersister
import org.grails.datastore.mapping.model.config.GormProperties
import org.grails.datastore.mapping.multitenancy.MultiTenancySettings
import org.grails.datastore.mapping.multitenancy.exceptions.TenantNotFoundException
import org.grails.datastore.mapping.query.QueryException
import org.neo4j.driver.v1.Record
import org.neo4j.driver.v1.StatementResult
import org.neo4j.driver.v1.StatementRunner
import org.neo4j.driver.v1.Value
import org.neo4j.driver.v1.summary.SummaryCounters
import org.neo4j.driver.v1.types.Node
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
        adaptResults(result)
    }

    protected List adaptResults(StatementResult result) {
        result.list({ Record r ->
            def map = r.asMap()
            if (map.size() == 1) {
                return map.values().first()
            } else {
                return map
            }
        } as Function<Record, Map>)
    }

    @Override
    List executeQuery(CharSequence query, Collection params, Map args) {
        StatementResult result = cypherStatic(query, params.toList())
        adaptResults(result)
    }

    @Override
    Integer executeUpdate(CharSequence query, Map params, Map args) {
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
                log.debug("UPDATE Cypher [$queryString] for params [$params]")
            }

            StatementResult sr = boltSession.run(queryString, params)
            return Neo4jEntityPersister.countUpdates(sr)
        } as SessionCallback<Integer>)
    }



    @Override
    Integer executeUpdate(CharSequence query, Collection params, Map args) {
        return Neo4jEntityPersister.countUpdates(cypherStatic(query, params.toList()) )
    }


    Path findPath(CharSequence query, Map params) {
        StatementResult result = cypherStatic(query, params)
        if(result.hasNext()) {
            Record record = result.next()
            GraphPersistentEntity graphEntity = (GraphPersistentEntity) persistentEntity
            return new Neo4jPath((Neo4jDatastore)datastore, record.values().first().asPath(), graphEntity, graphEntity)
        }
        return null
    }

    Path findPathTo(Class type, CharSequence query, Map params) {
        StatementResult result = cypherStatic(query, params)
        if(result.hasNext()) {
            Record record = result.next()
            GraphPersistentEntity graphEntity = (GraphPersistentEntity) persistentEntity
            GraphPersistentEntity toEntity = (GraphPersistentEntity)persistentEntity.mappingContext.getPersistentEntity(type.name)
            if(toEntity == null) {
                throw new QueryException("Target type [$type] is not a persistent entity")
            }
            return new Neo4jPath((Neo4jDatastore)datastore, record.values().first().asPath(), graphEntity, toEntity)
        }
        return null
    }

    public <F extends GormEntity, T extends GormEntity> Relationship<F, T> findRelationship(F from, T to) {
        (Relationship<F, T>)execute({ Neo4jSession session ->
            EntityPersister fromPersister = (EntityPersister) session.getPersister(from)
            EntityPersister toPersister = (EntityPersister) session.getPersister(to)
            GraphPersistentEntity fromEntity = (GraphPersistentEntity) fromPersister?.getPersistentEntity()
            GraphPersistentEntity toEntity = (GraphPersistentEntity) toPersister?.getPersistentEntity()
            if (fromEntity == null) {
                throw new QueryException("From type [$from] is not a persistent entity")
            }
            if (toEntity == null) {
                throw new QueryException("Target type [$to] is not a persistent entity")
            }
            Relationship<F, T> relationship = null
            if(from != null && to != null) {
                String query = """MATCH (from)-[r]-(to) 
WHERE ${fromEntity.formatId(RelationshipPersistentEntity.FROM)} = {start} AND ${toEntity.formatId(RelationshipPersistentEntity.TO)} = {end}
RETURN r
LIMIT 1"""
                List<org.neo4j.driver.v1.types.Relationship> results = executeQuery(query, [start:from.ident(), end:to.ident()])

                if(!results.isEmpty()) {
                    org.neo4j.driver.v1.types.Relationship neoRel = results.first()
                    relationship = new Neo4jRelationship<>(from, to, neoRel)
                    relationship.attributes(neoRel.asMap())
                }
            }
            return relationship

        } as SessionCallback)
    }

    public <F extends GormEntity, T extends GormEntity> List<Relationship<F, T>>  findRelationships(F from, T to, Map params = Collections.emptyMap()) {
        (List<Relationship>)execute({ Neo4jSession session ->
            EntityPersister fromPersister = (EntityPersister) session.getPersister(from)
            EntityPersister toPersister = (EntityPersister) session.getPersister(to)
            GraphPersistentEntity fromEntity = (GraphPersistentEntity) fromPersister?.getPersistentEntity()
            GraphPersistentEntity toEntity = (GraphPersistentEntity) toPersister?.getPersistentEntity()
            if (fromEntity == null) {
                throw new QueryException("From type [$from] is not a persistent entity")
            }
            if (toEntity == null) {
                throw new QueryException("Target type [$to] is not a persistent entity")
            }
            List<Relationship> rels = []
            if(from != null && to != null) {
                String skip = params.offset ? " SKIP ${Integer.valueOf(params.offset.toString())}" : ''
                String limit = params.max ? " LIMIT ${Integer.valueOf(params.max.toString())}" : ''
                String query = """MATCH (from)-[r]-(to) 
WHERE ${fromEntity.formatId(RelationshipPersistentEntity.FROM)} = {start} AND ${toEntity.formatId(RelationshipPersistentEntity.TO)} = {end}
RETURN DISTINCT(r)$skip$limit"""
                List<org.neo4j.driver.v1.types.Relationship> results = (List<org.neo4j.driver.v1.types.Relationship>) executeQuery(query, [start:from.ident(), end:to.ident()])

                for(org.neo4j.driver.v1.types.Relationship neoRel in results) {
                    def relationship = new Neo4jRelationship<F,T>(from, to, neoRel)
                    relationship.attributes(neoRel.asMap())
                    rels.add(relationship)
                }
            }
            return rels

        } as SessionCallback)
    }

    public <F extends GormEntity, T extends GormEntity> List<Relationship<F, T>>  findRelationships(Class<F> from, Class<T> to, Map params = Collections.emptyMap()) {
        (List<Relationship>)execute({ Neo4jSession session ->
            Neo4jEntityPersister fromPersister = (Neo4jEntityPersister) session.getPersister(from)
            Neo4jEntityPersister toPersister = (Neo4jEntityPersister) session.getPersister(to)
            GraphPersistentEntity fromEntity = (GraphPersistentEntity) fromPersister?.getPersistentEntity()
            GraphPersistentEntity toEntity = (GraphPersistentEntity) toPersister?.getPersistentEntity()
            if (fromEntity == null) {
                throw new QueryException("From type [$from] is not a persistent entity")
            }
            if (toEntity == null) {
                throw new QueryException("Target type [$to] is not a persistent entity")
            }
            List<Relationship> rels = []
            if(from != null && to != null) {
                String skip = params.offset ? " SKIP ${Integer.valueOf(params.offset.toString())}" : ''
                String limit = params.max ? " LIMIT ${Integer.valueOf(params.max.toString())}" : ''
                String query = """MATCH ${fromEntity.formatNode(RelationshipPersistentEntity.FROM)}-[r]-${toEntity.formatNode(RelationshipPersistentEntity.TO)}
RETURN DISTINCT(r), from, to$skip$limit"""
                StatementResult results = cypherStatic(query)

                while(results.hasNext()) {
                    Record record = results.next()
                    org.neo4j.driver.v1.types.Relationship neoRel = record.get(CypherBuilder.REL_VAR).asRelationship()
                    Node fromNode = record.get(RelationshipPersistentEntity.FROM).asNode()
                    Node toNode = record.get(RelationshipPersistentEntity.TO).asNode()
                    F fromObject = (F) fromPersister.unmarshallOrFromCache(fromEntity, fromNode)
                    T toObject = (T) fromPersister.unmarshallOrFromCache(toEntity, toNode)
                    def relationship = new Neo4jRelationship<F,T>(fromObject, toObject, neoRel)
                    relationship.attributes(neoRel.asMap())
                    rels.add(relationship)
                }
            }
            return rels

        } as SessionCallback)
    }

    public <F, T> Path<F, T> findShortestPath(F from, T to, int maxDistance = 10) {
        (Path<F, T>)execute({ Neo4jSession session ->
            EntityPersister fromPersister = (EntityPersister) session.getPersister(from)
            EntityPersister toPersister = (EntityPersister) session.getPersister(to)
            GraphPersistentEntity fromEntity = (GraphPersistentEntity) fromPersister?.getPersistentEntity()
            GraphPersistentEntity toEntity = (GraphPersistentEntity) toPersister?.getPersistentEntity()
            if(fromEntity == null) {
                throw new QueryException("From type [$from] is not a persistent entity")
            }
            if(toEntity == null) {
                throw new QueryException("Target type [$to] is not a persistent entity")
            }

            Serializable fromId = fromPersister.getObjectIdentifier(from)
            Serializable toId = toPersister.getObjectIdentifier(to)
            if(fromId == null) {
                throw new QueryException("From type [$from] has not been persisted (null id)")
            }
            if(toId == null) {
                throw new QueryException("Target type [$to] has not been persisted (null id)")
            }
            String query = """MATCH ${
                fromEntity.formatNode(RelationshipPersistentEntity.FROM)
            },${
                toEntity.formatNode(RelationshipPersistentEntity.TO)
            }, p = shortestPath((from)-[*..$maxDistance]-(to)) WHERE ${
                fromEntity.formatId(RelationshipPersistentEntity.FROM)
            } = {start} AND ${toEntity.formatId(RelationshipPersistentEntity.TO)} = {end} RETURN p"""
            StatementResult result = cypherStatic(query, [start: fromId, end: toId])
            if(result.hasNext()) {
                Record record = result.next()
                return new Neo4jPath((Neo4jDatastore)datastore, record.values().first().asPath(), (GraphPersistentEntity)fromEntity, (GraphPersistentEntity)toEntity)
            }
            return null

        } as SessionCallback)
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

                    return boltSession.run(queryString, paramsMap)
                }
            }
            else {
                StatementRunner boltSession = getStatementRunner(session)
                if(log.isDebugEnabled()) {
                    log.debug("QUERY Cypher [$queryString]")
                }

                if(params.isEmpty()) {
                    return boltSession.run(queryString)
                }
                else {
                    return boltSession.run(queryString, params)
                }
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
    static String buildNamedParameterQueryFromGString(GString query, Map params) {
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
