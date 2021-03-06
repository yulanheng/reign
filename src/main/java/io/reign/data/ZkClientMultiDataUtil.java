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

package io.reign.data;

import io.reign.PathScheme;
import io.reign.ZkClient;
import io.reign.zk.PathCache;
import io.reign.zk.SimplePathCacheEntry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author ypai
 * 
 */
public class ZkClientMultiDataUtil extends ZkClientDataUtil {

    private static final Logger logger = LoggerFactory.getLogger(ZkClientMultiDataUtil.class);

    /**
     * Used by getDataFromPathCache() to distinguish btw. expired cache data and cache data that is current but empty
     */
    public static final byte[] EMPTY_BYTE_ARRAY = new byte[0];

    ZkClientMultiDataUtil(ZkClient zkClient, PathScheme pathScheme, TranscodingScheme transcodingScheme) {
        super(zkClient, pathScheme, transcodingScheme);

    }

    <V> String writeData(String absoluteBasePath, String index, V value, List<ACL> aclList) {
        try {

            byte[] bytes = null;
            if (value != null) {
                bytes = transcodingScheme.toBytes(value);
            } else {
                logger.warn("Attempting to write null data:  doing nothing:  absoluteBasePath={}; index={}; value={}",
                        new Object[] { absoluteBasePath, index, value });
                return null;
            }

            // write data to ZK
            AtomicReference<Stat> statRef = new AtomicReference<Stat>();
            String absoluteDataValuePath = updatePath(zkClient, pathScheme,
                    pathScheme.joinPaths(absoluteBasePath, index), bytes, aclList, CreateMode.PERSISTENT, -1, statRef);

            // logger.debug("writeData():  absoluteBasePath={}; index={}; absoluteDataValuePath={}; value={}",
            // new Object[] { absoluteBasePath, index, absoluteDataValuePath, value });

            // get stat if it was not returned from updatePath
            Stat stat = statRef.get();
            if (stat == null) {
                stat = zkClient.exists(absoluteDataValuePath, false);
            }

            // update stat with recent update time in case we have a stale read
            stat.setMtime(System.currentTimeMillis());

            // // update path cache after successful write
            // pathCache.put(absoluteDataValuePath, stat, bytes, null);
            //
            // SimplePathCacheEntry pce = pathCache.get(absoluteDataValuePath);
            // logger.debug("writeData():  absoluteDataValuePath={}; pathCacheEntry={}", absoluteDataValuePath,
            // transcodingScheme.fromBytes(pce.getData(), value.getClass()));

            return absoluteDataValuePath;

        } catch (KeeperException e) {
            logger.error("" + e, e);
            return null;
        } catch (Exception e) {
            logger.error("" + e, e);
            return null;
        }
    }

