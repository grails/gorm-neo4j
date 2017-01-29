package grails.gorm.tests

import grails.gorm.annotation.Entity
import grails.neo4j.Neo4jEntity
import grails.neo4j.Relationship

import javax.persistence.FetchType

/**
 * Created by graemerocher on 08/12/16.
 */
class RelationshipMappingSpec extends GormDatastoreSpec{

    @Override
    List getDomainClasses() {
        [Movie, CastMember, Celeb]
    }

    void "Test save an retrieve a relationship directly"() {
        when:"A new relationship is created"
        def keanu = new Celeb(name: "Keanu")
        def theMatrix = new Movie(title: "The Matrix")
        def newCastMember = new CastMember(from: keanu, to: theMatrix, roles: ["Neo"])
        newCastMember.putAt("foo", "bar")
        newCastMember
                .save(flush:true)

        then:"The CastMember count is correct"
        !newCastMember.errors.hasErrors()
        CastMember.count == 1
        CastMember.countByRoles(['Neo']) == 1

        when:"The relationship is updated"
        session.clear()
        CastMember cm = CastMember.first()
        cm.roles = ['Neo', 'Thomas Anderson']
        cm.save(flush:true)
        session.clear()
        cm = CastMember.get(cm.id)
        def roles = CastMember.where {
            id == cm.id
        }.property('roles').list()

        def actorNames = CastMember.where {
            id == cm.id
        }.property("from.name")
         .list()
        def countActorNames = CastMember.where {
            id == cm.id
        }.projections {
            countDistinct("from.name")
        }.get()

        List keanuCastings = CastMember.where {
            from.name == "Keanu"
        }.list()

        then:"The CastMember count is correct"
        keanuCastings.size() == 1
        countActorNames == 1
        actorNames == ["Keanu"]
        cm.dateCast != null
        cm.getAt("foo") == 'bar'
        CastMember.count == 1
        CastMember.findByFromAndTo(keanu, theMatrix) != null
        CastMember.countByRoles(['Neo', 'Thomas Anderson']) == 1
        cm.roles == ['Neo', 'Thomas Anderson']
        roles == ['Neo', 'Thomas Anderson']

        when:"The relationship is deleted"
        cm.delete(flush: true)

        then:"The relationship was deleted"
        CastMember.count == 0
        CastMember.countByRoles(['Neo', 'Thomas Anderson']) == 0

        and:"But the nodes were not deleted"
        Celeb.count == 1
        Movie.count == 1

    }


    void "Test query a relationship directly"() {
        given: " A relationship"
        Celeb c = new Celeb(name: "Keanu")
        c.save()
        Celeb c2 = new Celeb(name: "Carrie-Anne")
        c2.save()

        Celeb c3 = new Celeb(name: "Lana Wachowski")
        c3.save()

        Movie m = new Movie(title: "The Matrix")
        m.addToCast(
            new CastMember(type: "ACTED_IN", from: c, to: m, roles: ['Neo'])
        )
        m.addToCast(
            new CastMember(type: "DIRECTED", from: c3, to: m)
        )
        m.addToCast(
            new CastMember(type: "ACTED_IN", from: c2, to: m, roles: ['Trinity'])
        )
        m.save(flush:true)
        neo4jDatastore.currentSession.clear()


        when:"The relationship is lazy loaded"
        List<CastMember> cast = CastMember.list()

        then:"A result is returned"
        CastMember.findAllByType("DIRECTED").size() == 1
        cast.size() == 2
        cast.find { it.roles == ['Neo']}

        when:'A relationship is queried'
        CastMember cm = CastMember.findByRoles(['Neo'])

        then:"A result is returned"
        CastMember.countByRoles(['Neo']) == 1
        CastMember.countByTo(m) == 2
        CastMember.countByToInList([m]) == 2
        cm != null
        cm.id != null
        cm.roles == ['Neo']
        cm.from.name == "Keanu"

    }

    void "Test save and retrieve relationship"() {
        given: " A relationship"
        Celeb c = new Celeb(name: "Keanu")
        c.save()
        Celeb c2 = new Celeb(name: "Carrie-Anne")
        c2.save()
        Movie m = new Movie(title: "The Matrix")
        m.addToCast(
            new CastMember(type: "ACTED_IN", from: c, to: m, roles: ['Neo'])
        )
        m.addToCast(
            new CastMember(type: "ACTED_IN", from: c2, to: m, roles: ['Trinity'])
        )
        m.save(flush:true)
        neo4jDatastore.currentSession.clear()


        when:"The relationship is lazy loaded"
        m = Movie.first()


        then:"The relationship was retrieved"
        m.cast.size() == 2
        m.cast.find { it.roles == ['Neo'] }
        m.cast.find { it.type == 'ACTED_IN' }

        when:"The relationship is eagerly loaded"
        session.clear()
        m = Movie.first(fetch:[cast:FetchType.EAGER])


        then:"The relationship was retrieved"
        m.cast.size() == 2
        m.cast.find { it.to == m }
        m.cast.find { it.roles == ['Neo'] }
        m.cast.find { it.type == 'ACTED_IN' }

        when:'the relatinship is defined on the other side and queried'
        session.clear()
        c = Celeb.first()

        then:"the relationship is loaded correctly"
        c.name == "Keanu"
        c.appearances.size() == 1
        c.appearances.first().roles == ['Neo']
        c.appearances.first().from == c

        when:'the relationship is eager loaded'
        session.clear()
        c = Celeb.findByName("Keanu",[fetch:[appearances: FetchType.EAGER]])

        then:"the relationship is loaded correctly"
        c.name == "Keanu"
        c.appearances.size() == 1
        c.appearances.first().roles == ['Neo']
        c.appearances.first().from == c

    }

}

@Entity
class Movie implements Neo4jEntity<Movie> {
    String title
    static hasMany = [cast:CastMember]

    static mapping = {
        id generator:'native'
    }
}


@Entity
class CastMember implements Relationship<Celeb, Movie> {
    List<String> roles = []
    Date dateCast = new Date()
    String getJob() {
        type.toLowerCase()
    }
    String getName() {
        from.name
    }

    static mapping = {
        type "ACTED_IN"
    }
}

@Entity
class Celeb implements Neo4jEntity<Celeb>{
    String name
    static hasMany = [appearances:CastMember]
    static mapping = {
        id generator:'native'
    }

}