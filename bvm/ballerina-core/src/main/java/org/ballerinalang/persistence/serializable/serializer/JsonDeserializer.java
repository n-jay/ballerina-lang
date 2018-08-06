/*
 * Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * you may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.ballerinalang.persistence.serializable.serializer;

import org.ballerinalang.model.values.BBoolean;
import org.ballerinalang.model.values.BFloat;
import org.ballerinalang.model.values.BInteger;
import org.ballerinalang.model.values.BMap;
import org.ballerinalang.model.values.BNewArray;
import org.ballerinalang.model.values.BRefType;
import org.ballerinalang.model.values.BString;
import org.ballerinalang.model.values.BValue;
import org.ballerinalang.persistence.serializable.SerializableState;
import org.ballerinalang.persistence.serializable.serializer.type.BStringSerializationProvider;
import org.ballerinalang.persistence.serializable.serializer.type.ListSerializationProvider;
import org.ballerinalang.persistence.serializable.serializer.type.MapSerializationProvider;
import org.ballerinalang.persistence.serializable.serializer.type.NumericSerializationProviders;
import org.ballerinalang.persistence.serializable.serializer.type.SerializableBMapSerializationProvider;
import org.ballerinalang.persistence.serializable.serializer.type.SerializableBRefArraySerializationProvider;
import org.ballerinalang.persistence.serializable.serializer.type.SerializableContextSerializationProvider;
import org.ballerinalang.persistence.serializable.serializer.type.SerializableStateSerializationProvider;
import org.ballerinalang.persistence.serializable.serializer.type.SerializableWorkerDataSerializationProvider;
import org.ballerinalang.persistence.serializable.serializer.type.SerializedKeySerializationProvider;
import org.ballerinalang.persistence.serializable.serializer.type.WorkerStateSerializationProvider;
import org.ballerinalang.util.exceptions.BallerinaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.jvm.hotspot.utilities.Assert;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reconstruct Java object tree from JSON input.
 */
public class JsonDeserializer {
    private final TypeInstanceProvider typeInstanceProvider;
    private final BValueProvider bValueProvider;
    private static final Logger logger = LoggerFactory.getLogger(JsonDeserializer.class);
    private final HashMap<String, Object> identityMap = new HashMap<>();
    private BRefType<?> treeHead;

    public JsonDeserializer(BRefType<?> objTree) {
        treeHead = objTree;
        typeInstanceProvider = TypeInstanceProvider.getInstance();
        bValueProvider = BValueProvider.getInstance();
        registerTypeSerializationProviders(typeInstanceProvider);
    }

    private void registerTypeSerializationProviders(TypeInstanceProvider registry) {
        registry.addTypeProvider(new SerializableStateSerializationProvider());
        registry.addTypeProvider(new SerializableWorkerDataSerializationProvider());
        registry.addTypeProvider(new SerializableContextSerializationProvider());
        registry.addTypeProvider(new MapSerializationProvider());
        registry.addTypeProvider(new ListSerializationProvider());
        registry.addTypeProvider(new WorkerStateSerializationProvider());
        registry.addTypeProvider(new SerializableBMapSerializationProvider());
        registry.addTypeProvider(new BStringSerializationProvider());
        registry.addTypeProvider(new SerializedKeySerializationProvider());
        registry.addTypeProvider(new SerializableBRefArraySerializationProvider());
        NumericSerializationProviders.register(registry);
    }

    public SerializableState deserialize(Class<?> destinationType) {
        return (SerializableState) deserialize(treeHead, destinationType);
    }

    @SuppressWarnings("unchecked")
    private Object deserialize(BValue jValue, Class<?> targetType) {
        if (jValue instanceof BMap) {
            BMap<String, BValue> jBMap = (BMap<String, BValue>) jValue;
            return deserialize(jBMap, targetType);
        }
        if (jValue instanceof BNewArray) {
            return deserializeArray((BNewArray) jValue, targetType);
        }
        if (jValue instanceof BString) {
            return jValue.stringValue();
        }
        if (jValue instanceof BInteger) {
            return ((BInteger) jValue).intValue();
        }
        if (jValue instanceof BFloat) {
            return ((BFloat) jValue).floatValue();
        }
        if (jValue instanceof BBoolean) {
            return ((BBoolean) jValue).booleanValue();
        }
        if (jValue == null) {
            return null;
        }
        throw new BallerinaException(
                String.format("Unknown BValue type to deserialize: %s", jValue.getClass().getSimpleName()));
    }