    /**
     * 
     * @param absoluteBasePath
     * @param index
     * @param thresholdMillis
     *            remove data older than given threshold
     * @return
     */
    String deleteData(String absoluteBasePath, String index, int ttlMillis) {
        try {
            String absoluteDataPath = pathScheme.joinPaths(absoluteBasePath, index);

            // try to get from path cache, use stat modified timestamp instead of cache entry modified timestamp because
            // we are more interested in when the data last changed
            byte[] bytes = null;
            if (ttlMillis > 0) {
                bytes = null;
                // if (usePathCache) {
                // bytes = getDataFromPathCache(absoluteDataPath, ttlMillis);
                // }
                if (bytes == null) {
                    // read data from ZK
                    Stat stat = new Stat();
                    bytes = zkClient.getData(absoluteDataPath, true, stat);

                    // see if item is expired
                    if (isExpired(stat.getMtime(), ttlMillis)) {
                        bytes = null;
                    }
                }
            }

            // if bytes == null, meaning that data for this index is expired or that we are removing regardless of data
            // age, delete data node
            String deletedPath = null;
            if (bytes == null) {
                // remove node entry in path cache
                // pathCache.remove(absoluteDataPath);

                // delete from zk
                zkClient.delete(absoluteDataPath, -1);
                deletedPath = absoluteDataPath;

                // update parent children in path cache if parent node exists in cache
                String absoluteParentPath = pathScheme.getParentPath(absoluteDataPath);
                // SimplePathCacheEntry pathCacheEntry = pathCache.get(absoluteParentPath);
                // if (pathCacheEntry != null) {
                // List<String> currentChildList = pathCacheEntry.getChildList();
                // List<String> newChildList = new ArrayList<String>(currentChildList.size());
                // for (String child : currentChildList) {
                // if (!child.equals(index)) {
                // newChildList.add(child);
                // }
                // }
                //
                // // if parent child list is null, that means there are no longer any entries under this key, so
                // // remove
                // if (newChildList == null || newChildList.size() == 0) {
                // // remove from cache
                // pathCache.remove(absoluteParentPath);
                //
                // // remove from ZK
                // deleteKey(absoluteParentPath);
                //
                // } else {
                // // still entries, so update cache entry
                // pathCache.put(absoluteParentPath, pathCacheEntry.getStat(), pathCacheEntry.getData(),
                // newChildList);
                // }
                // } else {
                Stat stat = new Stat();
                List<String> childList = Collections.EMPTY_LIST;
                try {
                    childList = zkClient.getChildren(absoluteParentPath, true, stat);

                    // // update in path cache
                    // pathCache.put(absoluteParentPath, stat, null, childList);

                    // if no children, remove
                    if (childList == null || childList.size() == 0) {
                        deleteKey(absoluteParentPath);
                    }

                } catch (KeeperException e) {
                    logger.info("" + e, e);
                }
                // }
            }

            return deletedPath;

        } catch (KeeperException e) {
            if (e.code() != KeeperException.Code.NONODE) {
                logger.error("" + e, e);
            }
            return null;
        } catch (Exception e) {
            logger.error("" + e, e);
            return null;
        }
    }

    void deleteKey(String absoluteKeyPath) {
        try {
            // pathCache.remove(absoluteKeyPath);
            zkClient.delete(absoluteKeyPath, -1);
        } catch (Exception e1) {
            logger.error("Trouble deleting key:  key=" + absoluteKeyPath, e1);
        }
    }

    List<String> deleteAllData(String absoluteBasePath, int ttlMillis) {
        try {
            // get children
            List<String> childList = null;
            // if (usePathCache) {
            // childList = getChildListFromPathCache(absoluteBasePath, ttlMillis);
            // }
            if (childList == null) {
                try {
                    Stat stat = new Stat();
                    childList = zkClient.getChildren(absoluteBasePath, true, stat);

                    // update in path cache
                    // pathCache.put(absoluteBasePath, stat, null, childList);
                } catch (KeeperException e) {
                    // may hit this exception if node does not exist
                    logger.info("" + e, e);
                }
            }

            // iterate through children and build up list
            if (childList != null && childList.size() > 0) {
                List<String> resultList = new ArrayList<String>(childList.size());
                for (String child : childList) {
                    String deletedPath = deleteData(absoluteBasePath, child, ttlMillis);

                    // see if we deleted
                    if (deletedPath != null) {
                        resultList.add(deletedPath);
                    }
                }// for

                // delete key
                deleteKey(absoluteBasePath);

                return resultList;
            } // if

            // return list
            return Collections.EMPTY_LIST;

        } catch (Exception e) {
            logger.error("" + e, e);
            return Collections.EMPTY_LIST;
        }
    }

