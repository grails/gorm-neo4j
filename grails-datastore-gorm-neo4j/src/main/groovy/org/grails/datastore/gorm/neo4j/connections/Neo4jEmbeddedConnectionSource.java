package org.grails.datastore.gorm.neo4j.connections;

import org.grails.datastore.mapping.core.connections.DefaultConnectionSource;
import org.neo4j.driver.Driver;
import org.neo4j.harness.Neo4j;

import java.io.IOException;

/**
 * A {@link org.grails.datastore.mapping.core.connections.ConnectionSource} for embedded Neo4j
 *
 * @author Graeme Rocher
 * @since 6.0
 */
public class Neo4jEmbeddedConnectionSource extends DefaultConnectionSource<Driver, Neo4jConnectionSourceSettings> {
    protected final Neo4j neo4j;

    Neo4jEmbeddedConnectionSource(String name, Driver source, Neo4jConnectionSourceSettings settings, Neo4j neo4j) {
        super(name, source, settings);
        this.neo4j = neo4j;
    }

    public Neo4j getServerInstance() {
        return neo4j;
    }

    @Override
    public void close() throws IOException {
        try {
            super.close();
        } finally {
            neo4j.close();
        }
    }
}
