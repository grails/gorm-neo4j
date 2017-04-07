/* Copyright (C) 2015 original authors
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

package org.grails.datastore.gorm.neo4j.boot.autoconfigure

import groovy.transform.CompileStatic
import org.grails.datastore.gorm.events.ConfigurableApplicationContextEventPublisher
import org.grails.datastore.gorm.neo4j.Neo4jDatastore
import org.grails.datastore.gorm.neo4j.Neo4jDatastoreTransactionManager
import org.springframework.beans.BeansException
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.BeanFactoryAware
import org.springframework.boot.autoconfigure.AutoConfigurationPackages
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextAware
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.ResourceLoaderAware
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.core.io.ResourceLoader

/**
 * Auto configuration for GORM for Hibernate
 *
 * @author Graeme Rocher
 * @since 1.0
 */
@CompileStatic
@Configuration
@ConditionalOnMissingBean(Neo4jDatastore)
class Neo4jAutoConfiguration implements BeanFactoryAware, ResourceLoaderAware, ApplicationContextAware {

    BeanFactory beanFactory

    ResourceLoader resourceLoader

    ConfigurableApplicationContext applicationContext

    @Bean
    Neo4jDatastore neo4jDatastore() {
        List<String> packageNames = AutoConfigurationPackages.get(beanFactory)
        List<Package> packages = []
        for(name in packageNames) {
            Package pkg = Package.getPackage(name)
            if(pkg != null) {
                packages.add(pkg)
            }
        }

        new Neo4jDatastore(
                applicationContext.getEnvironment(),
                new ConfigurableApplicationContextEventPublisher(applicationContext),
                packages as Package[]
        )
    }

    @Bean
    Neo4jDatastoreTransactionManager neo4jDatastoreTransactionManager() {
        return neo4jDatastore().getTransactionManager()
    }

    @Override
    void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        if(!(applicationContext instanceof ConfigurableApplicationContext)) {
            throw new IllegalArgumentException("Neo4jAutoConfiguration requires an instance of ConfigurableApplicationContext")
        }
        this.applicationContext = (ConfigurableApplicationContext)applicationContext
    }
}
