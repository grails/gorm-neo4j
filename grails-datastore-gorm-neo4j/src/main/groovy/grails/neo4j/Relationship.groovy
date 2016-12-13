package grails.neo4j

import groovy.transform.CompileStatic
import org.grails.datastore.gorm.schemaless.DynamicAttributes

/**
 * Represents a Neo4j relationship
 *
 * @author Graeme Rocher
 * @since 6.1
 */
@CompileStatic
trait Relationship<F extends Neo4jEntity<F>, T extends Neo4jEntity<T>> implements DynamicAttributes {

    /**
     * The relationship type
     */
    String type = getClass().simpleName.toUpperCase()

    /**
     * The node where relationship originates from
     */
    F from

    /**
     * The node where the relationship goes to
     */
    T to


    /**
     * @return The type of the relationship
     */
    String type() {
        return type
    }

}