    private Object deserialize(BMap<String, BValue> jBMap, Class<?> targetType) {
        Object object = findExisting(jBMap);
        if (object != null) {
            return object;
        }
        // try BValueProvider
        String typeName = getTargetTypeName(targetType, jBMap);
        if (typeName != null) {
            SerializationBValueProvider provider = this.bValueProvider.find(typeName);
            if (provider != null) {
                return provider.toObject(jBMap);
            }
        }

        Object emptyInstance = createInstance(jBMap, targetType);
        addIdentity(jBMap, emptyInstance);
        object = deserializeObject(jBMap, emptyInstance, targetType);
        // check to make sure deserializeObject does not return a new object other than populating 'emptyInstance'
        // we need it to return the same object so that we know object identity for handling #existing# objects.
        if (object != emptyInstance) {
            throw new BallerinaException("Internal error: deserializeObject should not create it's own objects.");
        }
        return object;
    }

    private String getTargetTypeName(Class<?> targetType, BMap<String, BValue> jBMap) {
        String typeName = null;
        if (targetType != null) {
            typeName = targetType.getName();
        } else {
            BValue typeVal = jBMap.get(JsonSerializerConst.TYPE_TAG);
            if (typeVal != null){
                typeName = typeVal.stringValue();
            }
        }
        return typeName;
    }

    private void addIdentity(BMap<String, BValue> jBMap, Object object) {
        BValue hash = jBMap.get(JsonSerializerConst.HASH_TAG);
        if (hash != null) {
            identityMap.put(hash.stringValue(), object);
        }
    }

    /**
     * Try to find existing object if possible.
     * @param jBMap
     * @return
     */
    private Object findExisting(BMap<String, BValue> jBMap) {
        BValue existingKey = jBMap.get(JsonSerializerConst.EXISTING_TAG);
        if (existingKey != null) {
            return identityMap.get(existingKey.stringValue());
        }
        return null;
    }

    private Object deserializeArray(BNewArray jArray, Class<?> targetType) {
        int arrayLen = (int) jArray.size();
        Object[] array = new Object[arrayLen];
        for (int i = 0; i < arrayLen; i++) {
            BValue bValue = jArray.getBValue(i);
            Class itemType = Object.class;
            if (bValue instanceof BMap) {
                String typeName = ((BMap) bValue).get("type").stringValue();
                itemType = findClass(typeName);
            }
            array[i] = deserialize(jArray.getBValue(i), itemType);
        }
        return array;
    }

    /**
     * Create a empty java object instance for target type.
     *
     * @param jsonNode
     * @return
     */
    private Object createInstance(BMap<String, BValue> jsonNode, Class<?> target) {
        if (Enum.class.isAssignableFrom(target)) {
            return createEnumInstance(jsonNode);
        }
        BValue typeNode = jsonNode.get("type");
        if (typeNode != null) {
            String type = typeNode.stringValue();
            return getObjectOf(type);
        }
        if (target != null) {
            return getObjectOf(target);
        }
        return null;
    }

    private Object createEnumInstance(BMap jsonNode) {
        String enumName = jsonNode.get("payload").stringValue();
        String[] frag = enumName.split("\\.");
        String type = frag[0];
        Class enumClass = typeInstanceProvider.findTypeProvider(type).getTypeClass();
        String enumConst = frag[1];
        return Enum.valueOf(enumClass, enumConst);
    }

    private Object getObjectOf(Class<?> clazz) {
        TypeSerializationProvider typeProvider = typeInstanceProvider.findTypeProvider(clazz.getSimpleName());
        if (typeProvider != null) {
            return clazz.cast(typeProvider.newInstance());
        }
        return null;
    }

    private Object getObjectOf(String type) {
        TypeSerializationProvider typeProvider = typeInstanceProvider.findTypeProvider(type);
        if (typeProvider != null) {
            return typeProvider.newInstance();
        }
        return null;
    }

