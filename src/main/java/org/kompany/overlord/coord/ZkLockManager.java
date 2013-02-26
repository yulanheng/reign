package org.kompany.overlord.coord;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;
import org.kompany.overlord.PathContext;
import org.kompany.overlord.PathScheme;
import org.kompany.overlord.PathType;
import org.kompany.overlord.ZkClient;
import org.kompany.overlord.util.PathCache;
import org.kompany.overlord.util.PathCache.PathCacheEntry;
import org.kompany.overlord.util.ZkUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Contains basic functionality for creating Lock/Semaphore functionality using
 * ZooKeeper.
 * 
 * @author ypai
 * 
 */
class ZkLockManager {

    private static final Logger logger = LoggerFactory.getLogger(ZkLockManager.class);

    private final Comparator<String> lockReservationComparator = new LockReservationComparator("_");
    private final ZkClient zkClient;
    private final PathScheme pathScheme;
    private final ZkUtil zkUtil = new ZkUtil();
    private volatile boolean shutdown = false;

    private final PathCache pathCache;

    ZkLockManager(ZkClient zkClient, PathScheme pathScheme, PathCache pathCache) {
        super();
        this.zkClient = zkClient;
        this.pathScheme = pathScheme;
        this.pathCache = pathCache;
    }

    public void shutdown() {
        this.shutdown = true;
    }

    /**
     * 
     * @param pathContext
     * @param entityName
     * @param reservationType
     * @param useCache
     * @return sorted list of lock/semaphore requesters, first to last
     */
    public List<String> getSortedReservationList(PathContext pathContext, String entityName,
            ReservationType reservationType, boolean useCache) {
        List<String> list = getReservationList(pathContext, entityName, reservationType, useCache);
        Collections.sort(list, lockReservationComparator);
        return list;
    }

    /**
     * 
     * @param pathContext
     * @param entityName
     * @param reservationType
     * @param useCache
     * @return unsorted list of lock/semaphore requesters
     */
    public List<String> getReservationList(PathContext pathContext, String entityName, ReservationType reservationType,
            boolean useCache) {
        String entityPath = pathScheme.getAbsolutePath(pathContext, PathType.COORD,
                reservationType.getSubCategoryPathToken() + "/" + entityName);
        try {
            List<String> lockReservationList = null;

            PathCacheEntry pathCacheEntry = pathCache.get(entityPath);
            if (useCache && pathCacheEntry != null) {
                // found in cache
                lockReservationList = pathCacheEntry.getChildren();
            } else {
                lockReservationList = zkClient.getChildren(entityPath, true);
            }

            return lockReservationList;
        } catch (KeeperException e) {
            throw new IllegalStateException("Error trying to get reservation list:  " + e + ": entityPath="
                    + entityPath, e);
        } catch (Exception e) {
            throw new IllegalStateException("Error trying to get reservation list:  " + e + ": entityPath="
                    + entityPath, e);
        }
    }

