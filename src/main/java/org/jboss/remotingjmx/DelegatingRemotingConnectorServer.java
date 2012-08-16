/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.remotingjmx;

import static org.jboss.remotingjmx.Constants.CHANNEL_NAME;
import static org.jboss.remotingjmx.Constants.JMX;
import static org.jboss.remotingjmx.Constants.JMX_BYTES;
import static org.jboss.remotingjmx.Constants.SNAPSHOT;
import static org.jboss.remotingjmx.Constants.STABLE;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import javax.management.remote.JMXServiceURL;

import org.jboss.logging.Logger;
import org.jboss.remoting3.Channel;
import org.jboss.remoting3.Endpoint;
import org.jboss.remoting3.MessageInputStream;
import org.jboss.remoting3.OpenListener;
import org.jboss.remoting3.Registration;
import org.jboss.remotingjmx.protocol.CancellableDataOutputStream;
import org.jboss.remotingjmx.protocol.Versions;
import org.xnio.IoUtils;
import org.xnio.OptionMap;

/**
 * A RemotingConnectorServer implementation that can delegate to multiple MBeanServers both local and remote through the use of
 * an MBeanServerLocator.
 *
 * @author <a href="mailto:darran.lofthouse@jboss.com">Darran Lofthouse</a>
 */
public class DelegatingRemotingConnectorServer {

    private static final Logger log = Logger.getLogger(DelegatingRemotingConnectorServer.class);

    private boolean started = false;
    private boolean stopped = false;

    /**
     * The Remoting Endpoint this ConnectorServer will register against when it is started.
     */
    private MBeanServerManager mbeanServerManager;
    private Endpoint endpoint;
    private Registration registration;
    private Executor executor;

    public DelegatingRemotingConnectorServer(final MBeanServerLocator mbeanServerLocator, final Endpoint endpoint) {
        this(mbeanServerLocator, endpoint, Executors.newCachedThreadPool());
    }

    public DelegatingRemotingConnectorServer(final MBeanServerLocator mbeanServerLocator, final Endpoint endpoint,
            final Executor executor) {
        this.mbeanServerManager = new DelegatingMBeanServerManager(mbeanServerLocator);
        this.endpoint = endpoint;
        this.executor = executor;
    }

    DelegatingRemotingConnectorServer(final MBeanServerManager mbeanServerManager, final Endpoint endpoint,
            final Executor executor) {
        this.mbeanServerManager = mbeanServerManager;
        this.endpoint = endpoint;
        this.executor = executor;
    }

    /*
     * Methods from JMXConnectorServerMBean
     */

    public void start() throws IOException {
        log.trace("start()");
        if (stopped) {
            throw new IOException("Unable to start connector as already stopped.");
        }

        // If this ConnectorServer has already started just return.
        if (started) {
            return;
        }

        log.trace("Registering service");
        registration = endpoint.registerService(CHANNEL_NAME, new ChannelOpenListener(), OptionMap.EMPTY);
        started = true;
    }

    public void stop() throws IOException {
        // If successfully stopped just return.
        if (stopped) {
            return;
        }

        try {
            if (started) {
                // TODO - How to correctly handle existing clients and notify them to disconnect?
                registration.close();
            }
        } finally {
            // Even if the connector server had not been started calling stop permenantly
            // disables the connector server.
            endpoint = null;
            registration = null;
            stopped = true;
        }

    }

    public boolean isActive() {
        // The connector server is active when it has been started but not stopped.
        return started && !stopped;
    }

    public JMXServiceURL getAddress() {
        // Using Remoting we don't have direct access to the address so for now
        // assume there isn't one available and return null.
        return null;
    }

    public Map<String, ?> getAttributes() {
        // TODO - What attributes are there to return?
        return Collections.emptyMap();
    }

