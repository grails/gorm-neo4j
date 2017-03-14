package grails.gorm.tests.cypher.domain

import grails.gorm.annotation.Entity

@Entity
// tag::class[]
class Owner {
    String name
    static hasMany = [pets:Pet]
}
// end::class[]