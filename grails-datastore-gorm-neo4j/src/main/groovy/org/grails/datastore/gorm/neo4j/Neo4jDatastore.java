/* Copyright (C) 2010 SpringSource
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.grails.datastore.gorm.neo4j;

import org.grails.datastore.gorm.GormEnhancer;
import org.grails.datastore.gorm.GormInstanceApi;
import org.grails.datastore.gorm.GormStaticApi;
import org.grails.datastore.gorm.events.AutoTimestampEventListener;
import org.grails.datastore.gorm.events.ConfigurableApplicationEventPublisher;
import org.grails.datastore.gorm.events.DefaultApplicationEventPublisher;
import org.grails.datastore.gorm.events.DomainEventListener;
import org.grails.datastore.gorm.neo4j.connections.Neo4jConnectionSourceFactory;
import org.grails.datastore.gorm.neo4j.connections.Neo4jConnectionSourceSettings;
import org.grails.datastore.gorm.neo4j.connections.Neo4jConnectionSourceSettingsBuilder;
import org.grails.datastore.gorm.validation.constraints.MappingContextAwareConstraintFactory;
import org.grails.datastore.gorm.validation.constraints.builtin.UniqueConstraint;
import org.grails.datastore.gorm.validation.constraints.registry.DefaultValidatorRegistry;
import org.grails.datastore.mapping.config.Property;
import org.grails.datastore.mapping.config.Settings;
import org.grails.datastore.mapping.core.AbstractDatastore;
import org.grails.datastore.mapping.core.Datastore;
import org.grails.datastore.mapping.core.DatastoreUtils;
import org.grails.datastore.mapping.core.StatelessDatastore;
import org.grails.datastore.mapping.core.connections.*;
import org.grails.datastore.mapping.core.exceptions.ConfigurationException;
import org.grails.datastore.mapping.graph.GraphDatastore;
import org.grails.datastore.mapping.model.DatastoreConfigurationException;
import org.grails.datastore.mapping.model.MappingContext;
import org.grails.datastore.mapping.model.PersistentEntity;
import org.grails.datastore.mapping.model.PersistentProperty;
import org.grails.datastore.mapping.model.types.Simple;
import org.neo4j.driver.v1.Driver;
import org.neo4j.driver.v1.Session;
import org.neo4j.driver.v1.Transaction;
import org.neo4j.driver.v1.exceptions.Neo4jException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.PropertyResolver;
import org.springframework.core.env.StandardEnvironment;

import javax.annotation.PreDestroy;
import javax.persistence.FlushModeType;
import java.io.Closeable;
import java.io.IOException;
import java.util.*;

/**
 * Datastore implementation for Neo4j backend
 *
 * @author Stefan Armbruster (stefan@armbruster-it.de)
 * @author Graeme Rocher
 *
 * @since 1.0
 */
public class Neo4jDatastore extends AbstractDatastore implements Closeable, StatelessDatastore, GraphDatastore, Settings, ConnectionSourcesProvider<Driver, Neo4jConnectionSourceSettings> {

    private static Logger log = LoggerFactory.getLogger(Neo4jDatastore.class);

    protected boolean skipIndexSetup = false;
    protected final Driver boltDriver;
    protected final FlushModeType defaultFlushMode;
    protected final ConfigurableApplicationEventPublisher eventPublisher;
    protected final Neo4jDatastoreTransactionManager transactionManager;
    protected final GormEnhancer gormEnhancer;
    protected final ConnectionSources<Driver, Neo4jConnectionSourceSettings> connectionSources;
    protected final Map<String, Neo4jDatastore> datastoresByConnectionSource = new LinkedHashMap<>();