    /**
     * 
     * @param ownerId
     * @param pathContext
     * @param entityName
     * @param reservationType
     * @param totalAvailable
     *            the total number of locks available; -1 for no limit
     * @param aclList
     * @param waitTimeoutMs
     *            total time to wait in millis for lock acquisition; -1 to wait
     *            indefinitely
     * @param aclList
     * @param interruptible
     *            true to throw InterruptedException(s); false to eat them
     * @return path of acquired lock node (this needs to be kept in order to
     *         unlock!)
     */
    public String acquire(String ownerId, PathContext pathContext, String entityName, ReservationType reservationType,
            List<ACL> aclList, long waitTimeoutMs, boolean interruptible) throws InterruptedException {
        try {
            long startTimestamp = System.currentTimeMillis();
            // String lockReservationPath = null;
            // LockWatcher lockWatcher = null;

            // path to lock (parent node of all reservations)
            String lockPath = pathScheme.getAbsolutePath(pathContext, PathType.COORD,
                    reservationType.getSubCategoryPathToken() + "/" + entityName);

            // owner data in JSON
            String lockReservationData = "{\"ownerId\":\"" + ownerId + "\"}";

            // path to lock reservation node (to "get in line" for lock)
            String lockReservationPrefix = pathScheme.getAbsolutePath(pathContext, PathType.COORD,
                    reservationType.getSubCategoryPathToken() + "/" + entityName + "/" + reservationType + "_");

            // create lock reservation sequential node
            String lockReservationPath = zkUtil.updatePath(zkClient, pathScheme, lockReservationPrefix,
                    lockReservationData.getBytes("UTF-8"), aclList, CreateMode.EPHEMERAL_SEQUENTIAL, -1);

            // path token (last part of path)
            String lockReservation = lockReservationPath.substring(lockReservationPath.lastIndexOf('/') + 1);

            // create lock watcher for wait/notify
            if (logger.isDebugEnabled()) {
                logger.debug("Attempting to acquire:  ownerId={}; lockType={}; lockReservationPath={}", new Object[] {
                        ownerId, reservationType, lockReservationPath });
            }

            String acquiredLockPath = null;
            ZkLockWatcher lockReservationWatcher = null;
            do {
                try {
                    /** see if we can acquire right away **/
                    // get lock reservation list without watch
                    List<String> lockReservationList = zkClient.getChildren(lockPath, false);

                    // sort child list
                    Collections.sort(lockReservationList, lockReservationComparator);

                    // loop through children and see if we have the lock
                    String reservationAheadPath = null;
                    boolean exclusiveReservationEncountered = false;
                    for (int i = 0; i < lockReservationList.size(); i++) {
                        String currentReservation = lockReservationList.get(i);

                        // see if we have the lock
                        if (lockReservation.equals(currentReservation)) {
                            if (i == 0
                                    || (reservationType != ReservationType.EXCLUSIVE && !exclusiveReservationEncountered)) {
                                acquiredLockPath = lockReservationPath;
                                break;
                            }

                            // set the one ahead of this reservation so we can
                            // watch if we do not acquire
                            reservationAheadPath = lockPath + "/" + lockReservationList.get(i - 1);
                            break;
                        }

                        // see if we have encountered an exclusive lock yet
                        if (!exclusiveReservationEncountered) {
                            exclusiveReservationEncountered = currentReservation.startsWith(ReservationType.EXCLUSIVE
                                    .toString());
                        }

                    }

                    /** see if we acquired lock **/
                    if (acquiredLockPath == null) {
                        // wait to acquire if not yet acquired
                        logger.info(
                                "Waiting to acquire:  ownerId={}; lockType={}; lockReservationPath={}; watchPath={}",
                                new Object[] { ownerId, reservationType, lockReservationPath, reservationAheadPath });
                        if (lockReservationWatcher == null) {
                            lockReservationWatcher = new ZkLockWatcher(lockPath, lockReservationPath);
                        }

                        // set lock on the reservation ahead of this one
                        zkClient.exists(reservationAheadPath, lockReservationWatcher);

                        // wait for notification
                        lockReservationWatcher.waitForEvent(waitTimeoutMs);
                    } else {
                        logger.info("Acquired:  ownerId={}; lockType={}; acquiredLockPath={}", new Object[] { ownerId,
                                reservationType, acquiredLockPath });
                        break;
                    }
                } catch (InterruptedException e) {
                    if (interruptible) {
                        throw e;
                    } else {
                        logger.info(
                                "Ignoring attempted interrupt while waiting for lock acquisition:  ownerId={}; lockType={}; lockPath={}",
                                new Object[] { ownerId, reservationType, lockPath });
                    }
                }

            } while (!this.shutdown && acquiredLockPath == null
                    && (waitTimeoutMs == -1 || startTimestamp + waitTimeoutMs > System.currentTimeMillis()));

            if (acquiredLockPath == null) {
                logger.info("Could not acquire:  ownerId={}; lockType={}; lockPath={}", new Object[] { ownerId,
                        reservationType, lockPath });
            }

            return acquiredLockPath;

        } catch (KeeperException e) {
            logger.error("Error trying to acquire:  " + e + ":  ownerId=" + ownerId + "; lockName=" + entityName
                    + "; lockType=" + reservationType, e);
        } catch (Exception e) {
            logger.error("Error trying to acquire:  " + e + ":  ownerId=" + ownerId + "; lockName=" + entityName
                    + "; lockType=" + reservationType, e);
        }

        return null;

    }

