package org.grails.datastore.gorm.neo4j.engine

import groovy.transform.CompileStatic
import org.grails.datastore.mapping.engine.EntityAccess
import org.grails.datastore.mapping.engine.ModificationTrackingEntityAccess

@CompileStatic
class Neo4jModificationTrackingEntityAccess extends ModificationTrackingEntityAccess {

    Neo4jModificationTrackingEntityAccess(EntityAccess target) {
        super(target)
    }

    @Override
    void setPropertyNoConversion(String name, Object value) {
        modifiedProperties.put(name, getProperty(name))
        target.setPropertyNoConversion(name, value)
    }

    /**
     * Sets a property value
     * @param name The name of the property
     * @param value The value of the property
     */
    @Override
    void setProperty(String name, Object value) {
        modifiedProperties.put(name, getProperty(name))
        target.setProperty(name, value)
    }
}
