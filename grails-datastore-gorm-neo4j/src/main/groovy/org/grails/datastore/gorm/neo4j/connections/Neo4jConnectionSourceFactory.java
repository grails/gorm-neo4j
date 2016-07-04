package org.grails.datastore.gorm.neo4j.connections;

import groovy.transform.CompileStatic;
import groovy.util.logging.Slf4j;
import org.grails.datastore.gorm.neo4j.config.Settings;
import org.grails.datastore.gorm.neo4j.util.EmbeddedNeo4jServer;
import org.grails.datastore.mapping.core.connections.ConnectionSource;
import org.grails.datastore.mapping.core.connections.ConnectionSourceFactory;
import org.grails.datastore.mapping.core.connections.DefaultConnectionSource;
import org.grails.datastore.mapping.model.DatastoreConfigurationException;
import org.grails.datastore.mapping.reflect.ClassUtils;
import org.neo4j.driver.v1.AuthToken;
import org.neo4j.driver.v1.AuthTokens;
import org.neo4j.driver.v1.Config;
import org.neo4j.driver.v1.Driver;
import org.neo4j.driver.v1.GraphDatabase;
import org.neo4j.harness.ServerControls;
import org.springframework.core.env.PropertyResolver;

import java.io.File;
import java.io.Serializable;
import java.util.Map;

/**
 * A {@link org.grails.datastore.mapping.core.connections.ConnectionSourceFactory} for Neo4j
 *
 * @author Graeme Rocher
 * @since 6.0
 */
@CompileStatic
@Slf4j
public class Neo4jConnectionSourceFactory implements ConnectionSourceFactory<Driver, Neo4jConnectionSourceSettings> {
    @Override
    public ConnectionSource<Driver, Neo4jConnectionSourceSettings> create(String name, PropertyResolver configuration) {
        return create(name, configuration, null);
    }

    @Override
    public ConnectionSource<Driver, Neo4jConnectionSourceSettings> create(String name, PropertyResolver configuration, Neo4jConnectionSourceSettings fallbackSettings) {
        final boolean isDefaultConnection = ConnectionSource.DEFAULT.equals(name);
        String prefix = isDefaultConnection ? Settings.PREFIX : Settings.SETTING_CONNECTIONS + "." + name;
        Neo4jConnectionSourceSettingsBuilder settingsBuilder = new Neo4jConnectionSourceSettingsBuilder(configuration, prefix, fallbackSettings);
        Neo4jConnectionSourceSettings settings = settingsBuilder.build();


        final String url = settings.getUrl();
        final String username = settings.getUsername();
        final String password = settings.getPassword();
        final Neo4jConnectionSourceSettings.ConnectionType type = settings.getType();
        if(type == Neo4jConnectionSourceSettings.ConnectionType.embedded && isDefaultConnection) {
            if(ClassUtils.isPresent("org.neo4j.harness.ServerControls") && EmbeddedNeo4jServer.isAvailable()) {
                final String location = settings.getLocation();
                final Map options = settings.getEmbedded().getOptions();
                final File dataDir = location != null ? new File(location) : null;
                ServerControls serverControls;
                try {
                    serverControls = url != null ? EmbeddedNeo4jServer.start(url, dataDir, options) : EmbeddedNeo4jServer.start(dataDir, options);

                    Driver driver =  GraphDatabase.driver(serverControls.boltURI(), Config.build().withEncryptionLevel(Config.EncryptionLevel.NONE).toConfig());
                    return new Neo4jEmbeddedConnectionSource(name, driver, settings, serverControls);
                } catch (Throwable e) {
                    throw new DatastoreConfigurationException("Unable to start embedded Neo4j server: " + e.getMessage(), e);
                }
            }
            else {
                throw new DatastoreConfigurationException("Embedded Neo4j server was configured but 'neo4j-harness' classes not found on classpath.");
            }
        }

        AuthToken authToken = null;

        if(username != null && password != null) {
            authToken = AuthTokens.basic(username, password);
        }


        Driver driver = GraphDatabase.driver(url, authToken, settings.getOptions().build());
        return new DefaultConnectionSource<>(name, driver, settings);

    }

    @Override
    public Serializable getConnectionSourcesConfigurationKey() {
        return Settings.SETTING_CONNECTIONS;
    }
}
