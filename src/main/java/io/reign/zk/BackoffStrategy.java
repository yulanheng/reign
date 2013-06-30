package io.reign.zk;

/**
 * Defines how to wait: for retries, etc.
 * 
 * @author ypai
 * 
 */
public interface BackoffStrategy {

    /**
     * @return true if there is another iteration
     * @return
     */
    public boolean hasNext();

    /**
     * Increment interval.
     * 
     * @return the interval after incrementing; or null if there are no more iterations.
     */
    public Integer next();

    /**
     * 
     * @return the current interval value; or null if there are no more iterations.
     */
    public Integer get();
}