/*
 Copyright 2013 Yen Pai ypai@reign.io

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */

package io.reign.presence;

import io.reign.AbstractService;
import io.reign.DataSerializer;
import io.reign.JsonDataSerializer;
import io.reign.PathType;
import io.reign.coord.CoordinationService;
import io.reign.coord.DistributedLock;
import io.reign.mesg.RequestMessage;
import io.reign.mesg.ResponseMessage;
import io.reign.mesg.SimpleResponseMessage;
import io.reign.util.ZkClientUtil;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service discovery service.
 * 
 * @author ypai
 * 
 */
public class PresenceService extends AbstractService {

    private static final Logger logger = LoggerFactory.getLogger(PresenceService.class);

    public static final int DEFAULT_ZOMBIE_CHECK_INTERVAL_MILLIS = 300000;

    public static final int DEFAULT_HEARTBEAT_INTERVAL_MILLIS = 30000;

    public static final int DEFAULT_EXECUTION_INTERVAL_MILLIS = 2000;

    private int heartbeatIntervalMillis = DEFAULT_HEARTBEAT_INTERVAL_MILLIS;

    private int zombieCheckIntervalMillis = DEFAULT_ZOMBIE_CHECK_INTERVAL_MILLIS;

    private DataSerializer<Map<String, String>> nodeAttributeSerializer = new JsonDataSerializer<Map<String, String>>();

    private final ConcurrentMap<String, Announcement> announcementMap = new ConcurrentHashMap<String, Announcement>(8,
            0.9f, 2);

    private final ConcurrentMap<String, PresenceObserver> notifyObserverMap = new ConcurrentHashMap<String, PresenceObserver>(
            8, 0.9f, 2);

    private final ZkClientUtil zkClientUtil = new ZkClientUtil();

    private volatile long lastZombieCheckTimestamp = System.currentTimeMillis();

    private ScheduledExecutorService executorService;

    @Override
    public synchronized void init() {
        if (executorService != null) {
            return;
        }

        logger.info("init() called");

        executorService = new ScheduledThreadPoolExecutor(2);

        if (this.getHeartbeatIntervalMillis() < 1000) {
            this.setHeartbeatIntervalMillis(DEFAULT_HEARTBEAT_INTERVAL_MILLIS);
        }

        // schedule admin activity
        Runnable adminRunnable = new AdminRunnable();// Runnable
        executorService.scheduleAtFixedRate(adminRunnable, this.heartbeatIntervalMillis / 2,
                this.heartbeatIntervalMillis, TimeUnit.MILLISECONDS);

    }

    @Override
    public void destroy() {
        executorService.shutdown();
    }

    public List<String> lookupClusters() {
        /** get node data from zk **/
        String path = getPathScheme().getAbsolutePath(PathType.PRESENCE);
        List<String> children = Collections.EMPTY_LIST;
        try {
            Stat stat = new Stat();
            children = getZkClient().getChildren(path, true, stat);

            // getPathCache().put(path, stat, null, children);

        } catch (KeeperException e) {
            if (e.code() == Code.NONODE) {
                logger.warn("lookupClusters():  " + e + ":  node does not exist:  path={}", path);
            } else {
                logger.warn("lookupClusters():  " + e, e);
            }
            return Collections.EMPTY_LIST;
        } catch (InterruptedException e) {
            logger.warn("lookupClusters():  " + e, e);
            return Collections.EMPTY_LIST;
        }

        return children != null ? children : Collections.EMPTY_LIST;
    }

    public List<String> lookupServices(String clusterId) {
        /** get node data from zk **/
        if (!getPathScheme().isValidToken(clusterId)) {
            throw new IllegalArgumentException("Invalid path token:  pathToken='" + clusterId + "'");
        }
        String path = getPathScheme().getAbsolutePath(PathType.PRESENCE, clusterId);
        List<String> children = Collections.EMPTY_LIST;
        try {
            Stat stat = new Stat();
            children = getZkClient().getChildren(path, true, stat);

            // getPathCache().put(path, stat, null, children);

        } catch (KeeperException e) {
            if (e.code() == Code.NONODE) {
                logger.warn("lookupServices():  " + e + ":  node does not exist:  path={}", path);
            } else {
                logger.warn("lookupServices():  " + e, e);
            }
            return Collections.EMPTY_LIST;
        } catch (InterruptedException e) {
            logger.warn("lookupServices():  " + e, e);
            return Collections.EMPTY_LIST;
        }

        return children;
    }

