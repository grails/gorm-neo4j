package grails25.neo4j.hibernate

import grails.test.spock.IntegrationSpec

class Neo4jHibernateIntegrationSpec extends IntegrationSpec {

    def setup() {
    }

    def cleanup() {
    }

    void "test hibernate"() {
        expect:
        HibernateEntity.count() == 0
        !grails.neo4j.Neo4jEntity.isAssignableFrom(HibernateEntity)

    }

    void "test neo4j"() {
        expect:
        Neo4jEntity.count() == 0
        grails.neo4j.Neo4jEntity.isAssignableFrom(Neo4jEntity)

    }
}
