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
package com.dremio.plugins.azure;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.inject.Provider;

import org.apache.arrow.util.Preconditions;
import org.apache.hadoop.fs.FileSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dremio.common.exceptions.UserException;
import com.dremio.connector.metadata.BytesOutput;
import com.dremio.connector.metadata.DatasetHandle;
import com.dremio.connector.metadata.DatasetMetadata;
import com.dremio.connector.metadata.extensions.ValidateMetadataOption;
import com.dremio.exec.catalog.StoragePluginId;
import com.dremio.exec.catalog.conf.Property;
import com.dremio.exec.physical.base.WriterOptions;
import com.dremio.exec.planner.logical.CreateTableEntry;
import com.dremio.exec.server.SabotContext;
import com.dremio.exec.store.SchemaConfig;
import com.dremio.exec.store.dfs.FileSystemPlugin;
import com.dremio.exec.store.dfs.FileSystemWrapper;
import com.dremio.plugins.util.ContainerFileSystem.ContainerFailure;
import com.dremio.sabot.exec.context.OperatorContext;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.SourceState;

/**
 * Storage plugin for Microsoft Azure Storage
 */
class AzureStoragePlugin extends FileSystemPlugin<AzureStorageConf> {

  private static final Logger logger = LoggerFactory.getLogger(AzureStoragePlugin.class);

  public AzureStoragePlugin(AzureStorageConf config, SabotContext context, String name, Provider<StoragePluginId> idProvider) {
    super(config, context, name, idProvider);
  }

  @Override
  public SourceState getState() {
    try {
      ensureDefaultName();
      AzureStorageFileSystem fs = getSystemUserFS().unwrap(AzureStorageFileSystem.class);
      fs.refreshFileSystems();
      List<ContainerFailure> failures = fs.getSubFailures();
      if(failures.isEmpty()) {
        return SourceState.GOOD;
      }
      StringBuilder sb = new StringBuilder();
      for(ContainerFailure f : failures) {
        sb.append(f.getName());
        sb.append(": ");
        sb.append(f.getException().getMessage());
        sb.append("\n");
      }

      return SourceState.warnState(sb.toString());

    } catch (Exception e) {
      return SourceState.badState(e);
    }
  }

  //DX-17365: Azure does not return correct "Last Modified" times for folders, therefore new
  //          subfolder discovery is not possible by checking super folder's 'Last Modified" time.
  //          A full refresh is required to guarantee that container content is up-to-date.
  //          Always return metadata invalid in order to trigger a refresh.
  @Override
  public MetadataValidity validateMetadata(BytesOutput signature, DatasetHandle datasetHandle, DatasetMetadata metadata,
                                           ValidateMetadataOption... options){
    return MetadataValidity.INVALID;
  }

  private void ensureDefaultName() throws IOException {
    String urlSafeName = URLEncoder.encode(getName(), "UTF-8");
    getFsConf().set(FileSystem.FS_DEFAULT_NAME_KEY, AzureStorageFileSystem.SCHEME + urlSafeName);
    final FileSystemWrapper fs = getSystemUserFS();
    // do not use fs.getURI() or fs.getConf() directly as they will produce wrong results
    fs.initialize(URI.create(getFsConf().get(FileSystem.FS_DEFAULT_NAME_KEY)), getFsConf());
  }

  @Override
  protected List<Property> getProperties() {
    final AzureStorageConf config = getConfig();
    final List<Property> properties = new ArrayList<>();

    // configure hadoop fs implementation
    properties.add(new Property("fs.dremioAzureStorage.impl", AzureStorageFileSystem.class.getName()));

    // Explicitly disable caching of the FileSystem backing this plugin. Hadoop's caching mechanism is only
    // keyed by the URI, and not by connection properties, so if credentials are supplied through properties,
    // Hadoop would return incorrect FileSystems from its cache.
    // Note that the scheme for the FileSystem this FileSystemPlugin utilizes must match the second part of the
    // property name.
    properties.add(new Property("fs.dremioAzureStorage.impl.disable.cache", "true"));

    // configure azure properties.
    properties.add(new Property(AzureStorageFileSystem.ACCOUNT, config.accountName));
    properties.add(new Property(AzureStorageFileSystem.SECURE, Boolean.toString(config.enableSSL)));
    properties.add(new Property(AzureStorageFileSystem.MODE, config.accountKind.name()));

    AzureAuthenticationType credentialsType = config.credentialsType;

    if(credentialsType == AzureAuthenticationType.AZURE_ACTIVE_DIRECTORY) {
      properties.add(new Property(AzureStorageFileSystem.CREDENTIALS_TYPE, AzureAuthenticationType.AZURE_ACTIVE_DIRECTORY.name()));
      properties.add(new Property(AzureStorageFileSystem.CLIENT_ID, config.clientId));
      properties.add(new Property(AzureStorageFileSystem.CLIENT_SECRET, config.clientSecret));
      properties.add(new Property(AzureStorageFileSystem.TOKEN_ENDPOINT, config.tokenEndpoint));
    } else {
      properties.add(new Property(AzureStorageFileSystem.CREDENTIALS_TYPE, AzureAuthenticationType.ACCESS_KEY.name()));
      properties.add(new Property(AzureStorageFileSystem.KEY, config.accessKey));
    }

    if(config.containers != null && config.containers.size() > 0) {
      String containers = config.containers.stream()
        .filter(c -> c != null && c.length() > 0)
        .map(c -> {
        Preconditions.checkArgument(!c.contains(","), "Container Names cannot contain commas.");
        return c;
      }).collect(Collectors.joining(","));
      properties.add(new Property(AzureStorageFileSystem.CONTAINER_LIST, containers));
    }

    // Properties are added in order so make sure that any hand provided properties override settings done via specific config
    List<Property> parentProperties = super.getProperties();
    if(parentProperties != null) {
      properties.addAll(parentProperties);
    }

    return properties;
  }

  @Override
  public CreateTableEntry createNewTable(
      SchemaConfig config,
      NamespaceKey key,
      WriterOptions writerOptions,
      Map<String, Object> storageOptions
  ) {
    Preconditions.checkArgument(key.size() >= 2, "key must be at least two parts");
    final String containerName = key.getPathComponents().get(1);
    if (key.size() == 2) {
      throw UserException.validationError()
          .message("Creating containers is not supported.", containerName)
          .build(logger);
    }

    final CreateTableEntry entry = super.createNewTable(config, key, writerOptions, storageOptions);

    final AzureStorageFileSystem fs = getSystemUserFS().unwrap(AzureStorageFileSystem.class);

    if (!fs.containerExists(containerName)) {
      throw UserException.validationError()
          .message("Cannot create the table because '%s' container does not exist.", containerName)
          .build(logger);
    }

    // TODO (DX-15571): Add a check for if the current user can possibly have write permissions
    // on the container. This is only valid when using Azure Active Directory (OAuth) authentication
    // as SharedKeys do not have an identity.

//    if (!fs.mayHaveWritePermission(containerName)) {
//      throw UserException.validationError()
//          .message("No write permission to '%s' container.", containerName)
//          .build(logger);
//    }

    return entry;
  }

  @Override
  protected boolean isAsyncEnabledForQuery(OperatorContext context) {
    return context != null && context.getOptions().getOption(AzureStorageOptions.ASYNC_READS);
  }
}