    public void observe(String clusterId, String serviceId, PresenceObserver<ServiceInfo> observer) {
        // ServiceInfo result = lookupServiceInfo(clusterId, serviceId, observer, nodeAttributeSerializer, true);
        String servicePath = getPathScheme().joinTokens(clusterId, serviceId);
        String path = getPathScheme().getAbsolutePath(PathType.PRESENCE, servicePath);

        observer.setClusterId(clusterId);
        observer.setServiceId(serviceId);

        getObserverManager().put(path, observer);
    }

    public void observe(String clusterId, String serviceId, String nodeId, PresenceObserver<NodeInfo> observer) {
        // NodeInfo result = lookupNodeInfo(clusterId, serviceId, nodeId, observer, nodeAttributeSerializer, true);
        String nodePath = getPathScheme().joinTokens(clusterId, serviceId, nodeId);
        String path = getPathScheme().getAbsolutePath(PathType.PRESENCE, nodePath);

        observer.setClusterId(clusterId);
        observer.setServiceId(serviceId);
        observer.setNodeId(nodeId);

        getObserverManager().put(path, observer);
    }

    public ServiceInfo waitUntilAvailable(String clusterId, String serviceId, long timeoutMillis) {

        ServiceInfo result = lookupServiceInfo(clusterId, serviceId);

        if (result == null || result.getNodeIdList().size() < 1) {
            String servicePath = getPathScheme().joinTokens(clusterId, serviceId);
            String path = getPathScheme().getAbsolutePath(PathType.PRESENCE, servicePath);

            PresenceObserver<ServiceInfo> notifyObserver = getNotifyObserver(clusterId, serviceId);
            getObserverManager().put(path, notifyObserver);

            synchronized (notifyObserver) {
                long waitStartTimestamp = System.currentTimeMillis();
                while ((result == null || result.getNodeIdList().size() < 1)
                        && (System.currentTimeMillis() - waitStartTimestamp < timeoutMillis || timeoutMillis < 0)) {

                    logger.info("Waiting until service is available:  path={}", path);
                    try {
                        if (timeoutMillis < 0) {
                            notifyObserver.wait();
                        } else {
                            notifyObserver.wait(timeoutMillis);
                        }
                    } catch (InterruptedException e) {
                        logger.warn("Interrupted while waiting for ServiceInfo:  " + e, e);
                    } // try

                    result = lookupServiceInfo(clusterId, serviceId);
                }// while
            }// synchronized

        } // if

        return result;
    }

    <T> PresenceObserver<T> getNotifyObserver(String clusterId, String serviceId) {
        return getNotifyObserver(clusterId, serviceId, null);
    }

    <T> PresenceObserver<T> getNotifyObserver(String clusterId, String serviceId, String nodeId) {
        String servicePath = null;
        if (nodeId == null) {
            servicePath = getPathScheme().joinTokens(clusterId, serviceId);
        } else {
            servicePath = getPathScheme().joinTokens(clusterId, serviceId, nodeId);
        }

        final String path = getPathScheme().getAbsolutePath(PathType.PRESENCE, servicePath);

        // observer for wait/notify
        PresenceObserver<T> observer = notifyObserverMap.get(path);
        if (observer == null) {
            PresenceObserver<T> newObserver = new PresenceObserver<T>() {
                @Override
                public void updated(T info) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("Notifying all waiters [{}]:  path={}", this.hashCode(), path);
                    }
                    synchronized (this) {
                        this.notifyAll();
                    }
                }
            };

