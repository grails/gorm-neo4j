package grails.gorm.tests.multitenancy

import grails.gorm.MultiTenant
import grails.gorm.annotation.Entity
import grails.neo4j.Neo4jEntity
import org.grails.datastore.gorm.neo4j.Neo4jDatastore
import org.grails.datastore.gorm.neo4j.config.Settings
import org.grails.datastore.gorm.neo4j.util.EmbeddedNeo4jServer
import org.grails.datastore.mapping.core.Session
import org.grails.datastore.mapping.multitenancy.exceptions.TenantNotFoundException
import org.grails.datastore.mapping.multitenancy.resolvers.SystemPropertyTenantResolver
import org.neo4j.driver.v1.exceptions.ClientException
import org.neo4j.driver.v1.exceptions.ServiceUnavailableException
import org.neo4j.harness.ServerControls
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * Created by graemerocher on 08/07/2016.
 */
class SingleTenancySpec extends Specification {
    @Shared @AutoCleanup Neo4jDatastore datastore
    @Shared @AutoCleanup ServerControls serverControls

    void setupSpec() {
        Map config = [
                "grails.gorm.multiTenancy.mode":"DATABASE",
                "grails.gorm.multiTenancy.tenantResolverClass":SystemPropertyTenantResolver,
                "grails.neo4j.options.encryptionLevel":"NONE",
                (Settings.SETTING_NEO4J_URL)        : "bolt://localhost:7687",
                (Settings.SETTING_NEO4J_BUILD_INDEX): false,
                (Settings.SETTING_CONNECTIONS)      : [
                        test1: [
                                url: "bolt://localhost:7688"
                        ],
                        test2: [
                                url: "bolt://localhost:7689"
                        ]
                ]
        ]
        this.datastore = new Neo4jDatastore(config, getDomainClasses() as Class[])
        this.serverControls = EmbeddedNeo4jServer.start("localhost", 7688)
    }

    void cleanupSpec() {
        datastore.close()
    }

    void setup() {
        System.setProperty(SystemPropertyTenantResolver.PROPERTY_NAME, "")
    }


    void "Test no tenant id"() {
        when:
        CompanyB.count()

        then:
        thrown(TenantNotFoundException)
    }

    void "Test persist and retrieve entities with multi tenancy"() {
        setup:

        when:"A tenant id is present"
        System.setProperty(SystemPropertyTenantResolver.PROPERTY_NAME, "test1")

        then:"the correct tenant is used"
        CompanyB.count() == 0

        when:"An object is saved"
        CompanyB.withTransaction {
            new CompanyB(name: "Foo").save(flush:true)
        }


        then:"The results are correct"
        CompanyB.count() == 1

        when:"The tenant id is switched"
        System.setProperty(SystemPropertyTenantResolver.PROPERTY_NAME, "test2")
        CompanyB.count() == 0

        then:"the correct tenant is used"
        def error = thrown(ServiceUnavailableException)
        error.message.contains("SSL Connection terminated")

    }

    List getDomainClasses() {
        [CompanyB]
    }
}

/**
 * Created by graemerocher on 30/06/16.
 */
@Entity
class CompanyB implements Neo4jEntity<CompanyB>, MultiTenant {
    Long id
    String name
}
