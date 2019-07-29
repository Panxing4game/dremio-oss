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
package com.dremio.sabot.rpc.user;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.calcite.avatica.util.Quoting;

import com.dremio.common.utils.SqlUtils;
import com.dremio.exec.ExecConstants;
import com.dremio.exec.proto.UserBitShared.RpcEndpointInfos;
import com.dremio.exec.proto.UserBitShared.UserCredentials;
import com.dremio.exec.proto.UserProtos.Property;
import com.dremio.exec.proto.UserProtos.RecordBatchFormat;
import com.dremio.exec.proto.UserProtos.UserProperties;
import com.dremio.exec.server.options.SessionOptionManager;
import com.dremio.exec.store.ischema.InfoSchemaConstants;
import com.dremio.exec.work.user.SubstitutionSettings;
import com.dremio.options.OptionManager;
import com.dremio.service.namespace.NamespaceKey;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;

public class UserSession {
  private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(UserSession.class);

  public static final String SCHEMA = PropertySetter.SCHEMA.toPropertyName();
  public static final String USER = PropertySetter.USER.toPropertyName();
  public static final String PASSWORD = PropertySetter.PASSWORD.toPropertyName();
  public static final String IMPERSONATION_TARGET = PropertySetter.IMPERSONATION_TARGET.toPropertyName();
  public static final String QUOTING = PropertySetter.QUOTING.toPropertyName();
  public static final String SUPPORTFULLYQUALIFIEDPROJECTS = PropertySetter.SUPPORTFULLYQUALIFIEDPROJECTS.toPropertyName();

  private enum PropertySetter {
    USER, PASSWORD,

    QUOTING {
      @Override
      public void setValue(UserSession session, String value) {
        if (value == null) {
          return;
        }
        final Quoting quoting;
        switch(value.toUpperCase(Locale.ROOT)) {
        case "BACK_TICK":
          quoting = Quoting.BACK_TICK;
          break;

        case "DOUBLE_QUOTE":
          quoting = Quoting.DOUBLE_QUOTE;
          break;

        case "BRACKET":
          quoting = Quoting.BRACKET;
          break;

        default:
          logger.warn("Ignoring message to use initial quoting of type {}.", value);
          return;
        }
        session.initialQuoting = quoting;
      }
    },

    SCHEMA {
      @Override
      public void setValue(UserSession session, String value) {
        session.defaultSchemaPath = Strings.isNullOrEmpty(value) ? null : new NamespaceKey(SqlUtils.parseSchemaPath(value));
      }
    },

    IMPERSONATION_TARGET {
      @Override
      public void setValue(UserSession session, String value) {
        session.impersonationTarget = value;
      }
    },

    SUPPORTFULLYQUALIFIEDPROJECTS {
      @Override
      public void setValue(UserSession session, String value) {
        session.supportFullyQualifiedProjections = "true".equalsIgnoreCase(value);
      }
    };

    /**
     * Set the corresponding
     * @param session
     * @param value
     */
    public void setValue(UserSession session, String value) {
      // Default: do nothing
    }

    public String toPropertyName() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  private final AtomicInteger queryCount = new AtomicInteger(0);
  private boolean supportComplexTypes = false;
  private UserCredentials credentials;
  private NamespaceKey defaultSchemaPath;
  private OptionManager sessionOptions;
  private RpcEndpointInfos clientInfos;
  private boolean useLegacyCatalogName = false;
  private String impersonationTarget = null;
  private Quoting initialQuoting;
  private boolean supportFullyQualifiedProjections;
  private RecordBatchFormat recordBatchFormat = RecordBatchFormat.DREMIO_1_4;
  private boolean exposeInternalSources = false;
  private SubstitutionSettings substitutionSettings = SubstitutionSettings.of();

  public static class Builder {
    UserSession userSession;

    public static Builder newBuilder() {
      return new Builder();
    }

    public Builder withCredentials(UserCredentials credentials) {
      userSession.credentials = credentials;
      return this;
    }

    public Builder withOptionManager(OptionManager systemOptions) {
      userSession.sessionOptions = new SessionOptionManager(systemOptions, userSession);
      return this;
    }

    public Builder withDefaultSchema(List<String> defaultSchemaPath){
      if(defaultSchemaPath == null) {
        userSession.defaultSchemaPath = null;
        return this;
      }

      userSession.defaultSchemaPath = new NamespaceKey(defaultSchemaPath);
      return this;
    }

    public Builder withClientInfos(RpcEndpointInfos infos) {
      userSession.clientInfos = infos;
      return this;
    }

    public Builder withRecordBatchFormat(RecordBatchFormat recordBatchFormat) {
      userSession.recordBatchFormat = recordBatchFormat;
      return this;
    }

