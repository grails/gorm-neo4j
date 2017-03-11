package grails.neo4j

import groovy.transform.CompileStatic
import org.grails.datastore.gorm.GormEnhancer
import org.grails.datastore.gorm.neo4j.RelationshipPersistentEntity
import org.grails.datastore.gorm.schemaless.DynamicAttributes

/**
 * Represents a Neo4j relationship
 *
 * @author Graeme Rocher
 * @since 6.1
 */
@CompileStatic
trait Relationship<F extends Neo4jEntity<F>, T extends Neo4jEntity<T>> implements DynamicAttributes, Serializable {

    /**
     * The relationship type
     */
    String type = ((RelationshipPersistentEntity)GormEnhancer.findEntity(getClass())).type()

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

    /**
     * @return True if they are equal
     */
    boolean equals(Object object) {
        if(object instanceof Relationship) {
            Relationship other = (Relationship)object
            return from?.ident() == other.from?.ident() && to?.ident() == other.to?.ident()
        }
        return false
    }

    /**
     * @return hashCode
     */
    int hashCode() {
        int result = type != null ? type.hashCode() : 0
        def fromId = from?.ident()
        if(fromId != null) {
            result = 31 * result + fromId.hashCode()
        }
        def toId = to?.ident()
        if(toId != null) {
            result = 31 * result + toId.hashCode()
        }
        return result
    }
}