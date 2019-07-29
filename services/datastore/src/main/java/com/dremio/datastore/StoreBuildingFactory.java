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
package com.dremio.datastore;

import com.dremio.datastore.KVStoreProvider.StoreBuilder;

/**
 * Internal Interface for defining how to build datastore
 */
public interface StoreBuildingFactory {

  /**
   * Create a new key-value store which preserves key ordering
   *
   * The key-value store is performing comparison on the serialized key (byte array). As
   * different backend might use different comparators, two different backends might return keys in a
   * different order.
   *
   * @param name the name of the table
   * @param keySerializerClass the key serializer class (singleton)
   * @param keySerializerClass the value serializer class (singleton)
   * @return a hash based key value store
   */
  <K, V> StoreBuilder<K, V> newStore();
}
