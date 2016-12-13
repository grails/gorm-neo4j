package grails.neo4j

import groovy.transform.CompileStatic

/**
 * A domain class that represents a Neo4j Node
 *
 * @author Graeme Rocher
 * @since 6.1
 */
@CompileStatic
trait Node<D> extends Neo4jEntity<D> {
}