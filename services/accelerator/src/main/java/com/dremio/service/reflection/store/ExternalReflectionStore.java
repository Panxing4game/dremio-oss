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
package com.dremio.service.reflection.store;

import static com.dremio.datastore.SearchQueryUtils.newTermQuery;
import static com.dremio.service.reflection.store.ReflectionIndexKeys.DATASET_ID;
import static com.dremio.service.reflection.store.ReflectionIndexKeys.REFLECTION_ID;
import static com.dremio.service.reflection.store.ReflectionIndexKeys.REFLECTION_NAME;
import static com.dremio.service.reflection.store.ReflectionIndexKeys.TARGET_DATASET_ID;

import java.util.Map.Entry;

import javax.inject.Provider;

import com.dremio.datastore.IndexedStore;
import com.dremio.datastore.IndexedStore.FindByCondition;
import com.dremio.datastore.KVStoreProvider;
import com.dremio.datastore.KVStoreProvider.DocumentConverter;
import com.dremio.datastore.KVStoreProvider.DocumentWriter;
import com.dremio.datastore.StoreBuildingFactory;
import com.dremio.datastore.StoreCreationFunction;
import com.dremio.datastore.VersionExtractor;
import com.dremio.service.reflection.proto.ExternalReflection;
import com.dremio.service.reflection.proto.ReflectionId;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.Iterables;

/**
 * store for external reflections
 */
public class ExternalReflectionStore {
  private static final String TABLE_NAME = "external_reflection_store";
  private final Supplier<IndexedStore<ReflectionId, ExternalReflection>> store;

  public ExternalReflectionStore(final Provider<KVStoreProvider> provider) {
    Preconditions.checkNotNull(provider, "kvStore provider required");
    this.store = Suppliers.memoize(new Supplier<IndexedStore<ReflectionId, ExternalReflection>>() {
      @Override
      public IndexedStore<ReflectionId, ExternalReflection> get() {
        return provider.get().getStore(StoreCreator.class);
      }
    });
  }

  public void addExternalReflection(ExternalReflection externalReflection) {
    store.get().put(new ReflectionId(externalReflection.getId()), externalReflection);
  }

  public ExternalReflection get(String id) {
    return store.get().get(new ReflectionId(id));
  }

  public Iterable<ExternalReflection> findByDatasetId(String datasetId) {
    return Iterables.transform(
      store.get()
        .find(new FindByCondition()
          .setCondition(
            newTermQuery(DATASET_ID, datasetId)
          )
        ),
      new Function<Entry<ReflectionId, ExternalReflection>, ExternalReflection>() {
      @Override
      public ExternalReflection apply(Entry<ReflectionId, ExternalReflection> entry) {
        return entry.getValue();
      }
    });
  }

  public Iterable<ExternalReflection> getExternalReflections() {
    return Iterables.transform(store.get().find(), new Function<Entry<ReflectionId,ExternalReflection>, ExternalReflection>() {
      @Override
      public ExternalReflection apply(Entry<ReflectionId, ExternalReflection> entry) {
        return entry.getValue();
      }
    });
  }

  public void deleteExternalReflection(String id) {
    store.get().delete(new ReflectionId(id));
  }

  private static final class Converter implements DocumentConverter<ReflectionId,ExternalReflection> {
    @Override
    public void convert(DocumentWriter writer, ReflectionId key, ExternalReflection record) {
      writer.write(REFLECTION_ID, key.getId());
      writer.write(DATASET_ID, record.getQueryDatasetId());
      writer.write(TARGET_DATASET_ID, record.getTargetDatasetId());
      writer.write(REFLECTION_NAME, record.getName());
    }
  }

  /**
   * {@link ExternalReflectionStore} creator
   */
  public static final class StoreCreator implements StoreCreationFunction<IndexedStore<ReflectionId, ExternalReflection>> {
    @Override
    public IndexedStore<ReflectionId, ExternalReflection> build(StoreBuildingFactory factory) {
      return factory.<ReflectionId, ExternalReflection>newStore()
        .name(TABLE_NAME)
        .keySerializer(Serializers.ReflectionIdSerializer.class)
        .valueSerializer(Serializers.ExternalReflectionSerializer.class)
        .versionExtractor(ExternalReflectionVersionExtractor.class)
        .buildIndexed(Converter.class);
    }
  }

  private static final class ExternalReflectionVersionExtractor implements VersionExtractor<ExternalReflection> {

    @Override
    public Long getVersion(ExternalReflection value) {
      return value.getVersion();
    }

    @Override
    public void setVersion(ExternalReflection value, Long version) {
      value.setVersion(version);
    }

    @Override
    public String getTag(ExternalReflection value) {
      return value.getTag();
    }

    @Override
    public void setTag(ExternalReflection value, String version) {
      value.setTag(version);
    }
  }
}
