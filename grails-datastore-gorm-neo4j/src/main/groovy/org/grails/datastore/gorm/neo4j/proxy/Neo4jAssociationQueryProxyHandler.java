package org.grails.datastore.gorm.neo4j.proxy;

import org.grails.datastore.mapping.core.Session;
import org.grails.datastore.mapping.engine.AssociationQueryExecutor;
import org.grails.datastore.mapping.proxy.AssociationQueryProxyHandler;

import java.io.Serializable;

public class Neo4jAssociationQueryProxyHandler extends AssociationQueryProxyHandler {

    public Neo4jAssociationQueryProxyHandler(Session session, AssociationQueryExecutor executor, Serializable associationKey) {
        super(session, executor, associationKey);
    }

    @Override
    protected Object invokeEntityProxyMethods(Object self, String methodName, Object[] args) {
        if (methodName.equals(GET_ID_METHOD)) {
            return INVOKE_IMPLEMENTATION;
        } else {
            return super.invokeEntityProxyMethods(self, methodName, args);
        }
    }
}
