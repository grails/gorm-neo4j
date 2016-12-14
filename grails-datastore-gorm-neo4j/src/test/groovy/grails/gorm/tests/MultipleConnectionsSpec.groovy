package grails.gorm.tests

import grails.gorm.annotation.Entity
import grails.neo4j.Neo4jEntity
import org.grails.datastore.gorm.neo4j.Neo4jDatastore
import org.grails.datastore.gorm.neo4j.config.Settings
import org.neo4j.driver.v1.exceptions.ClientException
import org.neo4j.driver.v1.exceptions.ServiceUnavailableException
import spock.lang.Shared
import spock.lang.Specification

/**
 * Created by graemerocher on 05/07/2016.
 */
class MultipleConnectionsSpec extends Specification {

    @Shared Neo4jDatastore datastore

    void setupSpec() {
        Map config = [
                (Settings.SETTING_NEO4J_URL) : "bolt://localhost:7687",
                (Settings.SETTING_NEO4J_BUILD_INDEX) :false,
                (Settings.SETTING_CONNECTIONS): [
                        test1: [
                                url: "bolt://localhost:7688"
                        ],
                        test2: [
                                url: "bolt://localhost:7689"
                        ]
                ]
        ]
        this.datastore = new Neo4jDatastore(config, getDomainClasses() as Class[])
    }

    void cleanupSpec() {
        datastore.close()
    }


    void "Test query multiple data sources"() {

        when:"An entity is saved"
        new CompanyA(name:"One").save(flush:true)

        then:
        def error = thrown(ServiceUnavailableException)
        error.message.contains("SSL Connection terminated")

        when:"An entity is saved"
        CompanyA.withConnection("test2") {
            save(new CompanyA(name:"One"), [flush: true])
        }

        then:
        def error2 = thrown(ServiceUnavailableException)
        error2.message.contains("SSL Connection terminated")
    }

    List getDomainClasses() {
        [CompanyA]
    }
}

/**
 * Created by graemerocher on 30/06/16.
 */
@Entity
class CompanyA implements Neo4jEntity<CompanyA> {
    Long id
    String name
    static mapping = {
        connections "test1", "test2"
    }
}


