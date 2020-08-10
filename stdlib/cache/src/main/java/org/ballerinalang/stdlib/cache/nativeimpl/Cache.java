/*
 * Copyright (c) 2020, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.ballerinalang.stdlib.cache.nativeimpl;

import org.ballerinalang.jvm.values.ArrayValueImpl;
import org.ballerinalang.jvm.values.MapValue;
import org.ballerinalang.jvm.values.ObjectValue;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Ballerina function to cache with java.util.concurrent.ConcurrentHashMap.
 *
 * @since 2.0.0
 */
public class Cache {

    public static final String CACHE_MAP = "CACHE_MAP";

    public static void externInit(ObjectValue cache, int capacity) {
        ConcurrentHashMap<String, MapValue<String, Object>> map = new ConcurrentHashMap<>(capacity);
        cache.addNativeData(CACHE_MAP, map);
    }

    public static void externPut(ObjectValue cache, String key, MapValue<String, Object> value) {
        ConcurrentHashMap<String, MapValue<String, Object>> map =
                (ConcurrentHashMap<String, MapValue<String, Object>>) cache.getNativeData(CACHE_MAP);
        map.put(key, value);
    }

    public static MapValue<String, Object> externGet(ObjectValue cache, String key) {
        ConcurrentHashMap<String, MapValue<String, Object>> map =
                (ConcurrentHashMap<String, MapValue<String, Object>>) cache.getNativeData(CACHE_MAP);
        return map.get(key);
    }

    public static void externRemove(ObjectValue cache, String key) {
        ConcurrentHashMap<String, MapValue<String, Object>> map =
                (ConcurrentHashMap<String, MapValue<String, Object>>) cache.getNativeData(CACHE_MAP);
        map.remove(key);
    }

    public static void externRemoveAll(ObjectValue cache) {
        ConcurrentHashMap<String, MapValue<String, Object>> map =
                (ConcurrentHashMap<String, MapValue<String, Object>>) cache.getNativeData(CACHE_MAP);
        map.clear();
    }

    public static boolean externHasKey(ObjectValue cache, String key) {
        ConcurrentHashMap<String, MapValue<String, Object>> map =
                (ConcurrentHashMap<String, MapValue<String, Object>>) cache.getNativeData(CACHE_MAP);
        return map.containsKey(key);
    }

    public static ArrayValueImpl externKeys(ObjectValue cache) {
        ConcurrentHashMap<String, MapValue<String, Object>> map =
                (ConcurrentHashMap<String, MapValue<String, Object>>) cache.getNativeData(CACHE_MAP);
        return new ArrayValueImpl(map.keySet().toArray(new String[0]));
    }

    public static int externSize(ObjectValue cache) {
        ConcurrentHashMap<String, MapValue<String, Object>> map =
                (ConcurrentHashMap<String, MapValue<String, Object>>) cache.getNativeData(CACHE_MAP);
        return map.size();
    }
}
