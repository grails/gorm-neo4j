package grails.gorm.tests

import grails.gorm.annotation.Entity
import grails.neo4j.Neo4jEntity

/**
 * Created by graemerocher on 24/10/16.
 */
class CustomLabelWithDynamicAssociationSpec extends GormDatastoreSpec {

    void "test custom labels with dynamic associations"() {
        when:"A club is saved"
        Club1 c = new Club1(name: "Manchester United")
        c.captain = new Player1(name: "Cantona")
        c.otherPlayers = [ new Player1(name: "Giggs")]
        c.save(flush:true)
        session.clear()

        c = Club1.first()

        then:"The associations are correct"
        c.captain.name == "Cantona"
        c.otherPlayers.size() == 1

    }
    @Override
    List getDomainClasses() {
        [Club1, Player1]
    }
}

@Entity
class Player1 implements Neo4jEntity<Player1> {
    String name
    static mapping = {
        dynamicAssociations true
        labels '__Player__'
    }
}
@Entity
class Club1 implements Neo4jEntity<Club1> {
    String name
    static mapping = {
        dynamicAssociations true
        labels '__Club__'
    }
}
