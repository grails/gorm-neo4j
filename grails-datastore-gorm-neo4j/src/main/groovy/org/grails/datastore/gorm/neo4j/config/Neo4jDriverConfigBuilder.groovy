package org.grails.datastore.gorm.neo4j.config

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.grails.datastore.gorm.neo4j.Neo4jDatastore
import org.grails.datastore.mapping.config.ConfigurationBuilder
import org.neo4j.driver.v1.Config
import org.springframework.core.env.PropertyResolver
/**
 * Constructs the Neo4j driver configuration
 *
 * @author Graeme Rocher
 * @since 6.0
 */
@CompileStatic
@Slf4j
class Neo4jDriverConfigBuilder extends ConfigurationBuilder<Config.ConfigBuilder, Config> {

    Neo4jDriverConfigBuilder(PropertyResolver propertyResolver) {
        super(propertyResolver, Neo4jDatastore.SETTING_NEO4J_DRIVER_PROPERTIES, "with")
    }

    @Override
    protected Config.ConfigBuilder createBuilder() {
        return Config.build()
    }

    @Override
    protected Config toConfiguration(Config.ConfigBuilder builder) {
        return builder.toConfig()
    }
}