    /**
     * Configures a new {@link Neo4jDatastore} for the given arguments
     *
     * @param mappingContext The {@link MappingContext} which contains information about the mapped classes
     * @param eventPublisher The Spring ApplicationContext
     */
    public Neo4jDatastore(ConnectionSources<Driver, Neo4jConnectionSourceSettings> connectionSources, Neo4jMappingContext mappingContext, ConfigurableApplicationEventPublisher eventPublisher) {
        super(mappingContext, connectionSources.getBaseConfiguration(), null);
        this.connectionSources = connectionSources;
        ConnectionSource<Driver, Neo4jConnectionSourceSettings> defaultConnectionSource = connectionSources.getDefaultConnectionSource();
        Neo4jConnectionSourceSettings settings = defaultConnectionSource.getSettings();

        this.boltDriver = defaultConnectionSource.getSource();
        this.eventPublisher = eventPublisher;
        this.defaultFlushMode = settings.getFlushMode();
        this.skipIndexSetup = !settings.isBuildIndex();

        if(!skipIndexSetup) {
            setupIndexing();
        }

        transactionManager = new Neo4jDatastoreTransactionManager(this);
        if(!(connectionSources instanceof SingletonConnectionSources)) {

            Iterable<ConnectionSource<Driver, Neo4jConnectionSourceSettings>> allConnectionSources = connectionSources.getAllConnectionSources();
            for (ConnectionSource<Driver, Neo4jConnectionSourceSettings> connectionSource : allConnectionSources) {
                SingletonConnectionSources singletonConnectionSources = new SingletonConnectionSources(connectionSource, connectionSources.getBaseConfiguration());
                Neo4jDatastore childDatastore;

                if(ConnectionSource.DEFAULT.equals(connectionSource.getName())) {
                    childDatastore = this;
                }
                else {
                    childDatastore = new Neo4jDatastore(singletonConnectionSources, mappingContext, eventPublisher) {
                        @Override
                        protected GormEnhancer initialize(Neo4jConnectionSourceSettings settings) {
                            return null;
                        }
                    };
                }
                datastoresByConnectionSource.put(connectionSource.getName(), childDatastore);
            }
        }

        this.gormEnhancer = initialize(settings);
    }

    protected GormEnhancer initialize(Neo4jConnectionSourceSettings settings) {
        registerEventListeners(this.eventPublisher);

        this.mappingContext.addMappingContextListener(new MappingContext.Listener() {
            @Override
            public void persistentEntityAdded(PersistentEntity entity) {
                gormEnhancer.registerEntity(entity);
            }
        });

        return new GormEnhancer(this, transactionManager, settings) {

            @Override
            protected <D> GormStaticApi<D> getStaticApi(Class<D> cls, String qualifier) {
                Neo4jDatastore neo4jDatastore = getDatastoreForQualifier(cls, qualifier);
                return new GormStaticApi<>(cls, neo4jDatastore, getFinders(), neo4jDatastore.getTransactionManager());
            }

            @Override
            protected <D> GormInstanceApi<D> getInstanceApi(Class<D> cls, String qualifier) {
                Neo4jDatastore neo4jDatastore = getDatastoreForQualifier(cls, qualifier);
                return new GormInstanceApi<>(cls,neo4jDatastore);
            }

            private <D> Neo4jDatastore getDatastoreForQualifier(Class<D> cls, String qualifier) {
                String defaultConnectionSourceName = ConnectionSourcesSupport.getDefaultConnectionSourceName(getMappingContext().getPersistentEntity(cls.getName()));
                boolean isDefaultQualifier = qualifier.equals(ConnectionSource.DEFAULT);
                if(isDefaultQualifier && defaultConnectionSourceName.equals(ConnectionSource.DEFAULT)) {
                    return Neo4jDatastore.this;
                }
                else {
                    if(isDefaultQualifier) {
                        qualifier = defaultConnectionSourceName;
                    }
                    ConnectionSource<Driver, Neo4jConnectionSourceSettings> connectionSource = connectionSources.getConnectionSource(qualifier);
                    if(connectionSource == null) {
                        throw new ConfigurationException("Invalid connection ["+defaultConnectionSourceName+"] configured for class ["+cls+"]");
                    }
                    return Neo4jDatastore.this.datastoresByConnectionSource.get(qualifier);
                }
            }
        };
    }

    /**
     * Configures a new {@link Neo4jDatastore} for the given arguments
     *
     * @param eventPublisher The Spring ApplicationContext
     * @param classes The persistent classes
     */
    public Neo4jDatastore(ConnectionSources<Driver, Neo4jConnectionSourceSettings> connectionSources,  ConfigurableApplicationEventPublisher eventPublisher, Class...classes) {
        this(connectionSources, createMappingContext(connectionSources,classes), eventPublisher);
    }

    /**
     * Configures a new {@link Neo4jDatastore} for the given arguments
     *
     * @param classes The persistent classes
     */
    public Neo4jDatastore(ConnectionSources<Driver, Neo4jConnectionSourceSettings> connectionSources, Class...classes) {
        this(connectionSources, createMappingContext(connectionSources,classes), new DefaultApplicationEventPublisher());
    }


    /**
     * Configures a new {@link Neo4jDatastore} for the given arguments
     *
     * @param boltDriver The driver
     * @param configuration The configuration for the datastore
     * @param eventPublisher The Spring ApplicationContext
     * @param classes The persistent classes
     */
    public Neo4jDatastore(Driver boltDriver, PropertyResolver configuration, ConfigurableApplicationEventPublisher eventPublisher, Class...classes) {
        this(createDefaultConnectionSources(boltDriver, configuration), eventPublisher, classes);
    }

