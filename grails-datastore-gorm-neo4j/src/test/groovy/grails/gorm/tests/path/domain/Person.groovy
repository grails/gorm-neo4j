package grails.gorm.tests.path.domain

import grails.gorm.annotation.Entity
import static grails.neo4j.mapping.MappingBuilder.*
import groovy.transform.EqualsAndHashCode

/**
 * Created by graemerocher on 14/03/2017.
 */
@Entity
// tag::class[]
@EqualsAndHashCode(includes = 'name')
class Person {
    String name
    static hasMany = [friends: Person]

    static mapping = node {
        id(generator:'assigned', name:'name')
    }
}
// end::class[]