    public Builder withLegacyCatalog() {
      userSession.useLegacyCatalogName = true;
      return this;
    }

    public Builder withInitialQuoting(Quoting quoting) {
      userSession.initialQuoting = quoting;
      return this;
    }

    public Builder withFullyQualifiedProjectsSupport(boolean value) {
      userSession.supportFullyQualifiedProjections = value;
      return this;
    }

    public Builder withSubstitutionSettings(final SubstitutionSettings substitutionSettings) {
      userSession.substitutionSettings = substitutionSettings;
      return this;
    }

    public Builder withUserProperties(UserProperties properties) {
      if (properties == null) {
        return this;
      }

      for (int i = 0; i < properties.getPropertiesCount(); i++) {
        final Property property = properties.getProperties(i);
        final String propertyName = property.getKey().toUpperCase(Locale.ROOT);
        final String propertyValue = property.getValue();
        try {
          final PropertySetter sessionProperty = PropertySetter.valueOf(propertyName);
          sessionProperty.setValue(userSession, propertyValue);
        } catch(IllegalArgumentException e) {
          logger.warn("Ignoring unknown property: {}", propertyName);
        }
      }

      return this;
    }

    public Builder setSupportComplexTypes(boolean supportComplexTypes) {
      userSession.supportComplexTypes = supportComplexTypes;
      return this;
    }

    public Builder exposeInternalSources(boolean exposeInternalSources) {
      userSession.exposeInternalSources = exposeInternalSources;
      return this;
    }

    public UserSession build() {
      UserSession session = userSession;
      userSession = null;
      return session;
    }

    Builder() {
      userSession = new UserSession();
    }
  }

  private UserSession() {
  }

  public boolean isSupportComplexTypes() {
    return supportComplexTypes;
  }

  public OptionManager getOptions() {
    return sessionOptions;
  }

  public UserCredentials getCredentials() {
    return credentials;
  }

  public RpcEndpointInfos getClientInfos() {
    return clientInfos;
  }

  public RecordBatchFormat getRecordBatchFormat() {
    return recordBatchFormat;
  }

  public boolean exposeInternalSources() {
    return exposeInternalSources;
  }

  public SubstitutionSettings getSubstitutionSettings() {
    return substitutionSettings;
  }

  public String getCatalogName() {
    return useLegacyCatalogName ? InfoSchemaConstants.IS_LEGACY_CATALOG_NAME : InfoSchemaConstants.IS_CATALOG_NAME;
  }

  public boolean useLegacyCatalogName() {
    return useLegacyCatalogName;
  }

  /**
   * Does the client requires support for fully qualified column names in projections?
   *
   * Ex:
   *   SELECT
   *       "elastic.yelp".business.city,
   *       "elastic.yelp".business.stars
   *   FROM
   *       "elastic.yelp".business
   *
   * Note: enabling this option disables complex field references in query (ex. mapCol.mapField, listCol[2])
   *
   * @return
   */
  public boolean supportFullyQualifiedProjections() {
    return supportFullyQualifiedProjections;
  }

  public static String getCatalogName(OptionManager options){
    return options.getOption(ExecConstants.USE_LEGACY_CATALOG_NAME) ? InfoSchemaConstants.IS_LEGACY_CATALOG_NAME : InfoSchemaConstants.IS_CATALOG_NAME;
  }

  /**
   * Replace current user credentials with the given user's credentials. Meant to be called only by a
   * {@link InboundImpersonationManager impersonation manager}.
   *
   * @param impersonationManager impersonation manager making this call
   * @param newCredentials user credentials to change to
   */
  public void replaceUserCredentials(final InboundImpersonationManager impersonationManager,
                                     final UserCredentials newCredentials) {
    Preconditions.checkNotNull(impersonationManager, "User credentials can only be replaced by an" +
        " impersonation manager.");
    credentials = newCredentials;
  }

  public String getTargetUserName() {
    return impersonationTarget;
  }

  public String getDefaultSchemaName() {
    return defaultSchemaPath == null ? "" : defaultSchemaPath.toString();
  }

  public void incrementQueryCount() {
    queryCount.incrementAndGet();
  }

  public int getQueryCount() {
    return queryCount.get();
  }

  public Quoting getInitialQuoting() {
    return initialQuoting;
  }

  /**
   * Set the schema path for the session.
   * @param newDefaultSchemaPath New default schema path to set. It should be an absolute schema
   */
  public void setDefaultSchemaPath(List<String> newDefaultSchemaPath) {
    this.defaultSchemaPath = newDefaultSchemaPath != null ? new NamespaceKey(newDefaultSchemaPath) : null;
  }

  /**
   * @return Get current default schema path.
   */
  public NamespaceKey getDefaultSchemaPath() {
    return defaultSchemaPath;
  }
}
