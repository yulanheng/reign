package org.kompany.overlord.coord;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;

import org.apache.zookeeper.data.ACL;
import org.kompany.overlord.PathContext;
import org.kompany.overlord.util.TimeUnitUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 
 * @author ypai
 * 
 */
public class ZkReentrantLock implements DistributedReentrantLock {

    private static final Logger logger = LoggerFactory.getLogger(ZkReentrantLock.class);

    private final ZkReservationManager zkReservationManager;
    private final String ownerId;
    private final PathContext pathContext;
    private final String clusterId;
    private final String lockName;
    private final ReservationType reservationType;
    private final List<ACL> aclList;

    private volatile String acquiredLockPath;

    private final AtomicInteger holdCount = new AtomicInteger(0);

    public ZkReentrantLock(ZkReservationManager zkReservationManager, String ownerId, PathContext pathContext,
            String clusterId, String lockName, ReservationType reservationType, List<ACL> aclList) {
        super();
        this.zkReservationManager = zkReservationManager;
        this.ownerId = ownerId;
        this.pathContext = pathContext;
        this.reservationType = reservationType;
        this.clusterId = clusterId;
        this.lockName = lockName;
        this.aclList = aclList;
    }

    @Override
    public void destroy() {
        logger.info("destroy() called");
        zkReservationManager.destroyLock(clusterId, lockName, reservationType, this);
    }

    @Override
    protected void finalize() throws Throwable {
        super.finalize();
        destroy();
    }

    @Override
    public String getLockId() {
        return this.acquiredLockPath;
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.util.concurrent.locks.Lock#lock()
     */
    @Override
    public void lock() {
        if (acquiredLockPath == null) {
            try {
                acquiredLockPath = zkReservationManager.acquire(ownerId, pathContext, clusterId, lockName,
                        reservationType, aclList, -1, false);
                holdCount.incrementAndGet();
            } catch (InterruptedException e) {
                logger.warn("Interrupted in lock():  should not happen:  " + e, e);
            }
        } else {
            holdCount.incrementAndGet();
        }
    }

    /*
     * 
     * (non-Javadoc)
     * 
     * @see java.util.concurrent.locks.Lock#lockInterruptibly()
     */
    @Override
    public void lockInterruptibly() throws InterruptedException {
        if (acquiredLockPath == null) {
            acquiredLockPath = zkReservationManager.acquire(ownerId, pathContext, clusterId, lockName, reservationType,
                    aclList, -1, true);
        }

        holdCount.incrementAndGet();
    }

    /**
     * 
     * @return number of times this lock has been acquired by current process
     */
    @Override
    public int getHoldCount() {
        return this.holdCount.get();
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.util.concurrent.locks.Lock#newCondition()
     */
    @Override
    public Condition newCondition() {
        throw new UnsupportedOperationException();
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.util.concurrent.locks.Lock#tryLock()
     */
    @Override
    public boolean tryLock() {
        try {
            if (acquiredLockPath == null) {
                acquiredLockPath = zkReservationManager.acquire(ownerId, pathContext, clusterId, lockName,
                        reservationType, aclList, 0, false);
            }

            holdCount.incrementAndGet();
        } catch (InterruptedException e) {
            logger.warn("Interrupted in lock():  should not happen:  " + e, e);
        }
        return acquiredLockPath != null;
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.util.concurrent.locks.Lock#tryLock(long,
     * java.util.concurrent.TimeUnit)
     */
    @Override
    public boolean tryLock(long wait, TimeUnit timeUnit) throws InterruptedException {
        if (acquiredLockPath == null) {
            // convert wait to millis
            long timeWaitMillis = TimeUnitUtils.toMillis(wait, timeUnit);

            // attempt to acquire lock
            acquiredLockPath = zkReservationManager.acquire(ownerId, pathContext, clusterId, lockName, reservationType,
                    aclList, timeWaitMillis, true);

            holdCount.incrementAndGet();
        } else {
            holdCount.incrementAndGet();
        }

        return acquiredLockPath != null;
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.util.concurrent.locks.Lock#unlock()
     */
    @Override
    public void unlock() {
        int updatedHoldCount = holdCount.decrementAndGet();
        if (updatedHoldCount < 1) {
            zkReservationManager.relinquish(acquiredLockPath);

            // try to set back to 0 by adding so as not to miss any other
            // increments/decrements that are in flight
            holdCount.addAndGet(0 - updatedHoldCount);

            acquiredLockPath = null;
        }
    }

}
