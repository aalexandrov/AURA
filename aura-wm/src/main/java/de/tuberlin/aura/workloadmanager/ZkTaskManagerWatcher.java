package de.tuberlin.aura.workloadmanager;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.tuberlin.aura.core.common.eventsystem.IEventHandler;
import de.tuberlin.aura.core.descriptors.Descriptors.MachineDescriptor;
import de.tuberlin.aura.core.zookeeper.ZookeeperHelper;

/**
 * TODO: Put all watchers in one class like the descriptors?
 */
public class ZkTaskManagerWatcher implements Watcher {

    // ---------------------------------------------------
    // Fields.
    // ---------------------------------------------------

    private static final Logger LOG = LoggerFactory.getLogger(ZkTaskManagerWatcher.class);

    /**
     * Events received by this class are passed on to this handler.
     */
    private IEventHandler handler;

    /**
     * The connection to the ZooKeeper-cluster.
     */
    private ZooKeeper zk;

    // TODO: Secure
    public HashSet<String> nodes;

    // ---------------------------------------------------
    // Constructors.
    // ---------------------------------------------------

    /**
     * @param handler
     * @param zk
     */
    public ZkTaskManagerWatcher(IEventHandler handler, ZooKeeper zk) {
        this.handler = handler;
        this.zk = zk;
        this.nodes = new HashSet<>();
    }

    // ---------------------------------------------------
    // Public Methods.
    // ---------------------------------------------------

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void process(WatchedEvent event) {
        LOG.debug("Received event: " + event.getState().toString());
        LOG.debug("Received event: " + event.getType().toString());

        try {
            switch (event.getType()) {
                case NodeChildrenChanged:
                    // Find out whether a node was created or deleted.
                    List<String> nodeList = this.zk.getChildren(ZookeeperHelper.ZOOKEEPER_TASKMANAGERS, false);

                    de.tuberlin.aura.core.common.eventsystem.Event zkEvent;
                    if (this.nodes.size() < nodeList.size()) {
                        // A node has been added.
                        MachineDescriptor newNode = null;
                        for (String node : nodeList) {
                            if (!this.nodes.contains(node)) {
                                byte[] data = this.zk.getData(event.getPath() + "/" + node, false, null);
                                ByteArrayInputStream bais = new ByteArrayInputStream(data);
                                ObjectInputStream ois = new ObjectInputStream(bais);
                                newNode = (MachineDescriptor) ois.readObject();

                                this.nodes.add(node);
                                break;
                            }
                        }

                        zkEvent = new de.tuberlin.aura.core.common.eventsystem.Event(ZookeeperHelper.EVENT_TYPE_NODE_ADDED, newNode);
                    } else {
                        // A node has been removed.
                        String nodeName = null;
                        for (String node : this.nodes) {
                            if (!nodeList.contains(node)) {
                                nodeName = node;
                                break;
                            }
                        }

                        zkEvent =
                                new de.tuberlin.aura.core.common.eventsystem.Event(ZookeeperHelper.EVENT_TYPE_NODE_REMOVED, UUID.fromString(nodeName));
                    }

                    // Forward the event.
                    this.handler.handleEvent(zkEvent);
                    break;
                default:
                    // Nothing to do here.
            }

            // Stay interested in changes within the worker folder.
            this.zk.getChildren(ZookeeperHelper.ZOOKEEPER_TASKMANAGERS, this);
        } catch (IOException e) {
            LOG.error("Read from ZooKeeper failed", e);
        } catch (ClassNotFoundException e) {
            LOG.error("Class cast failed", e);
        } catch (KeeperException e) {
            LOG.error("ZooKeeper operation failed", e);
        } catch (InterruptedException e) {
            LOG.error("Interaction with ZooKeeper was interrupted", e);
        }
    }
}
