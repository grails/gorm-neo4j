package org.grails.datastore.gorm.neo4j.util;

import java.nio.file.Path;
import org.grails.datastore.mapping.reflect.ClassUtils;
import org.neo4j.configuration.SettingBuilder;
import org.neo4j.configuration.connectors.BoltConnector;
import org.neo4j.configuration.helpers.SocketAddress;
import org.neo4j.harness.Neo4j;
import org.neo4j.harness.Neo4jBuilder;
import org.neo4j.harness.Neo4jBuilders;
import org.neo4j.server.ServerStartupException;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Collections;
import java.util.Map;

import static org.neo4j.configuration.SettingValueParsers.STRING;
import static org.neo4j.configuration.connectors.BoltConnector.EncryptionLevel.DISABLED;
import static org.neo4j.configuration.GraphDatabaseSettings.data_directory;

/**
 * Helper class for starting a Neo4j 3.x embedded server
 *
 * @author Graeme Rocher
 * @since 6.0
 */
public class EmbeddedNeo4jServer {

    /**
     * @return Whether the embedded server capability is available or not
     */
    public static boolean isAvailable() {
        return ClassUtils.isPresent("org.neo4j.harness.Neo4j", EmbeddedNeo4jServer.class.getClassLoader());
    }

    /**
     * Start a server on a random free port
     *
     * @return The server controls
     * @param dataLocation The data location
     */
    public static Neo4j start(File dataLocation) throws IOException {
        return attemptStartServer(0, dataLocation, Collections.<String, Object>emptyMap());
    }

    /**
     * Start a server on a random free port
     *
     * @return The server controls
     * @param dataLocation The data location
     */
    public static Neo4j start(File dataLocation, Map<String, Object> options) throws IOException {
        return attemptStartServer(0, dataLocation, options);
    }



    /**
     * Start a server on the given address
     *
     * @param inetAddr The inet address
     *
     * @return The {@link Neo4j}
     */
    public static Neo4j start(InetSocketAddress inetAddr) {
        return start(inetAddr.getHostName(),inetAddr.getPort(), null);
    }

    /**
     * Start a server on the given address
     *
     * @param inetAddr The inet address
     *
     * @return The {@link Neo4j}
     */
    public static Neo4j start(InetSocketAddress inetAddr, File dataLocation) {
        return start(inetAddr.getHostName(),inetAddr.getPort(), dataLocation);
    }

    /**
     * Start a server on the given address
     *
     * @param inetAddr The inet address
     *
     * @return The {@link Neo4j}
     */
    public static Neo4j start(InetSocketAddress inetAddr, File dataLocation, Map<String, Object> options) {
        return start(inetAddr.getHostName(),inetAddr.getPort(), dataLocation,options);
    }

    /**
     * Start a server on the given address
     *
     * @param address The address
     *
     * @return The {@link Neo4j}
     */
    public static Neo4j start(String address) {
        URI uri = URI.create(address);
        return start(new InetSocketAddress(uri.getHost(), uri.getPort()));
    }

    /**
     * Start a server on the given address
     *
     * @param address The address
     *
     * @return The {@link Neo4j}
     */
    public static Neo4j start(String address, File dataLocation) {
        URI uri = URI.create(address);
        return start(uri.getHost(), uri.getPort(), dataLocation);
    }


    /**
     * Start a server on the given address
     *
     * @param address The address
     *
     * @return The {@link Neo4j}
     */
    public static Neo4j start(String address, File dataLocation,Map<String, Object> options) {
        URI uri = URI.create(address);
        return start(uri.getHost(), uri.getPort(), dataLocation, options);
    }

    /**
     * Start a server on the given address
     *
     * @param host The host
     * @param port The port
     *
     * @return The {@link Neo4j}
     */
    public static Neo4j start(String host, int port) {
        return start(host, port,  null);
    }
    /**
     * Start a server on the given address
     *
     * @param host The host
     * @param port The port
     *
     * @return The {@link Neo4j}
     */
    public static Neo4j start(String host, int port, File dataLocation) {
        return start(host, port, dataLocation, Collections.<String, Object>emptyMap());
    }

    /**
     * Start a server on the given address
     *
     * @param host The host
     * @param port The port
     *
     * @return The {@link Neo4j}
     */
    public static Neo4j start(String host, int port, File dataLocation, Map<String, Object> options) {
        SocketAddress myBoltAddress = new SocketAddress(host, port);

        Neo4jBuilder serverBuilder = Neo4jBuilders.newInProcessBuilder()
                .withConfig(BoltConnector.enabled, true)
                .withConfig(BoltConnector.encryption_level, DISABLED)
                .withConfig(BoltConnector.listen_address, myBoltAddress);
        if(dataLocation != null) {
            serverBuilder = serverBuilder.withConfig(data_directory,  Path.of(dataLocation.toURI()));
        }

        for (String name : options.keySet()) {
            serverBuilder.withConfig(SettingBuilder.newBuilder(name, STRING, null).build(), options.get(name).toString());
        }

        return serverBuilder
                .build();
    }

    private static Neo4j attemptStartServer(int retryCount, File dataLocation, Map<String, Object> options) throws IOException {

        try {
            //In the new driver 0 implicitly means a random port
            return start("localhost", 0, dataLocation, options);
        } catch (ServerStartupException sse) {
            if(retryCount < 4) {
                return attemptStartServer(++retryCount, dataLocation, options);
            }
            else {
                throw sse;
            }
        }
    }
}