    /**
     * Configures a new {@link Neo4jDatastore} for the given arguments
     *
     * @param boltDriver The driver
     * @param eventPublisher The Spring ApplicationContext
     * @param classes The persistent classes
     */
    public Neo4jDatastore(Driver boltDriver, ConfigurableApplicationEventPublisher eventPublisher, Class...classes) {
        this(createDefaultConnectionSources(boltDriver, DatastoreUtils.createPropertyResolver(null)), eventPublisher, classes);
    }
    /**
     * Configures a new {@link Neo4jDatastore} for the given arguments
     *
     * @param boltDriver The driver
     * @param configuration The configuration for the datastore
     * @param classes The persistent classes
     */
    public Neo4jDatastore(Driver boltDriver, PropertyResolver configuration, Class...classes) {
        this(boltDriver, configuration, new DefaultApplicationEventPublisher(), classes);
    }

    /**
     * Configures a new {@link Neo4jDatastore} for the given arguments
     *
     * @param boltDriver The driver
     * @param classes The persistent classes
     */
    public Neo4jDatastore(Driver boltDriver, Class...classes) {
        this(boltDriver, new StandardEnvironment(), new DefaultApplicationEventPublisher(), classes);
    }


    /**
     * Configures a new {@link Neo4jDatastore} for the given arguments
     *
     * @param configuration The configuration for the datastore
     * @param eventPublisher The Spring ApplicationContext
     * @param classes The persistent classes
     */
    public Neo4jDatastore(PropertyResolver configuration, ConfigurableApplicationEventPublisher eventPublisher, Class...classes) {
        this(ConnectionSourcesInitializer.create(new Neo4jConnectionSourceFactory(), configuration), eventPublisher, classes);
    }

    /**
     * Configures a new {@link Neo4jDatastore} for the given arguments
     *
     * @param configuration The configuration for the datastore
     * @param classes The persistent classes
     */
    public Neo4jDatastore(PropertyResolver configuration, Class...classes) {
        this(configuration, new DefaultApplicationEventPublisher(), classes);
    }

    /**
     * Configures a new {@link Neo4jDatastore} for the given arguments
     *
     * @param classes The persistent classes
     */
    public Neo4jDatastore(Class...classes) {
        this(mapToPropertyResolver(null), classes);
    }

    /**
     * Configures a new {@link Neo4jDatastore} for the given arguments
     *
     * @param configuration The configuration
     * @param classes The persistent classes
     */
    public Neo4jDatastore(Map<String, Object> configuration, ConfigurableApplicationEventPublisher eventPublisher,Class...classes) {
        this(mapToPropertyResolver(configuration),eventPublisher, classes);
    }


    /**
     * Configures a new {@link Neo4jDatastore} for the given arguments
     *
     * @param configuration The configuration
     * @param classes The persistent classes
     */
    public Neo4jDatastore(Map<String, Object> configuration, Class...classes) {
        this(configuration, new DefaultApplicationEventPublisher(), classes);
    }

    /**
     * @return The transaction manager
     */
    public Neo4jDatastoreTransactionManager getTransactionManager() {
        return transactionManager;
    }


    @Override
    public ConfigurableApplicationEventPublisher getApplicationEventPublisher() {
        return this.eventPublisher;
    }

    /**
     * Creates the connection sources for an existing {@link Driver}
     *
     * @param driver The {@link Driver}
     * @param configuration The configuration
     * @return The {@link ConnectionSources}
     */
    protected static ConnectionSources<Driver, Neo4jConnectionSourceSettings> createDefaultConnectionSources(Driver driver, PropertyResolver configuration) {
        Neo4jConnectionSourceSettingsBuilder builder = new Neo4jConnectionSourceSettingsBuilder(configuration);
        Neo4jConnectionSourceSettings settings = builder.build();
        ConnectionSource<Driver, Neo4jConnectionSourceSettings> defaultConnectionSource = new DefaultConnectionSource<>(ConnectionSource.DEFAULT, driver, settings);
        return new InMemoryConnectionSources<>(defaultConnectionSource, new Neo4jConnectionSourceFactory(), configuration);
    }
    protected static Neo4jMappingContext createMappingContext(ConnectionSources<Driver, Neo4jConnectionSourceSettings> connectionSources, Class... classes) {
        ConnectionSource<Driver, Neo4jConnectionSourceSettings> defaultConnectionSource = connectionSources.getDefaultConnectionSource();
        Neo4jMappingContext neo4jMappingContext = new Neo4jMappingContext(defaultConnectionSource.getSettings(), classes);
        PropertyResolver configuration = connectionSources.getBaseConfiguration();
        DefaultValidatorRegistry defaultValidatorRegistry = new DefaultValidatorRegistry(neo4jMappingContext, configuration);
        defaultValidatorRegistry.addConstraintFactory(
                new MappingContextAwareConstraintFactory(UniqueConstraint.class, defaultValidatorRegistry.getMessageSource(), neo4jMappingContext)
        );
        neo4jMappingContext.setValidatorRegistry(
                defaultValidatorRegistry
        );
        return neo4jMappingContext;
    }