    public String acquireForSemaphore(String ownerId, PathContext pathContext, String entityName,
            ReservationType reservationType, int totalAvailable, List<ACL> aclList, long waitTimeoutMs,
            boolean interruptible) throws InterruptedException {
        if (reservationType != ReservationType.PERMIT) {
            throw new IllegalArgumentException("Invalid reservation type:  " + ReservationType.PERMIT);
        }

        try {
            long startTimestamp = System.currentTimeMillis();
            // String lockReservationPath = null;
            // LockWatcher lockWatcher = null;

            // path to lock (parent node of all reservations)
            String lockPath = pathScheme.getAbsolutePath(pathContext, PathType.COORD,
                    reservationType.getSubCategoryPathToken() + "/" + entityName);

            // owner data in JSON
            String lockReservationData = "{\"ownerId\":\"" + ownerId + "\"}";

            // path to lock reservation node (to "get in line" for lock)
            String lockReservationPrefix = pathScheme.getAbsolutePath(pathContext, PathType.COORD,
                    reservationType.getSubCategoryPathToken() + "/" + entityName + "/" + reservationType + "_");

            // create lock reservation sequential node
            String lockReservationPath = zkUtil.updatePath(zkClient, pathScheme, lockReservationPrefix,
                    lockReservationData.getBytes("UTF-8"), aclList, CreateMode.EPHEMERAL_SEQUENTIAL, -1);

            // path token (last part of path)
            String lockReservation = lockReservationPath.substring(lockReservationPath.lastIndexOf('/') + 1);

            // create lock watcher for wait/notify
            if (logger.isDebugEnabled()) {
                logger.debug("Attempting to acquire:  ownerId={}; lockType={}; lockReservationPath={}", new Object[] {
                        ownerId, reservationType, lockReservationPath });
            }

            String acquiredLockPath = null;
            ZkLockWatcher lockReservationWatcher = null;
            do {
                try {
                    /** see if we can acquire right away **/
                    // set lock on the reservation ahead of this one
                    Stat stat = zkClient.exists(lockPath, false);

                    // if we are only after semaphore reservations, check child
                    // count to see if we event need to count children
                    // explicitly
                    if (stat.getNumChildren() < totalAvailable) {
                        acquiredLockPath = lockReservationPath;
                        break;
                    } else {
                        // get lock reservation list with watch
                        if (lockReservationWatcher == null) {
                            lockReservationWatcher = new ZkLockWatcher(lockPath, lockReservationPath);
                        }
                        List<String> lockReservationList = zkClient.getChildren(lockPath, lockReservationWatcher);

                        // sort child list
                        Collections.sort(lockReservationList, lockReservationComparator);

                        // loop through children and see if we have the lock
                        boolean exclusiveReservationEncountered = false;
                        for (int i = 0; i < lockReservationList.size(); i++) {
                            String currentReservation = lockReservationList.get(i);

                            // see if we have the lock
                            if (lockReservation.equals(currentReservation)) {
                                boolean stillAvailable = (totalAvailable < 0 || i < totalAvailable);
                                if (stillAvailable && (i == 0 || !exclusiveReservationEncountered)) {
                                    acquiredLockPath = lockReservationPath;
                                    break;
                                }
                            }

                            exclusiveReservationEncountered = reservationType == ReservationType.EXCLUSIVE;
                        }
                    }// if

                    /** see if we acquired lock **/
                    if (acquiredLockPath == null) {
                        // wait to acquire if not yet acquired
                        logger.info(
                                "Waiting to acquire:  ownerId={}; lockType={}; lockReservationPath={}; watchPath={}",
                                new Object[] { ownerId, reservationType, lockReservationPath, lockPath });
                        // wait for notification
                        lockReservationWatcher.waitForEvent(waitTimeoutMs);
                    } else {
                        logger.info("Acquired:  ownerId={}; lockType={}; acquiredLockPath={}", new Object[] { ownerId,
                                reservationType, acquiredLockPath });
                        break;
                    }
                } catch (InterruptedException e) {
                    if (interruptible) {
                        throw e;
                    } else {
                        logger.info(
                                "Ignoring attempted interrupt while waiting for lock acquisition:  ownerId={}; lockType={}; lockPath={}",
                                new Object[] { ownerId, reservationType, lockPath });
                    }
                }

            } while (!this.shutdown && acquiredLockPath == null
                    && (waitTimeoutMs == -1 || startTimestamp + waitTimeoutMs > System.currentTimeMillis()));

            if (acquiredLockPath == null) {
                logger.info("Could not acquire:  ownerId={}; lockType={}; lockPath={}", new Object[] { ownerId,
                        reservationType, lockPath });
            }

            return acquiredLockPath;

        } catch (KeeperException e) {
            logger.error("Error trying to acquire:  " + e + ":  ownerId=" + ownerId + "; lockName=" + entityName
                    + "; lockType=" + reservationType, e);
        } catch (Exception e) {
            logger.error("Error trying to acquire:  " + e + ":  ownerId=" + ownerId + "; lockName=" + entityName
                    + "; lockType=" + reservationType, e);
        }

        return null;

    }

    public boolean relinquish(String reservationPath) {
        if (reservationPath == null) {
            logger.debug("Trying to delete ZK reservation node with invalid path:  path={}", reservationPath);
            return false;
        }// if

        try {
            logger.debug("Relinquishing:  path={}", reservationPath);

            zkClient.delete(reservationPath, -1);

            logger.info("Relinquished:  path={}", reservationPath);
            return true;
        } catch (KeeperException e) {
            if (e.code() == KeeperException.Code.NONODE) {
                // already deleted, so just log
                if (logger.isDebugEnabled()) {
                    logger.debug("Already deleted ZK reservation node:  " + e + "; path=" + reservationPath, e);
                }

            } else {
                logger.error("Error while deleting ZK reservation node:  " + e + "; path=" + reservationPath, e);
                throw new IllegalStateException("Error while deleting ZK reservation node:  " + e + "; path="
                        + reservationPath, e);
            }
        } catch (Exception e) {
            logger.error("Error while deleting ZK reservation node:  " + e + "; path=" + reservationPath, e);
            throw new IllegalStateException("Error while deleting ZK reservation node:  " + e + "; path="
                    + reservationPath, e);
        }// try

        return false;
    }// unlock

}