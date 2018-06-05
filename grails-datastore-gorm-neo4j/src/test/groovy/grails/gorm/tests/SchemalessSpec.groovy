package grails.gorm.tests

import org.grails.datastore.gorm.neo4j.util.IteratorUtil
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Issue

class SchemalessSpec extends GormDatastoreSpec {

    private static Logger log = LoggerFactory.getLogger(SchemalessSpec.class);

    @Override
    List getDomainClasses() {
        [Pet, Club]
    }

    def "non declared properties should not mark the object as dirty if the value is the same"() {
        when:'An object is initially saved'
        def club = new Club(name: 'Cosima')
        club.buddy = 'Lara'
        club.gstring = "Name ${club.buddy}"
        club.save(flush:true)
        session.clear()

        club = Club.get(club.id)
        then:"it is not diry"
        club.buddy == 'Lara'
        club.gstring == 'Name Lara'
        !club.hasChanged()

        when:"A dynamic property is modified with the same value"
        club.buddy = 'Lara'

        then:"The object has no changes"
        !club.hasChanged()
        !club.hasChanged('buddy')

        when:"A dynamic property is modified with a different value"
        club.buddy = 'Foo'

        then:"The object has changes"
        club.hasChanged()
        club.hasChanged('buddy')



    }


    def "should non declared properties work for transient instances"() {
        setup:
            def club = new Club(name: 'Cosima')

        when:
            club.buddy = 'Lara'

        then:
            club.attributes() == [buddy: 'Lara']
            club.buddy == 'Lara'


        when: "setting null means deleting the property"
            club.buddy = null
            def x = club.buddy

        then:
            x == null
    }

    def "should non declared properties throw error if not set"() {
        setup:
            def club = new Club(name: 'Cosima')

        expect:
            club.buddy == null
    }

    def "should non declared properties get persisted"() {
        setup:
            def club = new Club(name: 'Cosima').save()
            club.buddy = 'Lara'

            def date = new Date()
            club.born = date
            club.save(flush:true)
            session.clear()

        when:
            club = Club.findByName('Cosima')

        then:
            club.buddy == 'Lara'

        and: "dates are converted to long"
            club.born == date.time

        and: "we have no additional properties"
        club.attributes().size() == 2
    }

    def "test handling of non-declared properties"() {
        when:
        def club = new Club(name:'person1').save()
        club['notDeclaredProperty'] = 'someValue'   // n.b. the 'dot' notation is not valid for undeclared properties
        club['emptyArray'] = []
        club['someIntArray'] = [1,2,3]
        club['someStringArray'] = ['a', 'b', 'c']
//        person['someDoubleArray'] = [0.9, 1.0, 1.1]
        session.flush()
        session.clear()
        club = Club.get(club.id)

        then:
        club['notDeclaredProperty'] == 'someValue'
        club['name'] == 'person1'  // declared properties are also available via map semantics
        club['someIntArray'] == [1,2,3]
        club['someStringArray'] == ['a', 'b', 'c']
//        person['someDoubleArray'] == [0.9, 1.0, 1.1]
    }

    def "test handling of non-declared properties using dot notation"() {
        setup:
        def club = new Club(name:'club1').save(flush:true)
        session.clear()
        club = Club.load(club.id)

        when:
        club.notDeclaredProperty = 'someValue'   // n.b. the 'dot' notation is not valid for undeclared properties
        club.emptyArray = []
        club.someIntArray = [1,2,3]
        club.someStringArray = ['a', 'b', 'c']
//        person.someDoubleArray= [0.9, 1.0, 1.1]
        session.flush()
        session.clear()
        club = Club.get(club.id)

        then:
        club.notDeclaredProperty == 'someValue'
        club.name == 'club1'  // declared properties are also available via map semantics
        club.someIntArray == [1,2,3]
        club.someStringArray == ['a', 'b', 'c']
        club.emptyArray == []
//        person.someDoubleArray == [0.9, 1.0, 1.1]
    }

    def "test null values on dynamic properties"() {
        setup:
        def club = new Club(name: 'person1').save(flush: true)
        session.clear()
        club = Club.load(club.id)
        when:
        club.notDeclaredProperty = null
        session.flush()
        session.clear()
        club = Club.get(club.id)

        then:
        club.notDeclaredProperty == null

        when:
        club.notDeclaredProperty = 'abc'
        session.flush()
        session.clear()
        club = Club.get(club.id)

        then:
        club.notDeclaredProperty == 'abc'

        when:
        club.notDeclaredProperty = null
        session.flush()
        session.clear()
        club = Club.get(club.id)

        then:
        club.notDeclaredProperty == null
    }