    protected void registerEventListeners(ConfigurableApplicationEventPublisher eventPublisher) {
        eventPublisher.addApplicationListener(new DomainEventListener(this));
        eventPublisher.addApplicationListener(new AutoTimestampEventListener(this));
    }

    public void setSkipIndexSetup(boolean skipIndexSetup) {
        this.skipIndexSetup = skipIndexSetup;
    }

    @Override
    protected org.grails.datastore.mapping.core.Session createSession(PropertyResolver connectionDetails) {
        final Neo4jSession neo4jSession = new Neo4jSession(this, mappingContext, eventPublisher, false, boltDriver);
        neo4jSession.setFlushMode(defaultFlushMode);
        return neo4jSession;
    }


    public void setupIndexing() {
        if(skipIndexSetup) return;
        List<String> schemaStrings = new ArrayList<String>(); // using set to avoid duplicate index creation

        for (PersistentEntity persistentEntity:  mappingContext.getPersistentEntities()) {
            if(persistentEntity.isExternal()) continue;

            if(log.isDebugEnabled()) {
                log.debug("Setting up indexing for entity " + persistentEntity.getName());
            }
            final GraphPersistentEntity graphPersistentEntity = (GraphPersistentEntity) persistentEntity;
            for (String label: graphPersistentEntity.getLabels()) {
                StringBuilder sb = new StringBuilder();
                if(graphPersistentEntity.getIdGenerator() != null) {
                    sb.append("CREATE CONSTRAINT ON (n:").append(label).append(") ASSERT n.").append(CypherBuilder.IDENTIFIER).append(" IS UNIQUE");
                    schemaStrings.add(sb.toString());
                }



                for (PersistentProperty persistentProperty : persistentEntity.getPersistentProperties()) {
                    Property mappedForm = persistentProperty.getMapping().getMappedForm();
                    if ((persistentProperty instanceof Simple) && (mappedForm != null) ) {

                        if(mappedForm.isUnique()) {
                            sb = new StringBuilder();

                            sb.append("CREATE CONSTRAINT ON (n:").append(label).append(") ASSERT n.").append(persistentProperty.getName()).append(" IS UNIQUE");
                            schemaStrings.add(sb.toString());
                        }
                        else if(mappedForm.isIndex()) {
                            sb = new StringBuilder();
                            sb.append("CREATE INDEX ON :").append(label).append("(").append(persistentProperty.getName()).append(")");
                            schemaStrings.add(sb.toString());
                            if(log.isDebugEnabled()) {
                                log.debug("setting up indexing for " + label + " property " + persistentProperty.getName());
                            }
                        }
                    }
                }
            }
        }

        org.neo4j.driver.v1.Session boltSession = boltDriver.session();

        final Transaction transaction = boltSession.beginTransaction();;
        try {
            for (String cypher: schemaStrings) {
                if(log.isDebugEnabled()) {
                    log.debug("CREATE INDEX Cypher [{}]", cypher);
                }
                transaction.run(cypher);
                transaction.success();
            }
        } catch(Throwable e) {
            log.error("Error creating Neo4j index: " + e.getMessage(), e);
            transaction.failure();
            throw new DatastoreConfigurationException("Error creating Neo4j index: " + e.getMessage(), e);
        }
        finally {
            transaction.close();
        }
        if(log.isDebugEnabled()) {
            log.debug("done setting up indexes");
        }
    }

    /**
     * @return The {@link Driver} used by this datastore
     */
    public Driver getBoltDriver() {
        return boltDriver;
    }

    @Override
    @PreDestroy
    public void close() throws IOException {
        try {
            try {
                gormEnhancer.close();
            } catch (Throwable e) {
                // ignore
            }
            try {
                connectionSources.close();
            } catch (Neo4jException e) {
                log.error("Error shutting down Bolt driver: " + e.getMessage(), e);
            }
            super.destroy();
        } catch (Exception e) {
            throw new IOException("Error shutting down Neo4j datastore", e);
        }
    }

    @Override
    public ConnectionSources<Driver, Neo4jConnectionSourceSettings> getConnectionSources() {
        return this.connectionSources;
    }
}