package org.grails.datastore.gorm.neo4j

import grails.neo4j.Direction
import groovy.transform.CompileStatic
import org.grails.datastore.gorm.neo4j.mapping.config.NodeConfig
import org.grails.datastore.gorm.neo4j.mapping.config.RelationshipConfig
import org.grails.datastore.mapping.model.IllegalMappingException
import org.grails.datastore.mapping.model.MappingContext
import org.grails.datastore.mapping.model.PersistentProperty
import org.grails.datastore.mapping.model.types.Association
import org.grails.datastore.mapping.model.types.Basic

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

    protected String type
    protected Direction direction = Direction.OUTGOING

    RelationshipPersistentEntity(Class javaClass, MappingContext context, boolean external = false) {
        super(javaClass, context, external)
        this.type = javaClass.simpleName.toUpperCase()
    }

    static boolean isRelationshipAssociation(PersistentProperty association) {
        return TO == association.name || (FROM == association.name)
    }

    @Override
    void initialize() {
        super.initialize()
        for(association in associations) {
            if(!isRelationshipAssociation(association) && !(association instanceof Basic)) {
                throw new IllegalMappingException("Invalid association $association. You cannot have associations to other nodes within a relationship entity")
            }
        }
        NodeConfig mappedForm = getMapping().getMappedForm()
        if(mappedForm instanceof RelationshipConfig) {
            RelationshipConfig rc = (RelationshipConfig) mappedForm
            this.type = rc.type ?: type
            this.direction = rc.direction
        }
        getFrom().getMapping().getMappedForm().setLazy(true)
        getTo().getMapping().getMappedForm().setLazy(true)
    }

    @Override
    String getVariableName() {
        return CypherBuilder.REL_VAR
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

    String buildMatch(String type = this.type(), String var = "r") {
        String toMatch = buildMatchForType(type, var)
        return "(from${fromEntity.labelsAsString})$toMatch"
    }

    String buildToMatch(String var = "r") {
        String type = type()
        return buildMatchForType(type, var)
    }

    String type() {
        return this.type
    }

    String buildMatchForType(String type, String var = "r") {
        boolean incoming = direction.isIncoming()
        boolean outgoing = direction.isOutgoing()
        if(type != null) {
            return "${incoming ? RelationshipUtils.INCOMING_CHAR : ''}-[$var:${type}]-${outgoing ? RelationshipUtils.OUTGOING_CHAR : ''}(to${toEntity.labelsAsString})"
        }
        else {
            return "${incoming ? RelationshipUtils.INCOMING_CHAR : ''}-[$var]-${outgoing ? RelationshipUtils.OUTGOING_CHAR : ''}(to${toEntity.labelsAsString})"
        }
    }
}