    @Issue("GPNEO4J-25")
    def "dynamic properties point to domain classes instance should be relationships"() {
        setup:
        def cosima = new Pet(name: 'Cosima')
        def lara = new Pet(name: 'Lara')
        cosima.buddy = lara
        cosima.buddies = lara  // NB plural version

        cosima.save()
        session.flush()
        session.clear()

        when:
        def result = session.transaction.nativeTransaction.execute("MATCH (n:Pet {__id__:{1}})-[:buddy]->(l) return l", [cosima.id])

        then:
        IteratorUtil.count(result) == 1

        when:
        Pet pet = Pet.findByName("Cosima")

        then: "reading dynamic rels works"
        pet.buddy.name == "Lara"
        !pet.hasChanged()

        and: "using plural named properties returns an array"
        Pet.findByName("Cosima").buddies*.name == ["Lara"]

        when:"The dynamic association is set to null"
        pet.buddy = null
        pet.buddies = null
        pet.save(flush:true)
        session.clear()
        pet = Pet.findByName("Cosima")

        then:"the association is cleared"
        pet.buddy == null
        pet.buddies == null

    }

    @Issue('https://github.com/grails/gorm-neo4j/issues/18')
    def "test clear dynamic collection association"() {
        setup:
        def cosima = new Pet(name: 'Cosima')
        def lara = new Pet(name: 'Lara')
        def fred = new Pet(name: 'Fred')
        cosima.buddies = [lara, fred]


        cosima.save()
        session.flush()
        session.clear()

        when:
        def result = session.transaction.nativeTransaction.execute("MATCH (n:Pet {__id__:{1}})-[:buddies]->(l) return l", [cosima.id])

        then:
        IteratorUtil.count(result) == 2

        when:"the relationship is cleared"
        def pet = Pet.findByName("Cosima")
        pet.buddies.clear()
        pet.save(flush:true)
        session.clear()
        result = session.transaction.nativeTransaction.execute("MATCH (n:Pet {__id__:{1}})-[:buddies]->(l) return l", [cosima.id])

        then:"The relationship is empty"
        Pet.findByName("Cosima").buddies == [] || Pet.findByName("Cosima").buddies == null
        IteratorUtil.count(result) == 0

    }

    def "test clear and set again dynamic collection association"() {
        setup:
        def cosima = new Pet(name: 'Cosima')
        def lara = new Pet(name: 'Lara')
        def fred = new Pet(name: 'Fred')
        cosima.buddies = [lara, fred]


        when:
        cosima.save()
        session.flush()
        session.clear()
        def pet = Pet.findByName("Cosima")
        pet.buddies.clear()
        pet.save(flush:true)
        session.clear()
        pet = Pet.findByName("Cosima")
        lara = Pet.findByName('Lara')
        pet.buddies = [lara]
        pet.save(flush:true)
        session.clear()
        pet = Pet.findByName("Cosima")
        def result = session.transaction.nativeTransaction.execute("MATCH (n:Pet {__id__:{1}})-[:buddies]->(l) return l", [cosima.id])

        then:"The relationship is empty"
        IteratorUtil.count(result) == 1
        pet.buddies instanceof Collection
        pet.buddies.size() == 1
        pet.buddies.first().name == 'Lara'

    }

    def "dynamic properties pointing to arrays of domain classes should be a relationship"() {
        setup:
        def cosima = new Pet(name: 'Cosima')
        def lara = new Pet(name: 'Lara')
        def samira = new Pet(name: 'Samira')
        cosima.buddies = [lara, samira]

        cosima.save()
        session.flush()
        session.clear()

        when:
        def result = session.transaction.nativeTransaction.execute("MATCH (n:Pet {__id__:{1}})-[:buddies]->(l) return l", [cosima.id])

        then:
        IteratorUtil.count(result) == 2

        and: "reading dynamic rels works"
        Pet.findByName("Cosima").buddies*.name.sort() == ["Lara", "Samira"]
    }

