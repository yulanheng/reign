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

import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Supplier;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;

/**
 * Tracks outstanding locks, semaphores, barriers, etc.
 * 
 * @author ypai
 * 
 */
public class CoordinationServiceCache {

	private static final Logger logger = LoggerFactory.getLogger(CoordinationServiceCache.class);

	private final Multimap<String, DistributedSemaphore> semaphoreCache = Multimaps.synchronizedListMultimap(Multimaps
	        .newListMultimap(Maps.<String, Collection<DistributedSemaphore>> newHashMap(),
	                new Supplier<List<DistributedSemaphore>>() {
		                @Override
		                public List<DistributedSemaphore> get() {
			                return new CopyOnWriteArrayList<DistributedSemaphore>();
		                }
	                }));

	private final ConcurrentMap<String, PermitPoolSize> permitPoolSizeCache = new ConcurrentHashMap<String, PermitPoolSize>(
	        8, 0.9f, 2);

	private final Multimap<String, DistributedLock> lockCache = Multimaps.synchronizedListMultimap(Multimaps
	        .newListMultimap(Maps.<String, Collection<DistributedLock>> newHashMap(),
	                new Supplier<List<DistributedLock>>() {
		                @Override
		                public List<DistributedLock> get() {
			                return new CopyOnWriteArrayList<DistributedLock>();
		                }
	                }));

	public Collection<DistributedLock> getLocks(String entityPath, ReservationType reservationType) {
		return lockCache.get(getKey(entityPath, reservationType));
	}

	public void putLock(String entityPath, ReservationType reservationType, DistributedLock lock) {
		lockCache.put(getKey(entityPath, reservationType), lock);
		logger.trace("lockCache.size()={}", lockCache.size());
	}

	public void removeLock(String entityPath, ReservationType reservationType, DistributedLock lock) {
		lockCache.remove(getKey(entityPath, reservationType), lock);
		logger.trace("lockCache.size()={}", lockCache.size());
	}

	public Collection<DistributedSemaphore> getSemaphores(String entityPath) {
		return semaphoreCache.get(entityPath);
	}

	public void putSemaphore(String entityPath, DistributedSemaphore semaphore) {
		semaphoreCache.put(entityPath, semaphore);
		logger.trace("semaphoreCache.size()={}", semaphoreCache.size());

	}

	public void removeSemaphore(String entityPath, DistributedSemaphore semaphore) {
		semaphoreCache.remove(entityPath, semaphore);
		logger.trace("semaphoreCache.size()={}", semaphoreCache.size());
	}

	public PermitPoolSize getPermitPoolSize(String entityPath) {
		return permitPoolSizeCache.get(entityPath);
	}

	public PermitPoolSize putOrReturnCachedPermitPoolSize(String entityPath, PermitPoolSize pps) {
		PermitPoolSize value = permitPoolSizeCache.putIfAbsent(entityPath, pps);
		if (value == null) {
			value = pps;
		}
		logger.trace("permitPoolSizeCache.size()={}", permitPoolSizeCache.size());
		return value;

	}

	public void removePermitPoolSize(String entityPath, PermitPoolSize pps) {
		permitPoolSizeCache.remove(entityPath);
		logger.trace("permitPoolSizeCache.size()={}", permitPoolSizeCache.size());
	}

	String getKey(String entityPath, ReservationType reservationType) {
		return entityPath + "/" + reservationType.prefix();
	}
}
