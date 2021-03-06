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

package io.reign.coord;

import io.reign.PathScheme;
import io.reign.ZkClient;
import io.reign.util.ZkClientUtil;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Contains basic functionality for creating Lock/Semaphore functionality using ZooKeeper.
 * 
 * @author ypai
 * 
 */
class ZkReservationManager {

	private static final Logger logger = LoggerFactory.getLogger(ZkReservationManager.class);

	private final Comparator<String> lockReservationComparator = new LockReservationComparator("_");
	private final ZkClient zkClient;
	private final PathScheme pathScheme;
	private final ZkClientUtil zkUtil = new ZkClientUtil();
	private volatile boolean shutdown = false;

	// private final PathCache pathCache;

	private final CoordinationServiceCache coordinationServiceCache;

	ZkReservationManager(ZkClient zkClient, PathScheme pathScheme, CoordinationServiceCache coordinationServiceCache) {
		super();
		this.zkClient = zkClient;
		this.pathScheme = pathScheme;
		// this.pathCache = pathCache;
		this.coordinationServiceCache = coordinationServiceCache;
	}

	public void shutdown() {
		this.shutdown = true;
	}

	public void destroySemaphore(String entityPath, DistributedSemaphore semaphore, PermitPoolSize permitPoolSize) {
		semaphore.release(Integer.MAX_VALUE);
		coordinationServiceCache.removeSemaphore(entityPath, semaphore);
		coordinationServiceCache.removePermitPoolSize(entityPath, permitPoolSize);
	}

	public void destroyLock(String entityPath, ReservationType reservationType, DistributedLock lock) {
		lock.unlock();
		coordinationServiceCache.removeLock(entityPath, reservationType, lock);
	}

	public List<String> getSortedReservationList(String entityPath) {
		List<String> list = getReservationList(entityPath);
		Collections.sort(list, lockReservationComparator);
		return list;
	}

