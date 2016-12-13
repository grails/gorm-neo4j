package org.grails.datastore.gorm.neo4j

import groovy.transform.CompileStatic
import org.grails.datastore.mapping.model.MappingContext
import org.grails.datastore.mapping.model.types.Association

/**
 * Represents a relationship
 *
 * @author Graeme Rocher
 * @since 6.1
 */
@CompileStatic
class RelationshipPersistentEntity extends GraphPersistentEntity {
    /**
     * The name of the from property
    */
    public static final String FROM = "from"
    /**
    * The name of the to property
    */
    public static final String TO = "to"

    /**
     * The relationship type
     */
    public static final String TYPE = "type"

    RelationshipPersistentEntity(Class javaClass, MappingContext context, boolean external = false) {
        super(javaClass, context, external)
    }

    @Override
    void initialize() {
        super.initialize()
        getFrom().getMapping().getMappedForm().setLazy(true)
        getTo().getMapping().getMappedForm().setLazy(true)
    }

    @Override
    boolean isVersioned() {
        return false
    }

    Association getFrom() {
        (Association)getPropertyByName(FROM)
    }

    Association getTo() {
        (Association)getPropertyByName(TO)
    }

    GraphPersistentEntity getFromEntity() {
        (GraphPersistentEntity)getFrom().getAssociatedEntity()
    }

    GraphPersistentEntity getToEntity() {
        (GraphPersistentEntity)getTo().getAssociatedEntity()
    }

    String buildMatch(String var = "r") {
        String toMatch = buildToMatch(var)
        return "(from${fromEntity.labelsAsString})$toMatch"
    }

    String buildToMatch(String var = "r") {
        "-[$var]->(to${toEntity.labelsAsString})"
    }
}
