package functional.tests

import grails.gorm.transactions.Rollback
import org.grails.datastore.gorm.neo4j.Neo4jDatastore
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * Created by graemerocher on 18/11/16.
 */
class BookSpec extends Specification {

    @Shared @AutoCleanup Neo4jDatastore datastore = new Neo4jDatastore(getClass().getPackage())

    @Rollback
    void "test save and retrieve entity"() {
        when:"an entity is saved"
        new Book(title: "The Stand").save(flush:true)

        then:"A book was saved"
        Book.count() == 1
    }
}
