/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.dromara.soul.plugin.divide.cache;

import com.google.common.collect.Maps;
import lombok.extern.slf4j.Slf4j;
import org.dromara.soul.common.concurrent.SoulThreadFactory;
import org.dromara.soul.common.dto.SelectorData;
import org.dromara.soul.common.dto.convert.DivideUpstream;
import org.dromara.soul.common.utils.GsonUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * this is divide  http url upstream.
 *
 * @author xiaoyu
 */
@Slf4j
public class UpstreamCacheManager {
    
    private static final UpstreamCacheManager INSTANCE = new UpstreamCacheManager();
    
    private static final BlockingQueue<SelectorData> BLOCKING_QUEUE = new LinkedBlockingQueue<>(1024);
    
    private static final Map<String, List<DivideUpstream>> UPSTREAM_MAP = Maps.newConcurrentMap();
    
    /**
     * Gets instance.
     *
     * @return the instance
     */
    public static UpstreamCacheManager getInstance() {
        return INSTANCE;
    }
    
    /**
     * Find upstream list by selector id list.
     *
     * @param selectorId the selector id
     * @return the list
     */
    public List<DivideUpstream> findUpstreamListBySelectorId(final String selectorId) {
        return UPSTREAM_MAP.get(selectorId);
    }
    
    /**
     * Remove by key.
     *
     * @param key the key
     */
    public void removeByKey(final String key) {
        UPSTREAM_MAP.remove(key);
    }
    
    /**
     * Init.
     */
    public void init() {
        new ThreadPoolExecutor(1, 1,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                SoulThreadFactory.create("save-upstream-task", false))
                .execute(new Worker());
    }
    
    /**
     * Submit.
     *
     * @param selectorData the selector data
     */
    public void submit(final SelectorData selectorData) {
        try {
            BLOCKING_QUEUE.put(selectorData);
        } catch (InterruptedException e) {
            log.error(e.getMessage());
        }
    }
    
    /**
     * Clear.
     */
    public void clear() {
        UPSTREAM_MAP.clear();
    }
    
    
    /**
     * Execute.
     *
     * @param selectorData the selector data
     */
    public void execute(final SelectorData selectorData) {
        final List<DivideUpstream> upstreamList =
                GsonUtils.getInstance().fromList(selectorData.getHandle(), DivideUpstream.class);
        if (null != upstreamList && upstreamList.size() > 0) {
            UPSTREAM_MAP.put(selectorData.getId(), upstreamList);
        } else {
            UPSTREAM_MAP.remove(selectorData.getId());
        }
    }
    
    /**
     * The type Worker.
     */
    class Worker implements Runnable {
        
        @Override
        public void run() {
            runTask();
        }
        
        private void runTask() {
            for (; ;) {
                try {
                    final SelectorData selectorData = BLOCKING_QUEUE.take();
                    Optional.of(selectorData).ifPresent(UpstreamCacheManager.this::execute);
                } catch (InterruptedException e) {
                    log.warn("BLOCKING_QUEUE take operation was interrupted.", e);
                }
            }
        }
    }
    
}
