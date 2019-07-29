/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.exec.store.sys;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

import com.dremio.datastore.KVStore;
import com.dremio.datastore.PassThroughSerializer;
import com.dremio.datastore.StoreBuildingFactory;
import com.dremio.datastore.StringSerializer;
import com.dremio.exec.serialization.JacksonSerializer;
import com.dremio.exec.store.sys.store.KVPersistentStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Maps;

public class PStoreTestUtil {
//  private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(PStoreTestUtil.class);

  private static final String STORE_NAME = "sys.test";
  private final static JacksonSerializer<String> serializer = new JacksonSerializer<>(new ObjectMapper(), String.class);

  public static class TestStoreCreator implements KVPersistentStore.PersistentStoreCreator {
    @Override
    public KVStore<String, byte[]> build(StoreBuildingFactory factory) {
      return factory.<String, byte[]>newStore()
        .name(STORE_NAME)
        .keySerializer(StringSerializer.class)
        .valueSerializer(PassThroughSerializer.class)
        .build();
    }
  }

  public static void test(PersistentStoreProvider provider) throws Exception{
    final PersistentStore<String> store = provider.getOrCreateStore(STORE_NAME, TestStoreCreator.class, serializer);

    String[] keys = {"first", "second"};
    String[] values = {"value1", "value2"};
    Map<String, String> expectedMap = Maps.newHashMap();

    for(int i =0; i < keys.length; i++){
      expectedMap.put(keys[i], values[i]);
      store.put(keys[i], values[i]);
    }
    // allow one second for puts to propagate back to cache
    {
      Iterator<Map.Entry<String, String>> iter = store.getAll();
      for(int i =0; i < keys.length; i++){
        Entry<String, String> e = iter.next();
        assertTrue(expectedMap.containsKey(e.getKey()));
        assertEquals(expectedMap.get(e.getKey()), e.getValue());
      }

      assertFalse(iter.hasNext());
    }

    {
      Iterator<Map.Entry<String, String>> iter = store.getAll();
      while(iter.hasNext()){
        final String key = iter.next().getKey();
        store.delete(key);
      }
    }

    // allow one second for deletes to propagate back to cache

    assertFalse(store.getAll().hasNext());
  }
}
