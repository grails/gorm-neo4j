package grails.gorm.tests

import grails.gorm.annotation.Entity
import grails.gorm.transactions.Rollback
import org.grails.datastore.gorm.neo4j.config.Settings
import org.neo4j.driver.v1.exceptions.ClientException

import static grails.neo4j.mapping.MappingBuilder.*
import org.grails.datastore.gorm.neo4j.Neo4jDatastore
import org.springframework.transaction.PlatformTransactionManager
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification
/**
 * Created by graemerocher on 10/03/2017.
 */
class AssignedIdSpec extends Specification {

    @Shared Map config = [(Settings.SETTING_NEO4J_TYPE):Settings.DATABASE_TYPE_EMBEDDED,
                          (Settings.SETTING_NEO4J_EMBEDDED_EPHEMERAL): true ]

    @Shared @AutoCleanup Neo4jDatastore datastore = new Neo4jDatastore(config,Fruit, Origin)
    @Shared PlatformTransactionManager transactionManager = datastore.getTransactionManager()

    @Rollback
    void "test save object with assigned identifier"() {
        when:"An object with an assigned id is saved"
        Origin origin = new Origin(country: "Brazil")
        origin.save()
        Fruit saved = new Fruit(name: "Apple", origin: origin).save()
        new Fruit(name: "Banana", origin: origin).save(flush:true)
        origin.discard()
        saved.discard()

        then:"it exists"
        saved != null
        !saved.hasErrors()
        Fruit.findByName('Apple').name == "Apple"
        Fruit.get('Apple').name == "Apple"
        Fruit.get('Apple').origin.country == "Brazil"

    }

    @Rollback
    void "test save many end object with assigned identifier"() {
        when:"An object with an assigned id is saved"
        Origin origin = new Origin(country: "Brazil")
                             .addToFruits(name:"Apple")
                             .addToFruits(name: "Banana")
        origin.save(flush:true)
        origin.discard()

        then:"it exists"
        Fruit.count() == 2
        Fruit.findByName('Apple').name == "Apple"
        Fruit.get('Apple').name == "Apple"
        Fruit.get('Apple').origin.country == "Brazil"

    }
}

@Entity
class Origin {
    String country
    static hasMany = [fruits:Fruit]
    static mapping = node {
        id {
            generator "assigned"
            name "country"
        }
    }
}
@Entity
class Fruit {
    String name
    static belongsTo = [origin: Origin]
    static mapping = node {
        id {
            generator "assigned"
            name "name"
        }
    }
}
