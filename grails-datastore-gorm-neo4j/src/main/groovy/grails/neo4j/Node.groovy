package grails.neo4j

import groovy.transform.CompileStatic
import org.grails.datastore.gorm.GormEntity

/**
 * A domain class that represents a Neo4j Node
 *
 * @author Graeme Rocher
 * @since 6.1
 */
@CompileStatic
trait Node<D> implements Neo4jEntity<D>, GormEntity<D> {
}