package grails.gorm.tests.cypher

import grails.gorm.tests.cypher.domain.*
import grails.gorm.tests.cypher.domain.Pet
import grails.gorm.transactions.Rollback
import org.grails.datastore.gorm.neo4j.Neo4jDatastore
import spock.lang.*

class OneToManyCreateSpec extends Specification {

    @Shared @AutoCleanup Neo4jDatastore datastore = new Neo4jDatastore(Owner, Pet)

    @Rollback
    void "test save one-to-many"() {
        given:
        // tag::save[]
        new Owner(name:"Fred")
            .addToPets(name: "Dino")
            .save(flush:true)
            .discard()
        // end::save[]
        expect:
        Owner.count == 1
        Owner.first().pets.size() == 1
    }
}