            observer = notifyObserverMap.putIfAbsent(path, newObserver);
            if (observer == null) {
                observer = newObserver;
            }
        }

        // decorate observer with presence metadata
        observer.setClusterId(clusterId);
        observer.setServiceId(serviceId);
        observer.setNodeId(nodeId);

        return observer;
    }

    public ServiceInfo lookupServiceInfo(String clusterId, String serviceId) {
        return lookupServiceInfo(clusterId, serviceId, null, nodeAttributeSerializer);
    }

    public ServiceInfo lookupServiceInfo(String clusterId, String serviceId, PresenceObserver<ServiceInfo> observer) {
        return lookupServiceInfo(clusterId, serviceId, observer, nodeAttributeSerializer);
    }

    ServiceInfo lookupServiceInfo(String clusterId, String serviceId, PresenceObserver<ServiceInfo> observer,
            DataSerializer<Map<String, String>> nodeAttributeSerializer) {
        /** get node data from zk **/
        String servicePath = getPathScheme().joinTokens(clusterId, serviceId);
        String path = getPathScheme().getAbsolutePath(PathType.PRESENCE, servicePath);

        boolean error = false;

        List<String> children = null;
        try {

            // get from ZK
            Stat stat = new Stat();
            children = getZkClient().getChildren(path, false, stat);

        } catch (KeeperException e) {
            if (e.code() == Code.NONODE) {
                // set up watch on that node
                try {
                    getZkClient().exists(path, true);
                } catch (Exception e1) {
                    logger.error("lookupServiceInfo():  error trying to watch node:  " + e1 + ":  path=" + path, e1);
                }

                logger.debug(
                        "lookupServiceInfo():  error trying to fetch service info:  {}:  node does not exist:  path={}",
                        e.getMessage(), path);
            } else {
                logger.error("lookupServiceInfo():  error trying to fetch service info:  " + e, e);
            }
            error = true;
        } catch (Exception e) {
            logger.warn("lookupServiceInfo():  error trying to fetch service info:  " + e, e);
            error = true;
        }

        /** build service info **/
        ServiceInfo result = null;
        if (!error) {
            result = new ServiceInfo(clusterId, serviceId, children);
        }

        /** add observer if given **/
        if (observer != null) {
            this.observe(clusterId, serviceId, observer);
        }

        return result;

    }

    public NodeInfo waitUntilAvailable(String clusterId, String serviceId, String nodeId, long timeoutMillis) {

        NodeInfo result = lookupNodeInfo(clusterId, serviceId, nodeId);
        if (result == null) {
            String nodePath = getPathScheme().joinTokens(clusterId, serviceId, nodeId);
            String path = getPathScheme().getAbsolutePath(PathType.PRESENCE, nodePath);

            PresenceObserver<NodeInfo> notifyObserver = getNotifyObserver(clusterId, serviceId, nodeId);
            getObserverManager().put(path, notifyObserver);

            logger.info("Waiting until node is available:  path={}", path);

            synchronized (notifyObserver) {
                long waitStartTimestamp = System.currentTimeMillis();
                while (result == null
                        && (System.currentTimeMillis() - waitStartTimestamp < timeoutMillis || timeoutMillis < 0)) {
                    try {
                        if (timeoutMillis < 0) {
                            notifyObserver.wait();
                        } else {
                            notifyObserver.wait(timeoutMillis);
                        }
                    } catch (InterruptedException e) {
                        logger.warn("Interrupted while waiting for NodeInfo:  " + e, e);
                    }
                    result = lookupNodeInfo(clusterId, serviceId, nodeId);
                }
            }

        }

        return result;
    }

    public NodeInfo lookupNodeInfo(String clusterId, String serviceId, String nodeId) {
        return lookupNodeInfo(clusterId, serviceId, nodeId, null, nodeAttributeSerializer);
    }

    public NodeInfo lookupNodeInfo(String clusterId, String serviceId, String nodeId,
            PresenceObserver<NodeInfo> observer) {
        return lookupNodeInfo(clusterId, serviceId, nodeId, observer, nodeAttributeSerializer);
    }

    NodeInfo lookupNodeInfo(String clusterId, String serviceId, String nodeId, PresenceObserver<NodeInfo> observer,
            DataSerializer<Map<String, String>> nodeAttributeSerializer) {
        /** get node data from zk **/
        String nodePath = getPathScheme().joinTokens(clusterId, serviceId, nodeId);
        String path = getPathScheme().getAbsolutePath(PathType.PRESENCE, nodePath);

        /** add observer if passed in **/
        if (observer != null) {
            getObserverManager().put(path, observer);
        }

        /** fetch data **/
        boolean error = false;
        byte[] bytes = null;
        try {
            // populate from ZK
            Stat stat = new Stat();
            bytes = getZkClient().getData(path, false, stat);

        } catch (KeeperException e) {
            if (e.code() == Code.NONODE) {
                // set up watch on that node
                try {
                    getZkClient().exists(path, true);
                } catch (Exception e1) {
                    logger.error("lookupNodeInfo():  error trying to watch node:  " + e1 + ":  path=" + path, e1);
                }

                logger.debug("lookupNodeInfo():  error trying to fetch node info:  {}:  node does not exist:  path={}",
                        e.getMessage(), path);
            } else {
                logger.error("lookupNodeInfo():  error trying to fetch node info:  " + e, e);
            }
            error = true;
        } catch (Exception e) {
            logger.warn("lookupNodeInfo():  error trying to fetch node info:  " + e, e);
            error = true;
        }

        /** build node info **/
        NodeInfo result = null;
        if (!error) {
            try {
                result = new NodeInfo(clusterId, serviceId, nodeId,
                        bytes != null ? nodeAttributeSerializer.deserialize(bytes) : Collections.EMPTY_MAP);
            } catch (Throwable e) {
                throw new IllegalStateException(
                        "lookup():  error trying to fetch node info:  path=" + path + ":  " + e, e);
            }
        }

        return result;
    }

    /**
     * 
     * @param clusterId
     * @param serviceId
     * @param visible
     */
    public void announce(String clusterId, String serviceId, boolean visible) {
        announce(clusterId, serviceId, getPathScheme().toPathToken(getContext().getCanonicalId()), visible, null,
                getContext().getDefaultZkAclList());
    }

    /**
     * 
     * @param clusterId
     * @param serviceId
     * @param visible
     * @param attributeMap
     */
    public void announce(String clusterId, String serviceId, boolean visible, Map<String, String> attributeMap) {
        announce(clusterId, serviceId, getPathScheme().toPathToken(getContext().getCanonicalId()), visible,
                attributeMap, getContext().getDefaultZkAclList());
    }

    public void announce(String clusterId, String serviceId, String nodeId, boolean visible) {
        announce(clusterId, serviceId, nodeId, visible, null, getContext().getDefaultZkAclList());
    }

    public void announce(String clusterId, String serviceId, String nodeId, boolean visible,
            Map<String, String> attributeMap) {
        announce(clusterId, serviceId, nodeId, visible, attributeMap, getContext().getDefaultZkAclList());
    }

    // /**
    // *
    // * @param clusterId
    // * @param serviceId
    // * @param visible
    // * @param aclList
    // */
    // public void announce(String clusterId, String serviceId, boolean visible, List<ACL> aclList) {
    // announce(clusterId, serviceId, getPathScheme().toPathToken(getContext().getCanonicalId()), visible, null, null,
    // aclList);
    // }

    // /**
    // *
    // * @param clusterId
    // * @param serviceId
    // * @param visible
    // * @param attributeMap
    // * @param aclList
    // */
    // void announce(String clusterId, String serviceId, boolean visible, Map<String, String> attributeMap,
    // List<ACL> aclList) {
    // announce(clusterId, serviceId, getPathScheme().toPathToken(getContext().getCanonicalId()), visible,
    // attributeMap, null, aclList);
    // }

    /**
     * This method only has to be called once per service node and/or when node data changes. Announcements happen
     * asynchronously.
     * 
     * @param clusterId
     * @param serviceId
     * @param nodeId
     * @param visible
     *            whether or not service node will be initially visible
     * @param attributeMap
     * @param nodeAttributeSerializer
     */
    void announce(String clusterId, String serviceId, String nodeId, boolean visible, Map<String, String> attributeMap,
            List<ACL> aclList) {
        // defaults
        if (nodeAttributeSerializer == null) {
            nodeAttributeSerializer = this.getNodeAttributeSerializer();
        }

        // get announcement using path to node
        String nodePath = getPathScheme().joinTokens(clusterId, serviceId, nodeId);
        Announcement announcement = this.getAnnouncement(nodePath, aclList);
        announcement.setNodeAttributeSerializer(nodeAttributeSerializer);

        // update announcement if node data is different
        NodeInfo nodeInfo = new NodeInfo(clusterId, serviceId, nodeId, attributeMap);
        if (!nodeInfo.equals(announcement.getNodeInfo())) {
            announcement.setNodeInfo(nodeInfo);
            announcement.setLastUpdated(-1);
        }

        // mark as visible based on flag
        announcement.setHidden(!visible);

        // submit for async update immediately
        String path = getPathScheme().getAbsolutePath(PathType.PRESENCE, nodePath);
        doUpdateAnnouncementAsync(path, announcement);
    }

    public void hide(String clusterId, String serviceId) {
        hide(clusterId, serviceId, getPathScheme().toPathToken(getContext().getCanonicalId()));
    }

    public void show(String clusterId, String serviceId) {
        show(clusterId, serviceId, getPathScheme().toPathToken(getContext().getCanonicalId()));
    }

    void hide(String clusterId, String serviceId, String nodeId) {
        String nodePath = getPathScheme().joinTokens(clusterId, serviceId, nodeId);
        Announcement announcement = this.getAnnouncement(nodePath, null);
        if (announcement != null && !announcement.isHidden()) {
            announcement.setHidden(true);
            announcement.setLastUpdated(-1);
        }

        // submit for async update immediately
        String path = getPathScheme().getAbsolutePath(PathType.PRESENCE, nodePath);
        doUpdateAnnouncementAsync(path, announcement);
    }

    /**
     * Used to flag that a service node is dead, the presence node should be removed, and should not be checked again.
     * 
     * Used internally to remove connected clients once ping(s) fail.
     * 
     * @param clusterId
     * @param serviceId
     * @param nodeId
     */
    public void dead(String clusterId, String serviceId, String nodeId) {
        String nodePath = getPathScheme().joinTokens(clusterId, serviceId, nodeId);
        String path = getPathScheme().getAbsolutePath(PathType.PRESENCE, nodePath);
        try {
            getZkClient().delete(path, -1);
            announcementMap.remove(nodePath);
        } catch (KeeperException e) {
            if (e.code() == Code.NONODE) {
                logger.debug("Node does not exist:  path={}", path);
            } else {
                logger.warn("Error trying to remove node:  " + e + ":  path=" + path, e);
            }
        } catch (InterruptedException e) {
            logger.warn("hide():  error trying to remove node:  " + e, e);
        }

    }

    void show(String clusterId, String serviceId, String nodeId) {
        String nodePath = getPathScheme().joinTokens(clusterId, serviceId, nodeId);
        Announcement announcement = this.getAnnouncement(nodePath, null);
        if (announcement != null && announcement.isHidden()) {
            announcement.setHidden(false);
            announcement.setLastUpdated(-1);
        }

        // submit for async update immediately
        String path = getPathScheme().getAbsolutePath(PathType.PRESENCE, nodePath);
        doUpdateAnnouncementAsync(path, announcement);
    }

    /**
     * 
     * @param nodePath
     * @param aclList
     *            if null, will not create new Announcement if one does not currently exist
     * @return
     */
    Announcement getAnnouncement(String nodePath, List<ACL> aclList) {
        // create announcement if necessary
        Announcement announcement = announcementMap.get(nodePath);
        if (announcement == null && aclList != null) {
            Announcement newAnnouncement = new Announcement();
            newAnnouncement.setAclList(aclList);

            announcement = announcementMap.putIfAbsent(nodePath, newAnnouncement);
            if (announcement == null) {
                announcement = newAnnouncement;
            }
        }
        return announcement;
    }

    @Override
    public ResponseMessage handleMessage(RequestMessage requestMessage) {
        try {
            if (logger.isTraceEnabled()) {
                logger.trace("Received message:  request='{}:{}'", requestMessage.getTargetService(),
                        requestMessage.getBody());
            }

            /** preprocess request **/
            String requestResource = (String) requestMessage.getBody();
            String resource = requestResource;

            // strip beginning and ending slashes "/"
            if (resource.startsWith("/")) {
                resource = resource.substring(1);
            }
            if (resource.endsWith("/")) {
                resource = resource.substring(0, resource.length() - 1);
            }

            /** get response **/
            ResponseMessage responseMessage = null;

            // if base path, just return available clusters
            if (resource.length() == 0) {
                // list available clusters
                List<String> clusterList = this.lookupClusters();
                responseMessage = new SimpleResponseMessage();
                responseMessage.setBody(clusterList);

            } else {
                String[] tokens = getPathScheme().tokenizePath(resource);
                // logger.debug("tokens.length={}", tokens.length);

                if (tokens.length == 1) {
                    // list available services
                    List<String> serviceList = this.lookupServices(resource);

                    responseMessage = new SimpleResponseMessage();
                    responseMessage.setBody(serviceList);
                    if (serviceList == null) {
                        responseMessage.setComment("Not found:  " + requestResource);
                    }

                } else if (tokens.length == 2) {
                    // list available nodes for a given service
                    ServiceInfo serviceInfo = this.lookupServiceInfo(tokens[0], tokens[1]);

                    responseMessage = new SimpleResponseMessage();
                    responseMessage.setBody(serviceInfo);
                    if (serviceInfo == null) {
                        responseMessage.setComment("Not found:  " + requestResource);
                    }

                } else if (tokens.length == 3) {
                    // list available nodes for a given service
                    NodeInfo nodeInfo = this.lookupNodeInfo(tokens[0], tokens[1], tokens[2]);

                    responseMessage = new SimpleResponseMessage();
                    responseMessage.setBody(nodeInfo);
                    if (nodeInfo == null) {
                        responseMessage.setComment("Not found:  " + requestResource);
                    }

                }
            }

            responseMessage.setId(requestMessage.getId());

            return responseMessage;

        } catch (Exception e) {
            logger.error("" + e, e);
            return SimpleResponseMessage.DEFAULT_ERROR_RESPONSE;
        }

    }

    public DataSerializer<Map<String, String>> getNodeAttributeSerializer() {
        return nodeAttributeSerializer;
    }

    public void setNodeAttributeSerializer(DataSerializer<Map<String, String>> nodeAttributeSerializer) {
        this.nodeAttributeSerializer = nodeAttributeSerializer;
    }

    public long getHeartbeatIntervalMillis() {
        return this.heartbeatIntervalMillis;
    }

    public void setHeartbeatIntervalMillis(int heartbeatIntervalMillis) {
        if (heartbeatIntervalMillis < 1000) {
            throw new IllegalArgumentException("heartbeatIntervalMillis is too short:  heartbeatIntervalMillis="
                    + heartbeatIntervalMillis);
        }
        this.heartbeatIntervalMillis = heartbeatIntervalMillis;
    }

    public int getZombieCheckIntervalMillis() {
        return zombieCheckIntervalMillis;
    }

    public void setZombieCheckIntervalMillis(int zombieCheckIntervalMillis) {
        if (zombieCheckIntervalMillis < 1000) {
            throw new IllegalArgumentException("zombieCheckIntervalMillis is too short:  zombieCheckIntervalMillis="
                    + zombieCheckIntervalMillis);
        }
        this.zombieCheckIntervalMillis = zombieCheckIntervalMillis;
    }

    void doUpdateAnnouncementAsync(final String path, final Announcement announcement) {
        this.executorService.submit(new Runnable() {
            @Override
            public void run() {
                doUpdateAnnouncement(path, announcement);
            }
        });
    }

    void doUpdateAnnouncement(String path, Announcement announcement) {
        if (announcement.isHidden()) {
            doHide(path, announcement);
        } else {
            doShow(path, announcement);
        }
    }

    void doHide(String path, Announcement announcement) {
        long currentTimestamp = System.currentTimeMillis();
        try {

            // delete presence path
            getZkClient().delete(path, -1);

            // set last updated with some randomizer to spread out
            // requests
            announcement.setLastUpdated(currentTimestamp + (int) ((Math.random() * heartbeatIntervalMillis / 2)));
        } catch (KeeperException e) {
            if (e.code() == Code.NONODE) {
                logger.debug("Node does not exist:  path={}", path);
            } else {
                logger.warn("Error trying to remove node:  " + e + ":  path=" + path, e);
            }
        } catch (InterruptedException e) {
            logger.warn("hide():  error trying to remove node:  " + e, e);
        }
    }

    void doShow(String path, Announcement announcement) {
        long currentTimestamp = System.currentTimeMillis();
        try {
            Map<String, String> attributeMap = announcement.getNodeInfo().getAttributeMap();
            byte[] leafData = null;
            if (attributeMap.size() > 0) {
                leafData = announcement.getNodeAttributeSerializer().serialize(attributeMap);
            }
            String pathUpdated = zkClientUtil.updatePath(getZkClient(), getPathScheme(), path, leafData,
                    announcement.getAclList(), CreateMode.EPHEMERAL, -1);

            // set last updated with some randomizer to spread out
            // requests
            announcement.setLastUpdated(currentTimestamp + (int) ((Math.random() * heartbeatIntervalMillis / 2)));

            logger.debug("Announced:  path={}", pathUpdated);
        } catch (Exception e) {
            logger.error("Error while announcing:  " + e + ":  path=" + path, e);
        }
    }

    public class AdminRunnable implements Runnable {
        @Override
        public void run() {

            /** perform revealing and hiding announcements regularly **/
            logger.debug("Processing announcements:  announcementMap.size={}", announcementMap.size());
            Set<String> nodePathSet = announcementMap.keySet();
            long currentTimestamp = System.currentTimeMillis();
            for (String nodePath : nodePathSet) {
                Announcement announcement = announcementMap.get(nodePath);

                // skip if announcement no longer exists or less than heartbeat
                // interval
                if (announcement == null || currentTimestamp - announcement.getLastUpdated() < heartbeatIntervalMillis) {
                    continue;
                }

                // announce or hide node
                String path = getPathScheme().getAbsolutePath(PathType.PRESENCE, nodePath);
                doUpdateAnnouncement(path, announcement);
            }// for

            /** do zombie node check per interval **/
            if (System.currentTimeMillis() - lastZombieCheckTimestamp > zombieCheckIntervalMillis) {
                // get exclusive leader lock to perform maintenance duties
                CoordinationService coordinationService = getContext().getService("coord");
                DistributedLock adminLock = coordinationService.getLock("presence", "zombie-checker",
                        getDefaultZkAclList());
                logger.info("Checking for zombie nodes...");
                if (adminLock.tryLock()) {
                    try {
                        List<String> clusterIdList = getZkClient().getChildren(
                                getPathScheme().getAbsolutePath(PathType.PRESENCE), false);
                        for (String clusterId : clusterIdList) {
                            List<String> serviceIdList = lookupServices(clusterId);
                            for (String serviceId : serviceIdList) {
                                // service path
                                String servicePath = getPathScheme().getAbsolutePath(PathType.PRESENCE, clusterId,
                                        serviceId);

                                // get children of each service
                                List<String> serviceChildren = getZkClient().getChildren(servicePath, false);

                                // check stat and make sure mtime of each child is
                                // within 4x heartbeatIntervalMillis; if not, delete
                                for (String child : serviceChildren) {
                                    String serviceChildPath = getPathScheme().joinPaths(servicePath, child);
                                    logger.info("Checking for service zombie child nodes:  path={}", servicePath);
                                    Stat stat = getZkClient().exists(serviceChildPath, false);
                                    long timeDiff = System.currentTimeMillis() - stat.getMtime();
                                    if (timeDiff > heartbeatIntervalMillis * 4) {
                                        logger.warn(
                                                "Found zombie node:  deleting:  path={}; millisSinceLastHeartbeat={}",
                                                serviceChildPath, timeDiff);
                                        getZkClient().delete(serviceChildPath, -1);
                                    }
                                }// for

                            }// for
                        }// for

                        // update last check timestamp
                        lastZombieCheckTimestamp = System.currentTimeMillis();
                    } catch (Exception e) {
                        logger.warn("Error while checking for and removing zombie nodes:  " + e, e);
                    } finally {
                        adminLock.unlock();
                        adminLock.destroy();
                    }
                }// if tryLock

            }// if
        } // run()
    }// AdminRunnable

}