    <V> V readData(String absoluteBasePath, String index, int ttlMillis, Class<V> typeClass) {
        try {
            String absoluteDataPath = pathScheme.joinPaths(absoluteBasePath, index);

            // try to get from path cache, use stat modified timestamp instead of cache entry modified timestamp because
            // we are more interested in when the data last changed
            byte[] bytes = null;
            // if (usePathCache) {
            // bytes = getDataFromPathCache(absoluteDataPath, ttlMillis);
            // }
            if (bytes == null) {
                // read data from ZK
                Stat stat = new Stat();
                bytes = zkClient.getData(absoluteDataPath, true, stat);

                // see if item is expired
                if (isExpired(stat.getMtime(), ttlMillis)) {
                    return null;
                }

                // update in path cache
                // pathCache.put(absoluteDataPath, stat, bytes, Collections.EMPTY_LIST);
            }

            // deserialize
            V data = null;
            if (bytes != null && bytes != EMPTY_BYTE_ARRAY) {
                data = transcodingScheme.fromBytes(bytes, typeClass);
            }

            // logger.debug("readData():  absoluteBasePath={}; index={}; value={}", new Object[] { absoluteBasePath,
            // index, data });

            return data;

        } catch (KeeperException e) {
            if (e.code() != KeeperException.Code.NONODE) {
                logger.error("" + e, e);
            }
            return null;
        } catch (Exception e) {
            logger.error("" + e, e);
            return null;
        }
    }

    // /**
    // *
    // * @param absoluteBasePath
    // * @param ttlMillis
    // * @return List of children; or null if data in cache is expired or missing
    // */
    // List<String> getChildListFromPathCache(String absoluteBasePath, int ttlMillis) {
    //
    // List<String> childList = null;
    // SimplePathCacheEntry pathCacheEntry = pathCache.get(absoluteBasePath, ttlMillis);
    // if (pathCacheEntry != null) {
    // childList = pathCacheEntry.getChildList();
    // }
    //
    // return childList;
    // }

    // /**
    // * Different from regular path cache access method in that we are more interested in how old the data is, not the
    // * last time the cache entry has been updated.
    // *
    // * @param absoluteDataPath
    // * @param ttlMillis
    // * @return byte[] or null if data in cache is expired or missing
    // */
    // byte[] getDataFromPathCache(String absoluteDataPath, int ttlMillis) {
    //
    // byte[] bytes = null;
    // SimplePathCacheEntry pathCacheEntry = pathCache.get(absoluteDataPath);
    // if (pathCacheEntry != null && !isExpired(pathCacheEntry.getStat().getMtime(), ttlMillis)) {
    // bytes = pathCacheEntry.getData();
    //
    // // valid value, but we need a way in this use case to distinguish btw. expired/missing value in pathCache
    // // (return null) and
    // // valid value in pathCache but empty
    // if (bytes == null) {
    // bytes = EMPTY_BYTE_ARRAY;
    // }
    // }
    // return bytes;
    // }

    <V> List<V> readAllData(String absoluteBasePath, int ttlMillis, Class<V> typeClass) {

        try {
            // get children
            List<String> childList = null;
            // if (usePathCache) {
            // childList = getChildListFromPathCache(absoluteBasePath, ttlMillis);
            // }
            if (childList == null) {
                Stat stat = new Stat();
                childList = zkClient.getChildren(absoluteBasePath, false, stat);

                // update in path cache
                // pathCache.put(absoluteBasePath, stat, null, childList);
            }

            // iterate through children and build up list
            if (childList.size() > 0) {
                List<V> resultList = new ArrayList<V>(childList.size());
                for (String child : childList) {
                    V value = readData(absoluteBasePath, child, ttlMillis, typeClass);

                    // logger.debug("readAllData():  absoluteBasePath={}; index={}; value={}", new Object[] {
                    // absoluteBasePath, child, value });

                    if (value != null) {
                        resultList.add(value);
                    }
                }// for

                return resultList;
            } // if

            // return list
            return Collections.EMPTY_LIST;

        } catch (KeeperException e) {
            logger.error("" + e, e);
            return Collections.EMPTY_LIST;
        } catch (Exception e) {
            logger.error("" + e, e);
            return Collections.EMPTY_LIST;
        }
    }

}
