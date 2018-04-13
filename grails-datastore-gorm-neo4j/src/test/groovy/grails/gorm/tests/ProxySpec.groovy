package grails.gorm.tests

import grails.persistence.Entity

class ProxySpec extends GormDatastoreSpec {

    @Override
    List getDomainClasses() {
        return super.getDomainClasses() + [Owner, RockClub]
    }

    void "test proxy id for ToOne association"() {
        given:
        bootstrapData()
        session.clear()

        expect:
        RockClub.findByName("GR8 Club").owner.id ==
                Owner.findByName("John Doe2").id
    }

    void bootstrapData() {
        new Owner(name: "John Doe1").save(flush: true)
        Owner owner = new Owner(name: "John Doe2").save(flush: true)
        new RockClub(name: "GR8 Club", owner: owner).save(flush: true)
    }
}


@Entity
class Owner {
    String name
}

@Entity
class RockClub {
    String name
    Owner owner
}
