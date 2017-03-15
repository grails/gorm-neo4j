package grails.neo4j

/**
 * Represents a Neo4j path
 *
 * @author Graeme Rocher
 * @see org.neo4j.driver.v1.types.Path
 * @since 6.1
 */
interface Path<F, T> extends Iterable<Segment<F,T>> {

    /**
     * A segment
     *
     * @see org.neo4j.driver.v1.types.Path.Segment
     */
    interface Segment<F, T> {
        Relationship<F, T> relationship()

        F start()

        T end()
    }


    /** @return the start node of this path */
    F start()

    /** @return the end node of this path */
    T end()

    /** @return the number of segments in this path, which will be the same as the number of relationships */
    int length()

    /**
     * @return The domain instances that make up all the nodes
     */
    Iterable nodes()

    /**
     * Whether the path contains the given object. The entity should correctly implement equals/hashCode
     * @param o The object
     * @return True if it does
     */
    boolean contains(Object o)
}