    /**
     * Write the header message to the client.
     * <p/>
     * The header message will contain the following items to allow the client to select a version: -
     * <p/>
     * - The bytes for the characters 'JMX' - not completely fail safe but will allow early detection the client is connected to
     * the correct channel. - The number of versions supported by the server. (single byte) - The versions listed sequentially.
     * - A single byte to identify if the server is a SNAPSHOT release 0x00 = Stable, 0x01 - Snapshot
     *
     * @param channel
     * @throws IOException
     */
    private void writeVersionHeader(final Channel channel, final boolean fullVersionList) throws IOException {
        CancellableDataOutputStream dos = new CancellableDataOutputStream(channel.writeMessage());
        try {
            dos.writeBytes(JMX);
            byte[] versions = getSupportedVersions(fullVersionList);
            dos.writeInt(versions.length);
            dos.write(versions);
            if (Version.isSnapshot()) {
                dos.write(SNAPSHOT);
            } else {
                dos.write(STABLE);
            }

            if (fullVersionList) {
                String remotingJMXVersion = Version.getVersionString();
                byte[] versionBytes = remotingJMXVersion.getBytes("UTF-8");
                dos.writeInt(versionBytes.length);
                dos.write(versionBytes);
            }
        } catch (IOException e) {
            dos.cancel();
            throw e;
        } finally {
            dos.close();
        }
    }

    private byte[] getSupportedVersions(final boolean fullVersionList) {
        byte[] versions = Versions.getSupportedVersions();
        if (fullVersionList) {
            return versions;
        }
        for (byte current : versions) {
            // For older clients (1.0.4.Final and before) we send a special version list that allows them
            // to select protocol 0x01 whilst indicating to newer clients that there are more versions available.
            if (current == 0x01) {
                return new byte[] { 0x00, 0x01 };
            }
        }

        return new byte[] { 0x00 };
    }

    private class DelegatingMBeanServerManager implements MBeanServerManager {

        private final MBeanServerLocator mbeanServerLocator;

        public DelegatingMBeanServerManager(final MBeanServerLocator mbeanServerLocator) {
            this.mbeanServerLocator = mbeanServerLocator;
        }

        public WrappedMBeanServerConnection getDefaultMBeanServer() {
            // TODO Auto-generated method stub
            return null;
        }

    }

    /*
     * Handlers and Receivers
     */

    /**
     * The listener to handle the opening of the channel from remote clients.
     */
    private class ChannelOpenListener implements OpenListener {

        public void channelOpened(Channel channel) {
            log.trace("Channel Opened");

            try {
                writeVersionHeader(channel, false);
                channel.receiveMessage(new ClientVersionReceiver());
            } catch (IOException e) {
                log.error("Unable to send header, closing channel", e);
                IoUtils.safeClose(channel);
            }

        }

        public void registrationTerminated() {
            // To change body of implemented methods use File | Settings | File Templates.
        }
    }

    private class ClientVersionReceiver implements Channel.Receiver {

        public void handleMessage(Channel channel, MessageInputStream messageInputStream) {
            // The incoming message will be in the form [JMX {selected version}], once verified
            // the correct versioned proxy should be created and left to continue the communication.
            DataInputStream dis = new DataInputStream(messageInputStream);
            try {
                log.tracef("Bytes Available %d", dis.available());
                byte[] firstThree = new byte[3];
                dis.read(firstThree);
                log.tracef("First Three %s", new String(firstThree));
                if (Arrays.equals(firstThree, JMX_BYTES) == false) {
                    throw new IOException("Invalid leading bytes in header.");
                }
                log.tracef("Bytes Available %d", dis.available());
                byte version = dis.readByte();
                log.debugf("Chosen version 0x0%d", version);

                if (version == 0x00) {
                    int length = dis.readInt();
                    byte[] versionBytes = new byte[length];
                    dis.read(versionBytes);
                    String clientVersion = new String(versionBytes, "UTF-8");
                    log.debugf("Client version %s", clientVersion);

                    // This is the client saying it can handle the full version list.
                    writeVersionHeader(channel, true);
                    channel.receiveMessage(this);
                    return;
                }

                // The VersionedProxy is responsible for registering with the RemotingConnectorServer which
                // could vary depending on the version of the protocol.
                Versions.getVersionedProxy(version, channel, mbeanServerManager, executor);
            } catch (IOException e) {
                log.error("Error determining version selected by client.");
            } finally {
                IoUtils.safeClose(dis);
            }
        }

        public void handleError(Channel channel, IOException e) {
            log.warn("Error on channel before fully established.", e);
        }

        public void handleEnd(Channel channel) {
        }

    }

}