    def "same dynamic association on multiple objects"() {
        setup:
        def victor = new Pet(name: 'Victor')
        def fritz = new Pet(name: 'Fritz')
        def franz = new Pet(name: 'Franz')
        def heinrich = new Pet(name: 'Heinrich')
        victor.buddies = [fritz, franz, heinrich]
        fritz.buddies = [victor, franz, heinrich]
        franz.buddies = [victor, fritz, heinrich]
        heinrich.buddies = [victor, fritz, franz]

        victor.save()
        session.flush()
        session.clear()

        when:
        def victor_result = session.transaction.nativeTransaction.execute("MATCH (n:Pet {__id__:{1}})-[:buddies]->(l) return l", [victor.id])
        def fritz_result = session.transaction.nativeTransaction.execute("MATCH (n:Pet {__id__:{1}})-[:buddies]->(l) return l", [fritz.id])
        def franz_result = session.transaction.nativeTransaction.execute("MATCH (n:Pet {__id__:{1}})-[:buddies]->(l) return l", [franz.id])
        def heinrich_result = session.transaction.nativeTransaction.execute("MATCH (n:Pet {__id__:{1}})-[:buddies]->(l) return l", [heinrich.id])

        then:
        IteratorUtil.count(victor_result) == 3
        IteratorUtil.count(fritz_result) == 3
        IteratorUtil.count(franz_result) == 3
        IteratorUtil.count(heinrich_result) == 3

        and: "reading dynamic rels works"
        Pet.findByName("Victor").buddies*.name.sort() == ["Franz", "Fritz", "Heinrich"]
        Pet.findByName("Fritz").buddies*.name.sort() == ["Franz", "Heinrich", "Victor"]
        Pet.findByName("Franz").buddies*.name.sort() == ["Fritz", "Heinrich", "Victor"]
        Pet.findByName("Heinrich").buddies*.name.sort() == ["Franz", "Fritz", "Victor"]
    }

    def "nested associated objects "() {
        setup:
        def victor = new Pet(name: 'Victor')
        def fritz = new Pet(name: 'Fritz')
        def franz = new Pet(name: 'Franz')

        def heinrich = new Pet(name: 'Heinrich')
        def otto = new Pet(name: 'Otto')
        victor.buddies = [fritz, franz]
        fritz.cousins = [heinrich, otto]

        victor.save()
        session.flush()
        session.clear()

        when:
        def victor_buddies = session.transaction.nativeTransaction.execute("MATCH (n:Pet {__id__:{1}})-[:buddies]->(l) return l", [victor.id])
        def fritz_cousins = session.transaction.nativeTransaction.execute("MATCH (n:Pet {__id__:{1}})-[:cousins]->(l) return l", [fritz.id])

        then:
        IteratorUtil.count(victor_buddies) == 2
        IteratorUtil.count(fritz_cousins) == 2

        and: "reading dynamic rels works"
        Pet.findByName("Victor").buddies.find({it.name == "Fritz"}).cousins.name.sort() == ["Heinrich", "Otto"]
    }

    def "Test update dynamic single-ended relationships"() {
        setup:
        def cosima = new Pet(name: 'Cosima')
        def lara = new Pet(name: 'Lara')
        cosima.buddy = lara
        cosima.friends = lara  // NB plural version


        when:
        cosima.save()
        session.flush()
        session.clear()

        then:
        Pet.findByName("Cosima").buddy.name == "Lara"

        and: "using plural named properties returns an array"
        Pet.findByName("Cosima").friends*.name == ["Lara"]

        when:"The dynamic association is updated"
        cosima = Pet.findByName("Cosima")
        cosima.buddy = new Pet(name:"Fred")
        cosima.friends << new Pet(name: "Bob").save()
        cosima.save()
        session.flush()
        session.clear()

        then:
        Pet.findByName("Cosima").buddy.name == "Fred"

        and: "using plural named properties returns an array"
        Pet.findByName("Cosima").friends*.name.sort() == ["Bob","Lara"]
    }

    def "Test update newly created object"() {
        setup:
        def victor = new Pet(name: 'Victor')
        def fritz = new Pet(name: 'Fritz')
        def franz = new Pet(name: 'Franz')
        def heinrich = new Pet(name: 'Heinrich')
        victor.buddies = [fritz, franz]

        when:
        victor.save()
        heinrich.save()
        session.flush()

        then:
        victor.buddies.name.sort() == ['Franz', 'Fritz']

        when: "Adding an element"
        victor.buddies.add(heinrich)
        def cousin = victor.cousin

        then:
        victor.buddies.name.sort() == ['Franz', 'Fritz', 'Heinrich']

        when:
        victor.markDirty("buddies")
        victor.save()
        session.flush()
        victor.discard()
        victor = Pet.findByName("Victor")

        then:
        victor.buddies.name.sort() == ['Franz', 'Fritz', 'Heinrich']
    }

}







