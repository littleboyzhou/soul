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

package org.dromara.soul.plugin.alibaba.dubbo.cache;

import com.alibaba.dubbo.config.ApplicationConfig;
import com.alibaba.dubbo.config.ReferenceConfig;
import com.alibaba.dubbo.config.RegistryConfig;
import com.alibaba.dubbo.rpc.service.GenericService;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.Weigher;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;
import org.dromara.soul.common.config.DubboRegisterConfig;
import org.dromara.soul.common.dto.MetaData;
import org.dromara.soul.common.enums.LoadBalanceEnum;
import org.dromara.soul.common.exception.SoulException;
import org.dromara.soul.common.utils.GsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;


/**
 * The type Application config cache.
 */
@SuppressWarnings("all")
public final class ApplicationConfigCache {
    
    private static final Logger LOG = LoggerFactory.getLogger(ApplicationConfigCache.class);
    
    private ApplicationConfig applicationConfig;
    
    private RegistryConfig registryConfig;
    
    private final int maxCount = 50000;
    
    private final LoadingCache<String, ReferenceConfig<GenericService>> cache = CacheBuilder.newBuilder()
            .maximumWeight(maxCount)
            .weigher((Weigher<String, ReferenceConfig<GenericService>>) (string, ReferenceConfig) -> getSize())
            .removalListener(notification -> {
                ReferenceConfig config = notification.getValue();
                if (config != null) {
                    try {
                        Class cz = config.getClass();
                        Field field = cz.getDeclaredField("ref");
                        field.setAccessible(true);
                        field.set(config, null);
                        //跟改配置之后dubbo 销毁该实例,但是未置空,如果不处理,重新初始化的时候将获取到NULL照成空指针问题.
                    } catch (NoSuchFieldException | IllegalAccessException e) {
                        LOG.error("修改ref为null", e);
                    }
                }
            })
            .build(new CacheLoader<String, ReferenceConfig<GenericService>>() {
                @Override
                public ReferenceConfig<GenericService> load(final String key) {
                    return new ReferenceConfig<>();
                }
            });
    
    private ApplicationConfigCache() {
    }
    
    private int getSize() {
        return (int) cache.size();
    }
    
    /**
     * Gets instance.
     *
     * @return the instance
     */
    public static ApplicationConfigCache getInstance() {
        return ApplicationConfigCacheInstance.INSTANCE;
    }
    
    /**
     * Init.
     *
     * @param dubboRegisterConfig the dubbo register config
     */
    public void init(final DubboRegisterConfig dubboRegisterConfig) {
        if (applicationConfig == null) {
            applicationConfig = new ApplicationConfig("soul_proxy");
        }
        if (registryConfig == null) {
            registryConfig = new RegistryConfig();
            registryConfig.setProtocol(dubboRegisterConfig.getProtocol());
            registryConfig.setId("soul_proxy");
            registryConfig.setRegister(false);
            registryConfig.setAddress(dubboRegisterConfig.getRegister());
            Optional.ofNullable(dubboRegisterConfig.getGroup()).ifPresent(registryConfig::setGroup);
        }
    }
    
    /**
     * Init ref reference config.
     *
     * @param metaData the meta data
     * @return the reference config
     */
    public ReferenceConfig<GenericService> initRef(final MetaData metaData) {
        try {
            ReferenceConfig<GenericService> referenceConfig = cache.get(metaData.getServiceName());
            if (StringUtils.isNoneBlank(referenceConfig.getInterface())) {
                return referenceConfig;
            }
        } catch (ExecutionException e) {
            LOG.error("init dubbo ref ex:{}", e.getMessage());
        }
        return build(metaData);
        
    }
    
    /**
     * Build reference config.
     *
     * @param metaData the meta data
     * @return the reference config
     */
    public ReferenceConfig<GenericService> build(final MetaData metaData) {
        ReferenceConfig<GenericService> reference = new ReferenceConfig<>();
        reference.setGeneric(true);
        reference.setApplication(applicationConfig);
        reference.setRegistry(registryConfig);
        reference.setInterface(metaData.getServiceName());
        reference.setProtocol("dubbo");
        String rpcExt = metaData.getRpcExt();
        DubboParamExtInfo dubboParamExtInfo = GsonUtils.getInstance().fromJson(rpcExt, DubboParamExtInfo.class);
        if (Objects.nonNull(dubboParamExtInfo)) {
            if (StringUtils.isNoneBlank(dubboParamExtInfo.getVersion())) {
                reference.setVersion(dubboParamExtInfo.getVersion());
            }
            if (StringUtils.isNoneBlank(dubboParamExtInfo.getGroup())) {
                reference.setGroup(dubboParamExtInfo.getGroup());
            }
            if (StringUtils.isNoneBlank(dubboParamExtInfo.getLoadbalance())) {
                final String loadBalance = dubboParamExtInfo.getLoadbalance();
                reference.setLoadbalance(buildLoadBalanceName(loadBalance));
            }
            Optional.ofNullable(dubboParamExtInfo.getTimeout()).ifPresent(reference::setTimeout);
            Optional.ofNullable(dubboParamExtInfo.getRetries()).ifPresent(reference::setRetries);
        }
        Object obj = reference.get();
        if (obj != null) {
            LOG.info("初始化引用成功{}", metaData);
            cache.put(metaData.getServiceName(), reference);
        }
        return reference;
    }
    
    private String buildLoadBalanceName(final String loadBalance) {
        if (LoadBalanceEnum.HASH.getName().equals(loadBalance) || "consistenthash".equals(loadBalance)) {
            return "consistenthash";
        } else if (LoadBalanceEnum.ROUND_ROBIN.getName().equals(loadBalance)) {
            return "roundrobin";
        } else {
            return loadBalance;
        }
    }
    
    /**
     * Get reference config.
     *
     * @param <T>         the type parameter
     * @param serviceName the service name
     * @return the reference config
     */
    public <T> ReferenceConfig<T> get(final String serviceName) {
        try {
            return (ReferenceConfig<T>) cache.get(serviceName);
        } catch (ExecutionException e) {
            throw new SoulException(e.getCause());
        }
    }
    
    /**
     * Invalidate.
     *
     * @param serviceName the service name
     */
    public void invalidate(final String serviceName) {
        cache.invalidate(serviceName);
    }
    
    /**
     * Invalidate all.
     */
    public void invalidateAll() {
        cache.invalidateAll();
    }
    
    /**
     * The type Application config cache instance.
     */
    static class ApplicationConfigCacheInstance {
        /**
         * The Instance.
         */
        static final ApplicationConfigCache INSTANCE = new ApplicationConfigCache();
    }
    
    /**
     * The type Dubbo param ext info.
     */
    @Data
    static class DubboParamExtInfo {
        
        private String group;
        
        private String version;
        
        private String loadbalance;
        
        private Integer retries;
        
        private Integer timeout;
    }
    
}