	public List<String> getReservationList(String entityPath) {
		try {
			List<String> lockReservationList = null;

			lockReservationList = zkClient.getChildren(entityPath, true);

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
	 * @param entityPath
	 * @param reservationType
	 * @param aclList
	 * @param waitTimeoutMs
	 * @param interruptible
	 * @return
	 * @throws InterruptedException
	 */
	public String acquire(String ownerId, String entityPath, ReservationType reservationType, List<ACL> aclList,
	        long waitTimeoutMs, boolean interruptible) throws InterruptedException {
		if (waitTimeoutMs < -1) {
			throw new IllegalArgumentException("waitTimeoutMs must be -1 (no limit) or >= 0!");
		}

		try {
			long startTimestamp = System.currentTimeMillis();

			// owner data in JSON
			String lockReservationData = "{\"ownerId\":\"" + ownerId + "\"}";

			// path to lock reservation node (to "get in line" for lock)
			String lockReservationPrefix = CoordServicePathUtil.getAbsolutePathReservationPrefix(pathScheme,
			        entityPath, reservationType);

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

			String acquiredPath = null;
			ZkLockWatcher lockReservationWatcher = null;
			do {
				try {
					/** attempt to acquire lock **/
					// get lock reservation list without watch
					List<String> lockReservationList = zkClient.getChildren(entityPath, true);

					// sort child list
					Collections.sort(lockReservationList, lockReservationComparator);

					logger.debug(
					        "Checking acquisition status:  entityPath={}; lockReservation={}; lockReservationList={}",
					        entityPath, lockReservation, lockReservationList);

					// loop through children and see if we have the lock
					String reservationAheadPath = null;
					boolean exclusiveReservationEncountered = false;
					for (int i = 0; i < lockReservationList.size(); i++) {
						String currentReservation = lockReservationList.get(i);

						// see if we have the lock
						if (lockReservation.equals(currentReservation)) {
							// see if we are able to acquire lock immediately
							if (i == 0 || (!reservationType.isExclusive() && !exclusiveReservationEncountered)) {
								acquiredPath = lockReservationPath;
								break;
							}

							// if reservationAheadPath is null and set the one
							// ahead of this reservation so we can
							// watch if we do not acquire
							// Check if it's null in case we plan to watch an
							// exclusive lock reservation
							if (reservationAheadPath == null) {
								reservationAheadPath = pathScheme.joinPaths(entityPath, lockReservationList.get(i - 1));
							}
							break;
						}

						// Check if it's an exclusive lock reservation: set the
						// exclusiveReservationEncountered flag and
						// update the reservationAheadPath
						if (ReservationType.isExclusive(currentReservation)) {
							exclusiveReservationEncountered = true;
							reservationAheadPath = pathScheme.joinPaths(entityPath, currentReservation);
						}
					}

					/** see if we acquired lock **/
					if (acquiredPath == null) {
						// wait to acquire if not yet acquired
						if (lockReservationWatcher == null) {
							lockReservationWatcher = new ZkLockWatcher(entityPath, lockReservationPath);
						}

						// set watch on the reservation ahead of this one
						zkClient.exists(reservationAheadPath, lockReservationWatcher);

						// wait for notification
						logger.debug(
						        "Waiting to acquire:  ownerId={}; lockType={}; reservationPath={}; watchPath={}; totalWaitMillis={}",
						        new Object[] { ownerId, reservationType, lockReservationPath, reservationAheadPath,
						                (System.currentTimeMillis() - startTimestamp) });

						// always call wait() with timeout in case we missed an
						// update that occurred before setting the watch
						lockReservationWatcher.waitForEvent(waitTimeoutMs == -1 ? 15000 : waitTimeoutMs);

					} else {
						logger.debug("Acquired:  ownerId={}; reservationType={}; acquiredPath={}", new Object[] {
						        ownerId, reservationType, acquiredPath });

						// set watch on lock node so that we are notified if it
						// is deleted outside of framework and can notify any
						// lock observers
						zkClient.exists(acquiredPath, true);

						break;
					}
				} catch (InterruptedException e) {
					if (interruptible) {
						throw e;
					} else {
						logger.info(
						        "Ignoring attempted interrupt while waiting for lock acquisition:  ownerId={}; reservationType={}; entityPath={}",
						        new Object[] { ownerId, reservationType, entityPath });
					}
				}

			} while (!this.shutdown && acquiredPath == null
			        && (waitTimeoutMs == -1 || startTimestamp + waitTimeoutMs > System.currentTimeMillis()));

			// clean up lock watcher as necessary
			if (lockReservationWatcher != null) {
				lockReservationWatcher.destroy();
			}

			// log if not acquired
			if (acquiredPath == null) {
				boolean relinquishedLockReservationPath = this.relinquish(lockReservationPath);
				if (!relinquishedLockReservationPath) {
					logger.error(
					        "Unable to relinquish the lockReservationPath; ownerId={}; relinquishLockReservationPath={}; lockReservationPath={}",
					        ownerId, relinquishedLockReservationPath, lockReservationPath);
				}
				logger.info(
				        "Could not acquire:  ownerId={}; lockType={}; lockPath={}; waitTimeoutMillis={}; relinquishedLockReservationPath={}; lockReservationPath={}",
				        new Object[] { ownerId, reservationType, entityPath, waitTimeoutMs,
				                relinquishedLockReservationPath, lockReservationPath });
			}

			return acquiredPath;

		} catch (KeeperException e) {
			logger.error("Error trying to acquire:  " + e + ":  ownerId=" + ownerId + "; entityPath=" + entityPath
			        + "; lockType=" + reservationType, e);
		} catch (Exception e) {
			logger.error("Error trying to acquire:  " + e + ":  ownerId=" + ownerId + "; entityPath=" + entityPath
			        + "; lockType=" + reservationType, e);
		}

		return null;

	}

	/**
	 * 
	 * @param ownerId
	 * @param entityPath
	 * @param reservationType
	 * @param totalAvailable
	 * @param aclList
	 * @param waitTimeoutMs
	 * @param interruptible
	 * @return
	 * @throws InterruptedException
	 */
	public String acquireForSemaphore(String ownerId, String entityPath, ReservationType reservationType,
	        int totalAvailable, List<ACL> aclList, long waitTimeoutMs, boolean interruptible)
	        throws InterruptedException {
		if (waitTimeoutMs < -1) {
			throw new IllegalArgumentException("waitTimeoutMs must be -1 (no limit) or >= 0!");
		}

		if (reservationType != ReservationType.SEMAPHORE) {
			throw new IllegalArgumentException("Invalid reservation type:  " + ReservationType.SEMAPHORE);
		}

		try {
			long startTimestamp = System.currentTimeMillis();

			// owner data in JSON
			String lockReservationData = "{\"ownerId\":\"" + ownerId + "\"}";

			// path to lock reservation node (to "get in line" for lock)
			String lockReservationPrefix = CoordServicePathUtil.getAbsolutePathReservationPrefix(pathScheme,
			        entityPath, reservationType);

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

			String acquiredPath = null;
			ZkLockWatcher lockReservationWatcher = null;
			do {
				try {
					/** see if we can acquire right away **/
					// set lock on the reservation ahead of this one
					Stat stat = zkClient.exists(entityPath, false);

					// if we are only after semaphore reservations, check child
					// count to see if we event need to count children
					// explicitly
					if (stat.getNumChildren() < totalAvailable) {
						acquiredPath = lockReservationPath;
						break;
					} else {
						// get lock reservation list with watch
						if (lockReservationWatcher == null) {
							lockReservationWatcher = new ZkLockWatcher(entityPath, lockReservationPath);
						}
						List<String> lockReservationList = zkClient.getChildren(entityPath, lockReservationWatcher);

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
									acquiredPath = lockReservationPath;
									break;
								}
							}

							exclusiveReservationEncountered = reservationType == ReservationType.LOCK_EXCLUSIVE;
						}
					}// if

					/** see if we acquired lock **/
					if (acquiredPath == null) {
						// wait to acquire if not yet acquired
						logger.debug(
						        "Waiting to acquire:  ownerId={}; lockType={}; lockReservationPath={}; watchPath={}",
						        new Object[] { ownerId, reservationType, lockReservationPath, entityPath });

						// always call wait() with timeout in case we missed an
						// update that occurred before setting the watch
						lockReservationWatcher.waitForEvent(waitTimeoutMs == -1 ? 15000 : waitTimeoutMs);

					} else {
						logger.debug("Acquired:  ownerId={}; lockType={}; acquiredLockPath={}", new Object[] { ownerId,
						        reservationType, acquiredPath });

						// set watch on lock node so that we are notified if it
						// is deleted outside of framework and can notify any
						// lock observers
						zkClient.exists(acquiredPath, true);

						break;
					}
				} catch (InterruptedException e) {
					if (interruptible) {
						throw e;
					} else {
						logger.info(
						        "Ignoring attempted interrupt while waiting for lock acquisition:  ownerId={}; lockType={}; entityPath={}",
						        new Object[] { ownerId, reservationType, entityPath });
					}
				}

			} while (!this.shutdown && acquiredPath == null
			        && (waitTimeoutMs == -1 || startTimestamp + waitTimeoutMs > System.currentTimeMillis()));

			// clean up lock watcher as necessary
			if (lockReservationWatcher != null) {
				lockReservationWatcher.destroy();
			}

			// log if not acquired
			if (acquiredPath == null) {
				boolean relinquishLockReservationPath = this.relinquish(lockReservationPath);
				if (!relinquishLockReservationPath) {
					logger.error(
					        "Unable to relinquish the lockReservationPath; ownerId={}; relinquishLockReservationPath={};",
					        ownerId, relinquishLockReservationPath);
				}
				logger.info(
				        "Could not acquire:  ownerId={}; lockType={}; lockPath={}; waitTimeoutMillis={}; relinquishLockReservationPath={};",
				        new Object[] { ownerId, reservationType, entityPath, waitTimeoutMs,
				                relinquishLockReservationPath });
			}

			return acquiredPath;

		} catch (KeeperException e) {
			logger.error("Error trying to acquire:  " + e + ":  ownerId=" + ownerId + "; entityPath=" + entityPath
			        + "; lockType=" + reservationType, e);
		} catch (Exception e) {
			logger.error("Error trying to acquire:  " + e + ":  ownerId=" + ownerId + "; entityPath=" + entityPath
			        + "; lockType=" + reservationType, e);
		}

		return null;

	}

	/**
	 * 
	 * @param reservationPath
	 * @return
	 */
	public boolean relinquish(String reservationPath) {
		if (reservationPath == null) {
			// likely already deleted
			logger.trace("Trying to delete ZK reservation node with invalid path:  path={}", reservationPath);
			return true;
		}// if

		try {
			logger.trace("Relinquishing:  path={}", reservationPath);

			zkClient.delete(reservationPath, -1);

			logger.debug("Relinquished:  path={}", reservationPath);
			return true;
		} catch (KeeperException e) {
			if (e.code() == KeeperException.Code.NONODE) {
				// already deleted, so just log
				if (logger.isDebugEnabled()) {
					logger.debug("Already deleted ZK reservation node:  " + e + "; path=" + reservationPath, e);
				}

				return true;

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

	}// unlock

}
