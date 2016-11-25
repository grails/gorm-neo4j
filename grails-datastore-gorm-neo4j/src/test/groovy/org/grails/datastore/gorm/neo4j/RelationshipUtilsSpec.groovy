package org.grails.datastore.gorm.neo4j

import grails.gorm.annotation.Entity
import grails.neo4j.Direction
import org.grails.datastore.mapping.model.PersistentEntity
import spock.lang.Specification

/**
 * Created by graemerocher on 24/11/16.
 */
class RelationshipUtilsSpec extends Specification {

    void "Test get relationship type for association"() {
        given:
        def context = new Neo4jMappingContext()

        context.addPersistentEntities(Foo, Bar)
        context.initialize()
        PersistentEntity entity = context.getPersistentEntity(Foo.name)
        def associationMoreBars = entity.getPropertyByName('moreBars')
        def evenMoreBars = entity.getPropertyByName('evenMoreBars')
        def soManyBars = entity.getPropertyByName('soManyBars')

        expect:
        RelationshipUtils.relationshipTypeUsedFor(entity.getPropertyByName('bars')) == 'BARS'
        RelationshipUtils.relationshipTypeUsedFor(associationMoreBars) == 'MORE_BARZ'
        RelationshipUtils.matchForAssociation(associationMoreBars) == '-[:MORE_BARZ]->'
        RelationshipUtils.matchForAssociation(evenMoreBars) == '<-[:EVEN_MOREEE]-'
        RelationshipUtils.matchForAssociation(soManyBars) == '<-[:soManyBars]->'
    }


}
@Entity
class Foo {
    static hasMany = [bars:Bar, moreBars:Bar, evenMoreBars:Bar, soManyBars:Bar]

    static mapping = {
        moreBars type:"MORE_BARZ"
        evenMoreBars type:"EVEN_MOREEE", direction:Direction.INCOMING
        soManyBars direction:Direction.BOTH
    }
}
@Entity
class Bar {

}
