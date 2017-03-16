package grails.test.neo4j

import grails.config.Config
import groovy.transform.CompileStatic
import org.grails.config.PropertySourcesConfig
import org.grails.datastore.gorm.neo4j.Neo4jDatastore
import org.grails.datastore.gorm.neo4j.Neo4jSession
import org.grails.datastore.mapping.core.DatastoreUtils
import org.grails.datastore.mapping.model.MappingContext
import org.grails.datastore.mapping.transactions.Transaction
import org.springframework.boot.env.PropertySourcesLoader
import org.springframework.core.env.PropertyResolver
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.core.io.Resource
import org.springframework.core.io.ResourceLoader
import org.springframework.transaction.support.TransactionSynchronizationManager
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * Base class for Neo4j tests
 *
 * @author Graeme Rocher
 * @since 6.0.1
 */
@CompileStatic
abstract class Neo4jSpec extends Specification {

    @Shared
    @AutoCleanup
    Neo4jDatastore neo4jDatastore

    @Shared
    Neo4jSession session

    @Shared
    Transaction transaction

    /**
     * @return Obtains the mapping context
     */
    MappingContext getMappingContext() {
        neo4jDatastore.getMappingContext()
    }


    /**
     * @return The domain classes
     */
    protected List<Class> getDomainClasses() { [] }

    void setupSpec() {
        PropertySourcesLoader loader = new PropertySourcesLoader()
        ResourceLoader resourceLoader = new DefaultResourceLoader()
        def applicationYml = resourceLoader.getResource("application.yml")
        boolean hasConfig = false
        if(applicationYml != null && applicationYml.exists()) {
            hasConfig = true
            loader.load applicationYml
        }

        def applicationGroovy = resourceLoader.getResource("application.groovy")
        if(applicationGroovy != null && applicationGroovy.exists()) {
            hasConfig = true
            loader.load applicationGroovy
        }

        if(hasConfig) {

            Config config = new PropertySourcesConfig(loader.propertySources)
            List<Class> domainClasses = getDomainClasses()
            if (!domainClasses) {
                def packageToScan = getPackageToScan(config)
                neo4jDatastore = new Neo4jDatastore((PropertyResolver) config, packageToScan)
            }
            else {
                neo4jDatastore = new Neo4jDatastore((PropertyResolver) config, (Class[])domainClasses.toArray())
            }
        }
        else {
            List<Class> domainClasses = getDomainClasses()
            if (!domainClasses) {
                neo4jDatastore = new Neo4jDatastore(getClass().getPackage())
            }
            else {
                neo4jDatastore = new Neo4jDatastore((Class[])domainClasses.toArray())
            }
        }
    }

    void setup() {
        boolean existing = neo4jDatastore.hasCurrentSession()
        session = (Neo4jSession)( existing ? neo4jDatastore.currentSession : DatastoreUtils.bindSession(neo4jDatastore.connect()) )
        transaction = session.beginTransaction()
    }

    void cleanup() {
        transaction.rollback()
        if (!neo4jDatastore.hasCurrentSession()) {
            TransactionSynchronizationManager.unbindResource(neo4jDatastore)
            DatastoreUtils.closeSessionOrRegisterDeferredClose(session, neo4jDatastore)
        }
    }

    /**
     * Obtains the default package to scan
     *
     * @param config The configuration
     * @return The package to scan
     */
    protected Package getPackageToScan(Config config) {
        def p = Package.getPackage(config.getProperty('grails.codegen.defaultPackage', getClass().package.name))
        if(p == null) {
            p = getClass().package
        }
        return p
    }
}