    private Object deserializeObject(BMap<String, BValue> jsonNode, Object object, Class<?> targetType) {
        BValue payload = jsonNode.get("payload");
        if (jsonNode.get("type") != null) {
            String objType = jsonNode.get("type").stringValue();
            if ("map".equals(objType.toLowerCase())) {
                return deserializeMap((BMap<String, BValue>) payload, (Map) object, targetType);
            } else if ("list".equals(objType.toLowerCase())) {
                return deserializeList(payload, object, targetType);
            } else if ("enum".equals(objType.toLowerCase())) {
                return object;
            }
        }
        // if this is not a wrapped object
        if (payload == null) {
            payload = jsonNode;
        }
        if (jsonNode instanceof BMap) {
            BMap<String, BValue> jMap = (BMap<String, BValue>) payload;
            setFields(object, jMap, object.getClass());
        }
        return object;
    }

    private void setFields(Object target, BMap<String, BValue> jMap, Class<?> targetClass) {
        for (Field field : targetClass.getDeclaredFields()) {
            BValue value = jMap.get(field.getName());
            Object obj = deserialize(value, field.getType());
            primeFinalFieldForAssignment(field);
            try {
                Object newValue = cast(obj, field.getType());
                if (newValue != null && field.getType().isArray()) {
                    Object array;
                    if (newValue instanceof List) {
                        array = createArray(field, ((List) newValue).toArray());
                    } else {
                        array = createArray(field, (Object[]) newValue);
                    }
                    field.set(target, array);
                } else {
                    field.set(target, newValue);
                }
            } catch (IllegalAccessException e) {
                logger.warn(String.format(
                        "Cannot set field: %s, this probably is a final field \n" +
                                "initialized to a compile-time constant", field.getName()));
            } catch (IllegalArgumentException e) {
                logger.error(e.getMessage());
                throw new BallerinaException(e);
            }

            // recursively set fields in super types.
            if (targetClass != Object.class) {
                setFields(target, jMap, targetClass.getSuperclass());
            }
        }
    }

    private void primeFinalFieldForAssignment(Field field) {
        try {
            field.setAccessible(true);
            Field modifiers = Field.class.getDeclaredField("modifiers");
            modifiers.setAccessible(true);
            modifiers.setInt(field, field.getModifiers() & ~Modifier.FINAL);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            new BallerinaException();
        }
    }

    /**
     * Convert to desired type if special conditions are matched.
     *
     * @param obj
     * @param targetType
     * @return
     */
    private Object cast(Object obj, Class targetType) {
        // JsonParser always treat integer numbers as longs, if target field is int then cast to int.
        if ((targetType == Integer.class && obj.getClass() == Long.class)
                || (targetType.getName().equals("int") && obj.getClass() == Long.class)) {
            return ((Long) obj).intValue();
        }

        // JsonParser always treat float numbers as doubles, if target field is float then cast to float.
        if ((targetType == Float.class && obj.getClass() == Double.class)
                || (targetType.getName().equals("float") && obj.getClass() == Double.class)) {
            return ((Double) obj).floatValue();
        }

        return obj;
    }

    private Object deserializeList(BValue payload, Object targetList, Class<?> targetType) {
        if (targetList instanceof List) {
            List list = (List) targetList;
            Object[] array = (Object[]) deserializeArray((BNewArray) payload, targetType);
            for (int i = 0; i < array.length; i++) {
                list.add(array[i]);
            }
        }
        return targetList;
    }

    private Object deserializeMap(BMap<String, BValue> payload, Map map, Class<?> targetType) {
        for (String key : payload.keys()) {
            BValue value = payload.get(key);

            Class<?> fieldType;
            if (value instanceof BMap) {
                @SuppressWarnings("unchecked")
                BMap<String, BValue> item = (BMap<String, BValue>) value;
                String typeName = item.get(JsonSerializerConst.TYPE_TAG).stringValue();
                fieldType = findClass(typeName);
            } else if (value instanceof BBoolean) {
                fieldType = BBoolean.class;
            } else {
                throw new BallerinaException("Uncompatible type in JSON Map");
            }
            map.put(key, deserialize(value, fieldType));
        }
        return map;
    }

    private Class<?> findClass(String typeName) {
        SerializationBValueProvider provider = this.bValueProvider.find(typeName);
        if (provider != null) {
            return provider.getType();
        }
        TypeSerializationProvider typeProvider = this.typeInstanceProvider.findTypeProvider(typeName);
        return typeProvider.getTypeClass();
    }
}
