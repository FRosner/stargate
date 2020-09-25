/*
 * Copyright The Stargate Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.stargate.db.cassandra.impl;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import io.stargate.db.BatchType;
import io.stargate.db.ClientState;
import io.stargate.db.Parameters;
import io.stargate.db.Result;
import io.stargate.db.Result.Flag;
import io.stargate.db.datastore.common.util.ColumnUtils;
import io.stargate.db.schema.Column;
import io.stargate.db.schema.ImmutableColumn;
import io.stargate.db.schema.ImmutableUserDefinedType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.cql3.ColumnSpecification;
import org.apache.cassandra.cql3.QueryOptions;
import org.apache.cassandra.cql3.QueryProcessor;
import org.apache.cassandra.cql3.statements.BatchStatement;
import org.apache.cassandra.cql3.statements.ParsedStatement;
import org.apache.cassandra.db.marshal.AbstractType;
import org.apache.cassandra.db.marshal.ListType;
import org.apache.cassandra.db.marshal.MapType;
import org.apache.cassandra.db.marshal.ReversedType;
import org.apache.cassandra.db.marshal.SetType;
import org.apache.cassandra.db.marshal.TupleType;
import org.apache.cassandra.db.marshal.UserType;
import org.apache.cassandra.exceptions.CassandraException;
import org.apache.cassandra.service.QueryState;
import org.apache.cassandra.service.pager.PagingState;
import org.apache.cassandra.stargate.cql3.functions.FunctionName;
import org.apache.cassandra.stargate.db.ConsistencyLevel;
import org.apache.cassandra.stargate.db.WriteType;
import org.apache.cassandra.stargate.exceptions.AlreadyExistsException;
import org.apache.cassandra.stargate.exceptions.AuthenticationException;
import org.apache.cassandra.stargate.exceptions.ConfigurationException;
import org.apache.cassandra.stargate.exceptions.FunctionExecutionException;
import org.apache.cassandra.stargate.exceptions.InvalidRequestException;
import org.apache.cassandra.stargate.exceptions.IsBootstrappingException;
import org.apache.cassandra.stargate.exceptions.OverloadedException;
import org.apache.cassandra.stargate.exceptions.PreparedQueryNotFoundException;
import org.apache.cassandra.stargate.exceptions.ReadFailureException;
import org.apache.cassandra.stargate.exceptions.ReadTimeoutException;
import org.apache.cassandra.stargate.exceptions.RequestFailureReason;
import org.apache.cassandra.stargate.exceptions.SyntaxException;
import org.apache.cassandra.stargate.exceptions.TruncateException;
import org.apache.cassandra.stargate.exceptions.UnauthorizedException;
import org.apache.cassandra.stargate.exceptions.UnavailableException;
import org.apache.cassandra.stargate.exceptions.WriteFailureException;
import org.apache.cassandra.stargate.exceptions.WriteTimeoutException;
import org.apache.cassandra.stargate.locator.InetAddressAndPort;
import org.apache.cassandra.stargate.transport.ProtocolException;
import org.apache.cassandra.stargate.transport.ProtocolVersion;
import org.apache.cassandra.stargate.transport.ServerError;
import org.apache.cassandra.stargate.utils.MD5Digest;
import org.apache.cassandra.transport.Event;
import org.apache.cassandra.transport.ProtocolVersionLimit;
import org.apache.cassandra.transport.messages.ResultMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Conversion {
  private static final Logger LOG = LoggerFactory.getLogger(Conversion.class);

  // A number of constructors for classes related to QueryOptions but that are not accessible in C*
  // at the moment and need to be accessed through reflection.

  // SpecificOptions(int pageSize, PagingState state, ConsistencyLevel serialConsistency, long
  // timestamp)
  private static final Constructor<?> specificOptionsCtor;
  // DefaultQueryOptions(ConsistencyLevel consistency, List<ByteBuffer> values, boolean
  // skipMetadata, QueryOptions.SpecificOptions options, ProtocolVersion protocolVersion)
  private static final Constructor<?> defaultOptionsCtor;
  // OptionsWithNames(QueryOptions.DefaultQueryOptions wrapped, List<String> names)
  private static final Constructor<?> optionsWithNameCtor;

  static {
    try {
      Class<?> defaultOptionsClass =
          Class.forName("org.apache.cassandra.cql3.QueryOptions$DefaultQueryOptions");
      Class<?> specificOptionsClass =
          Class.forName("org.apache.cassandra.cql3.QueryOptions$SpecificOptions");
      Class<?> withNamesClass =
          Class.forName("org.apache.cassandra.cql3.QueryOptions$OptionsWithNames");

      specificOptionsCtor =
          specificOptionsClass.getDeclaredConstructor(
              int.class,
              PagingState.class,
              org.apache.cassandra.db.ConsistencyLevel.class,
              long.class);
      specificOptionsCtor.setAccessible(true);

      defaultOptionsCtor =
          defaultOptionsClass.getDeclaredConstructor(
              org.apache.cassandra.db.ConsistencyLevel.class,
              List.class,
              boolean.class,
              specificOptionsClass,
              org.apache.cassandra.transport.ProtocolVersion.class);
      defaultOptionsCtor.setAccessible(true);

      optionsWithNameCtor = withNamesClass.getDeclaredConstructor(defaultOptionsClass, List.class);
      optionsWithNameCtor.setAccessible(true);
    } catch (Exception e) {
      throw new RuntimeException(
          "Error during initialization of the persistence layer: some "
              + "reflection-based accesses cannot be setup.",
          e);
    }
  }

  private static final Map<Class<? extends AbstractType>, Column.Type> TYPE_MAPPINGS;

  static {
    Map<Class<? extends AbstractType>, Column.Type> types = new HashMap<>();
    Arrays.asList(Column.Type.values())
        .forEach(
            ct -> {
              if (ct != Column.Type.Tuple
                  && ct != Column.Type.List
                  && ct != Column.Type.Map
                  && ct != Column.Type.Set
                  && ct != Column.Type.UDT) {
                types.put(ColumnUtils.toInternalType(ct).getClass(), ct);
              }
            });
    TYPE_MAPPINGS = ImmutableMap.copyOf(types);
  }

  public static QueryState newQueryState(Optional<ClientState> state) {
    return new QueryState(internalClientState(state));
  }

  private static org.apache.cassandra.service.ClientState internalClientState(
      Optional<ClientState> state) {
    return state
        .map(c -> (org.apache.cassandra.service.ClientState) c.getWrapped())
        .orElse(org.apache.cassandra.service.ClientState.forInternalCalls());
  }

  public static QueryOptions toInternal(
      List<ByteBuffer> values, List<String> boundNames, Parameters parameters) {
    org.apache.cassandra.transport.ProtocolVersion protocolVersion =
        toInternal(parameters.protocolVersion());
    // Note that PagingState.deserialize below modifies its input, so we duplicate to avoid nasty
    // surprises down the line
    PagingState pagingState =
        parameters
            .pagingState()
            .map(s -> PagingState.deserialize(s.duplicate(), protocolVersion))
            .orElse(null);
    return createOptions(
        toInternal(parameters.consistencyLevel()),
        values,
        boundNames,
        parameters.skipMetadataInResult(),
        parameters.pageSize().orElse(-1),
        pagingState,
        toInternal(parameters.serialConsistencyLevel().orElse(ConsistencyLevel.SERIAL)),
        protocolVersion,
        parameters.defaultTimestamp().orElse(Long.MIN_VALUE));
  }

  private static QueryOptions createOptions(
      org.apache.cassandra.db.ConsistencyLevel consistencyLevel,
      List<ByteBuffer> values,
      List<String> boundNames,
      boolean skipMetadata,
      int pageSize,
      PagingState pagingState,
      org.apache.cassandra.db.ConsistencyLevel serialConsistency,
      org.apache.cassandra.transport.ProtocolVersion protocolVersion,
      long timestamp) {

    QueryOptions options;
    try {
      Object specificOptions =
          specificOptionsCtor.newInstance(pageSize, pagingState, serialConsistency, timestamp);
      Object defaultOptions =
          defaultOptionsCtor.newInstance(
              consistencyLevel, values, skipMetadata, specificOptions, protocolVersion);
      options = (org.apache.cassandra.cql3.QueryOptions) defaultOptions;

      // Adds names if there is some.
      if (boundNames != null) {
        options = (QueryOptions) optionsWithNameCtor.newInstance(defaultOptions, boundNames);
      }
    } catch (Exception e) {
      // We can't afford to ignore that: the values wouldn't be in the proper order, and worst
      // case scenario, this could end up inserting values in the wrong columns, which essentially
      // boils down to corrupting the use DB.
      throw new RuntimeException(
          "Unexpected error while trying to bind the query values by name", e);
    }
    return options;
  }

  public static org.apache.cassandra.db.ConsistencyLevel toInternal(ConsistencyLevel cl) {
    return cl == null ? null : org.apache.cassandra.db.ConsistencyLevel.fromCode(cl.code);
  }

  public static ConsistencyLevel toExternal(org.apache.cassandra.db.ConsistencyLevel cl) {
    return cl == null ? null : ConsistencyLevel.fromCode(cl.code);
  }

  public static org.apache.cassandra.transport.ProtocolVersion toInternal(
      ProtocolVersion protocolVersion) {
    // TODO(mpenick): Allow older version?
    return protocolVersion == null
        ? null
        : org.apache.cassandra.transport.ProtocolVersion.decode(
            protocolVersion.asInt(), ProtocolVersionLimit.SERVER_DEFAULT);
  }

  public static ProtocolVersion toExternal(
      org.apache.cassandra.transport.ProtocolVersion protocolVersion) {
    return protocolVersion == null ? null : ProtocolVersion.decode(protocolVersion.asInt(), true);
  }

  public static InetAddressAndPort toExternal(InetAddress internal) {
    return InetAddressAndPort.getByAddressOverrideDefaults(
        internal, DatabaseDescriptor.getStoragePort());
  }

  public static InetAddress toInternal(InetAddressAndPort external) {
    return external.address;
  }

  public static org.apache.cassandra.utils.MD5Digest toInternal(MD5Digest id) {
    return org.apache.cassandra.utils.MD5Digest.wrap(id.bytes);
  }

  public static MD5Digest toExternal(org.apache.cassandra.utils.MD5Digest id) {
    return MD5Digest.wrap(id.bytes);
  }

  public static Map<InetAddressAndPort, RequestFailureReason> toExternal(
      Map<InetAddress, org.apache.cassandra.exceptions.RequestFailureReason> internal) {
    Map<InetAddressAndPort, RequestFailureReason> external = new HashMap<>(internal.size());
    for (Map.Entry<InetAddress, org.apache.cassandra.exceptions.RequestFailureReason> entry :
        internal.entrySet()) {
      external.put(
          InetAddressAndPort.getByAddress(entry.getKey()),
          RequestFailureReason.fromCode(entry.getValue().code));
    }
    return external;
  }

  public static WriteType toExternal(org.apache.cassandra.db.WriteType internal) {
    return WriteType.fromOrdinal(internal.ordinal());
  }

  public static FunctionName toExternal(org.apache.cassandra.cql3.functions.FunctionName internal) {
    return new FunctionName(internal.keyspace, internal.name);
  }

  public static RuntimeException toExternal(org.apache.cassandra.exceptions.CassandraException e) {
    switch (e.code()) {
      case SERVER_ERROR:
        return addSuppressed(new ServerError(e.getMessage()), e);
      case BAD_CREDENTIALS:
        return addSuppressed(new AuthenticationException(e.getMessage(), e.getCause()), e);
      case UNAVAILABLE:
        org.apache.cassandra.exceptions.UnavailableException ue =
            (org.apache.cassandra.exceptions.UnavailableException) e;
        return addSuppressed(
            new UnavailableException(
                ue.getMessage(), toExternal(ue.consistency), ue.required, ue.alive),
            e);
      case OVERLOADED:
        return addSuppressed(new OverloadedException(e.getMessage()), e);
      case IS_BOOTSTRAPPING:
        return addSuppressed(new IsBootstrappingException(), e);
      case TRUNCATE_ERROR:
        return addSuppressed(
            e.getCause() == null
                ? new TruncateException(e.getMessage())
                : new TruncateException(e.getCause()),
            e);
      case WRITE_TIMEOUT:
        org.apache.cassandra.exceptions.WriteTimeoutException wte =
            (org.apache.cassandra.exceptions.WriteTimeoutException) e;
        return addSuppressed(
            new WriteTimeoutException(
                toExternal(wte.writeType),
                toExternal(wte.consistency),
                wte.received,
                wte.blockFor,
                wte.getMessage()),
            e);
      case READ_TIMEOUT:
        org.apache.cassandra.exceptions.ReadTimeoutException rte =
            (org.apache.cassandra.exceptions.ReadTimeoutException) e;
        return addSuppressed(
            new ReadTimeoutException(
                toExternal(rte.consistency), rte.received, rte.blockFor, rte.dataPresent),
            e);
      case READ_FAILURE:
        org.apache.cassandra.exceptions.ReadFailureException rfe =
            (org.apache.cassandra.exceptions.ReadFailureException) e;
        return addSuppressed(
            new ReadFailureException(
                toExternal(rfe.consistency),
                rfe.received,
                rfe.blockFor,
                rfe.dataPresent,
                toExternal(rfe.failureReasonByEndpoint)),
            e);
      case FUNCTION_FAILURE:
        org.apache.cassandra.exceptions.FunctionExecutionException fee =
            (org.apache.cassandra.exceptions.FunctionExecutionException) e;
        return addSuppressed(
            new FunctionExecutionException(toExternal(fee.functionName), fee.argTypes, fee.detail),
            e);
      case WRITE_FAILURE:
        org.apache.cassandra.exceptions.WriteFailureException wfe =
            (org.apache.cassandra.exceptions.WriteFailureException) e;
        return addSuppressed(
            new WriteFailureException(
                toExternal(wfe.consistency),
                wfe.received,
                wfe.blockFor,
                toExternal(wfe.writeType),
                toExternal(wfe.failureReasonByEndpoint)),
            e);
      case SYNTAX_ERROR:
        return addSuppressed(new SyntaxException(e.getMessage()), e);
      case UNAUTHORIZED:
        // Not adding suppressed exception to avoid information leaking
        return new UnauthorizedException(e.getMessage(), e.getCause());
      case INVALID:
        return addSuppressed(new InvalidRequestException(e.getMessage()), e);
      case CONFIG_ERROR:
        return addSuppressed(new ConfigurationException(e.getMessage(), e.getCause()), e);
      case ALREADY_EXISTS:
        org.apache.cassandra.exceptions.AlreadyExistsException aee =
            (org.apache.cassandra.exceptions.AlreadyExistsException) e;
        return addSuppressed(
            Strings.isNullOrEmpty(aee.cfName)
                ? new AlreadyExistsException(aee.ksName)
                : new AlreadyExistsException(aee.ksName, aee.cfName),
            e);
      case UNPREPARED:
        org.apache.cassandra.exceptions.PreparedQueryNotFoundException pnfe =
            (org.apache.cassandra.exceptions.PreparedQueryNotFoundException) e;
        return addSuppressed(new PreparedQueryNotFoundException(MD5Digest.wrap(pnfe.id.bytes)), e);
    }
    return e;
  }

  private static RuntimeException addSuppressed(RuntimeException e, RuntimeException suppressed) {
    e.addSuppressed(suppressed);
    return e;
  }

  public static Result.ResultMetadata toResultMetadata(
      org.apache.cassandra.cql3.ResultSet.ResultMetadata metadata,
      org.apache.cassandra.transport.ProtocolVersion version) {
    List<Column> columns = new ArrayList<>();
    if (metadata.names != null) {
      metadata.names.forEach(
          c ->
              columns.add(
                  ImmutableColumn.builder()
                      .keyspace(c.ksName)
                      .table(c.cfName)
                      .name(c.name.toString())
                      .type(getTypeFromInternal(c.type))
                      .build()));
    }

    EnumSet<Result.Flag> flags = EnumSet.noneOf(Result.Flag.class);

    PagingState pagingState = null;
    MD5Digest resultMetadataId = null;
    try {
      Field f = metadata.getClass().getDeclaredField("pagingState");
      f.setAccessible(true);
      pagingState = (PagingState) f.get(metadata);
      if (pagingState != null) {
        flags.add(Flag.HAS_MORE_PAGES);
      }
    } catch (Exception e) {
      LOG.info("Unable to get paging state", e);
    }

    return new Result.ResultMetadata(
        flags,
        columns,
        resultMetadataId,
        pagingState != null ? pagingState.serialize(version) : null);
  }

  public static Result.PreparedMetadata toPreparedMetadata(
      List<ColumnSpecification> names, short[] indexes) {
    List<Column> columns = new ArrayList<>();
    names.forEach(
        c ->
            columns.add(
                ImmutableColumn.builder()
                    .keyspace(c.ksName)
                    .table(c.cfName)
                    .name(c.name.toString())
                    .type(getTypeFromInternal(c.type))
                    .build()));

    EnumSet<Result.Flag> flags = EnumSet.noneOf(Result.Flag.class);
    if (!names.isEmpty() && ColumnSpecification.allInSameTable(names))
      flags.add(Result.Flag.GLOBAL_TABLES_SPEC);

    return new Result.PreparedMetadata(flags, columns, indexes);
  }

  public static Result.SchemaChangeMetadata toSchemaChangeMetadata(ResultMessage resultMessage) {
    ResultMessage.SchemaChange schemaChange = (ResultMessage.SchemaChange) resultMessage;
    Event.SchemaChange change = schemaChange.change;
    return new Result.SchemaChangeMetadata(
        change.change.toString(),
        change.target.toString(),
        change.keyspace,
        change.name,
        change.argTypes);
  }

  public static Result toResult(
      ResultMessage resultMessage, org.apache.cassandra.transport.ProtocolVersion version) {
    return toResultInternal(resultMessage, version).setTracingId(resultMessage.getTracingId());
  }

  public static Result toResultInternal(
      ResultMessage resultMessage, org.apache.cassandra.transport.ProtocolVersion version) {
    switch (resultMessage.kind) {
      case VOID:
        return Result.VOID;
      case ROWS:
        return new Result.Rows(
            ((ResultMessage.Rows) resultMessage).result.rows,
            toResultMetadata(((ResultMessage.Rows) resultMessage).result.metadata, version));
      case SET_KEYSPACE:
        return new Result.SetKeyspace(((ResultMessage.SetKeyspace) resultMessage).keyspace);
      case SCHEMA_CHANGE:
        return new Result.SchemaChange(toSchemaChangeMetadata(resultMessage));
      case PREPARED:
        ResultMessage.Prepared prepared = (ResultMessage.Prepared) resultMessage;
        ParsedStatement.Prepared preparedStatement =
            QueryProcessor.instance.getPrepared(prepared.statementId);
        return new Result.Prepared(
            Conversion.toExternal(prepared.statementId),
            null,
            toResultMetadata(prepared.resultMetadata, null),
            toPreparedMetadata(prepared.metadata.names, preparedStatement.partitionKeyBindIndexes));
    }

    throw new ProtocolException("Unexpected type for RESULT message: " + resultMessage.kind);
  }

  public static Throwable convertInternalException(Throwable t) {
    if (t instanceof CassandraException) {
      return Conversion.toExternal((CassandraException) t);
    } else if (t instanceof org.apache.cassandra.transport.ProtocolException) {
      // Note that ProtocolException is not a CassandraException
      org.apache.cassandra.transport.ProtocolException ex =
          (org.apache.cassandra.transport.ProtocolException) t;
      return new ProtocolException(t.getMessage(), toExternal(ex.getForcedProtocolVersion()));
    }
    return t;
  }

  public static List<Object> toInternalQueryOrIds(List<Object> queryOrIds) {
    List<Object> internal = new ArrayList<>(queryOrIds.size());
    for (Object o : queryOrIds) {
      if (o instanceof MD5Digest) {
        internal.add(toInternal((MD5Digest) o));
      } else {
        internal.add(o);
      }
    }
    return internal;
  }

  public static BatchStatement.Type toInternal(BatchType external) {
    switch (external) {
      case LOGGED:
        return BatchStatement.Type.LOGGED;
      case UNLOGGED:
        return BatchStatement.Type.UNLOGGED;
      case COUNTER:
        return BatchStatement.Type.COUNTER;
    }
    throw new IllegalArgumentException("Invalid batch type");
  }

  public static Column.ColumnType getTypeFromInternal(AbstractType abstractType) {
    if (abstractType instanceof ReversedType) {
      return getTypeFromInternal(((ReversedType) abstractType).baseType);
    }
    if (abstractType instanceof MapType) {
      return Column.Type.Map.of(
              getTypeFromInternal(((MapType) abstractType).getKeysType()),
              getTypeFromInternal(((MapType) abstractType).getValuesType()))
          .frozen(!abstractType.isMultiCell());
    } else if (abstractType instanceof SetType) {
      return Column.Type.Set.of(getTypeFromInternal(((SetType) abstractType).getElementsType()))
          .frozen(!abstractType.isMultiCell());
    } else if (abstractType instanceof ListType) {
      return Column.Type.List.of(getTypeFromInternal(((ListType) abstractType).getElementsType()))
          .frozen(!abstractType.isMultiCell());
    } else if (abstractType.getClass().equals(TupleType.class)) {
      TupleType tupleType = ((TupleType) abstractType);
      return Column.Type.Tuple.of(
              ((TupleType) abstractType)
                  .allTypes().stream()
                      .map(t -> getTypeFromInternal(t))
                      .toArray(Column.ColumnType[]::new))
          .frozen(!tupleType.isMultiCell());
    } else if (abstractType.getClass().equals(UserType.class)) {
      UserType udt = (UserType) abstractType;
      return ImmutableUserDefinedType.builder()
          .keyspace(udt.keyspace)
          .name(udt.getNameAsString())
          .addAllColumns(getUDTColumns(udt))
          .build()
          .frozen(!udt.isMultiCell());
    }

    Column.Type type = TYPE_MAPPINGS.get(abstractType.getClass());
    Preconditions.checkArgument(
        type != null, "Unknown type mapping for %s", abstractType.getClass());
    return type;
  }

  public static List<Column> getUDTColumns(org.apache.cassandra.db.marshal.UserType userType) {
    List<Column> columns = new ArrayList<>(userType.fieldTypes().size());
    for (int i = 0; i < userType.fieldTypes().size(); i++) {
      columns.add(
          ImmutableColumn.builder()
              .name(userType.fieldName(i).toString())
              .type(getTypeFromInternal(userType.fieldType(i)))
              .kind(Column.Kind.Regular)
              .build());
    }
    return columns;
  }
}
