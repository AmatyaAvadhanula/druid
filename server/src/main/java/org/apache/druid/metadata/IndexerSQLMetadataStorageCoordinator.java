/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
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

package org.apache.druid.metadata;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.common.io.BaseEncoding;
import com.google.inject.Inject;
import org.apache.commons.lang.StringEscapeUtils;
import org.apache.druid.indexing.overlord.DataSourceMetadata;
import org.apache.druid.indexing.overlord.IndexerMetadataStorageCoordinator;
import org.apache.druid.indexing.overlord.SegmentCreateRequest;
import org.apache.druid.indexing.overlord.SegmentPublishResult;
import org.apache.druid.indexing.overlord.Segments;
import org.apache.druid.indexing.overlord.TaskLockInfo;
import org.apache.druid.java.util.common.DateTimes;
import org.apache.druid.java.util.common.IAE;
import org.apache.druid.java.util.common.ISE;
import org.apache.druid.java.util.common.Intervals;
import org.apache.druid.java.util.common.Pair;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.jackson.JacksonUtils;
import org.apache.druid.java.util.common.lifecycle.LifecycleStart;
import org.apache.druid.java.util.common.logger.Logger;
import org.apache.druid.java.util.common.parsers.CloseableIterator;
import org.apache.druid.segment.SegmentUtils;
import org.apache.druid.segment.realtime.appenderator.SegmentIdWithShardSpec;
import org.apache.druid.timeline.DataSegment;
import org.apache.druid.timeline.Partitions;
import org.apache.druid.timeline.SegmentTimeline;
import org.apache.druid.timeline.TimelineObjectHolder;
import org.apache.druid.timeline.partition.NoneShardSpec;
import org.apache.druid.timeline.partition.NumberedPartialShardSpec;
import org.apache.druid.timeline.partition.NumberedShardSpec;
import org.apache.druid.timeline.partition.PartialShardSpec;
import org.apache.druid.timeline.partition.PartitionChunk;
import org.apache.druid.timeline.partition.PartitionIds;
import org.apache.druid.timeline.partition.ShardSpec;
import org.apache.druid.timeline.partition.SingleDimensionShardSpec;
import org.apache.druid.utils.CollectionUtils;
import org.joda.time.DateTime;
import org.joda.time.Interval;
import org.joda.time.chrono.ISOChronology;
import org.skife.jdbi.v2.Handle;
import org.skife.jdbi.v2.PreparedBatch;
import org.skife.jdbi.v2.Query;
import org.skife.jdbi.v2.ResultIterator;
import org.skife.jdbi.v2.StatementContext;
import org.skife.jdbi.v2.TransactionCallback;
import org.skife.jdbi.v2.TransactionStatus;
import org.skife.jdbi.v2.exceptions.CallbackFailedException;
import org.skife.jdbi.v2.tweak.HandleCallback;
import org.skife.jdbi.v2.util.ByteArrayMapper;

import javax.annotation.Nullable;
import javax.validation.constraints.NotNull;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 *
 */
public class IndexerSQLMetadataStorageCoordinator implements IndexerMetadataStorageCoordinator
{
  private static final Logger log = new Logger(IndexerSQLMetadataStorageCoordinator.class);
  private static final int MAX_NUM_SEGMENTS_TO_ANNOUNCE_AT_ONCE = 100;

  private final ObjectMapper jsonMapper;
  private final MetadataStorageTablesConfig dbTables;
  private final SQLMetadataConnector connector;

  private final String insertSegmentQuery;

  @Inject
  public IndexerSQLMetadataStorageCoordinator(
      ObjectMapper jsonMapper,
      MetadataStorageTablesConfig dbTables,
      SQLMetadataConnector connector
  )
  {
    this.jsonMapper = jsonMapper;
    this.dbTables = dbTables;
    this.connector = connector;
    this.insertSegmentQuery = StringUtils.format(
        "INSERT INTO %1$s (id, dataSource, created_date, start, %2$send%2$s, partitioned, version, used, payload, used_status_last_updated) "
        + "VALUES (:id, :dataSource, :created_date, :start, :end, :partitioned, :version, :used, :payload, :used_status_last_updated)",
        dbTables.getSegmentsTable(),
        connector.getQuoteString()
    );
  }

  @LifecycleStart
  public void start()
  {
    connector.createDataSourceTable();
    connector.createPendingSegmentsTable();
    connector.createSegmentTable();
    connector.createSegmentVersionTable();
  }

  @Override
  public Collection<DataSegment> retrieveUsedSegmentsForIntervals(
      final String dataSource,
      final List<Interval> intervals,
      final Segments visibility
  )
  {
    if (intervals == null || intervals.isEmpty()) {
      throw new IAE("null/empty intervals");
    }
    return doRetrieveUsedSegments(dataSource, intervals, visibility);
  }

  @Override
  public Collection<DataSegment> retrieveAllUsedSegments(String dataSource, Segments visibility)
  {
    return doRetrieveUsedSegments(dataSource, Collections.emptyList(), visibility);
  }

  /**
   * @param intervals empty list means unrestricted interval.
   */
  private Collection<DataSegment> doRetrieveUsedSegments(
      final String dataSource,
      final List<Interval> intervals,
      final Segments visibility
  )
  {
    return connector.retryWithHandle(
        handle -> {
          if (visibility == Segments.ONLY_VISIBLE) {
            final SegmentTimeline timeline =
                getTimelineForIntervalsWithHandle(handle, dataSource, intervals);
            return timeline.findNonOvershadowedObjectsInInterval(Intervals.ETERNITY, Partitions.ONLY_COMPLETE);
          } else {
            return retrieveAllUsedSegmentsForIntervalsWithHandle(handle, dataSource, intervals);
          }
        }
    );
  }

  @Override
  public List<Pair<DataSegment, String>> retrieveUsedSegmentsAndCreatedDates(String dataSource)
  {
    String rawQueryString = "SELECT created_date, payload FROM %1$s WHERE dataSource = :dataSource AND used = true";
    final String queryString = StringUtils.format(rawQueryString, dbTables.getSegmentsTable());
    return connector.retryWithHandle(
        handle -> {
          Query<Map<String, Object>> query = handle
              .createQuery(queryString)
              .bind("dataSource", dataSource);
          return query
              .map((int index, ResultSet r, StatementContext ctx) ->
                       new Pair<>(
                           JacksonUtils.readValue(jsonMapper, r.getBytes("payload"), DataSegment.class),
                           r.getString("created_date")
                       )
              )
              .list();
        }
    );
  }

  @Override
  public List<DataSegment> retrieveUnusedSegmentsForInterval(final String dataSource, final Interval interval)
  {
    return retrieveUnusedSegmentsForInterval(dataSource, interval, null);
  }

  @Override
  public List<DataSegment> retrieveUnusedSegmentsForInterval(
      String dataSource,
      Interval interval,
      @Nullable Integer limit
  )
  {
    final List<DataSegment> matchingSegments = connector.inReadOnlyTransaction(
        (handle, status) -> {
          try (final CloseableIterator<DataSegment> iterator =
                   SqlSegmentsMetadataQuery.forHandle(handle, connector, dbTables, jsonMapper)
                       .retrieveUnusedSegments(dataSource, Collections.singletonList(interval), limit)) {
            return ImmutableList.copyOf(iterator);
          }
        }
    );

    log.info("Found %,d unused segments for %s for interval %s.", matchingSegments.size(), dataSource, interval);
    return matchingSegments;
  }

  @Override
  public int markSegmentsAsUnusedWithinInterval(String dataSource, Interval interval)
  {
    final Integer numSegmentsMarkedUnused = connector.retryTransaction(
        (handle, status) ->
            SqlSegmentsMetadataQuery.forHandle(handle, connector, dbTables, jsonMapper)
                                    .markSegmentsUnused(dataSource, interval),
        3,
        SQLMetadataConnector.DEFAULT_MAX_TRIES
    );

    log.info("Marked %,d segments unused for %s for interval %s.", numSegmentsMarkedUnused, dataSource, interval);
    return numSegmentsMarkedUnused;
  }

  /**
   * Fetches all the pending segments, whose interval overlaps with the given
   * search interval from the metadata store.
   */
  private Set<SegmentIdWithShardSpec> getPendingSegmentsForIntervalWithHandle(
      final Handle handle,
      final String dataSource,
      final Interval interval
  ) throws IOException
  {
    final Set<SegmentIdWithShardSpec> identifiers = new HashSet<>();

    final ResultIterator<byte[]> dbSegments =
        handle.createQuery(
                  StringUtils.format(
                      // This query might fail if the year has a different number of digits
                      // See https://github.com/apache/druid/pull/11582 for a similar issue
                      // Using long for these timestamps instead of varchar would give correct time comparisons
                      "SELECT payload FROM %1$s WHERE dataSource = :dataSource AND start < :end and %2$send%2$s > :start",
                      dbTables.getPendingSegmentsTable(), connector.getQuoteString()
                  )
              )
              .bind("dataSource", dataSource)
              .bind("start", interval.getStart().toString())
              .bind("end", interval.getEnd().toString())
              .map(ByteArrayMapper.FIRST)
              .iterator();

    while (dbSegments.hasNext()) {
      final byte[] payload = dbSegments.next();
      final SegmentIdWithShardSpec identifier = jsonMapper.readValue(payload, SegmentIdWithShardSpec.class);

      if (interval.overlaps(identifier.getInterval())) {
        identifiers.add(identifier);
      }
    }

    dbSegments.close();

    return identifiers;
  }

  private SegmentTimeline getTimelineForIntervalsWithHandle(
      final Handle handle,
      final String dataSource,
      final List<Interval> intervals
  ) throws IOException
  {
    try (final CloseableIterator<DataSegment> iterator =
             SqlSegmentsMetadataQuery.forHandle(handle, connector, dbTables, jsonMapper)
                                     .retrieveUsedSegments(dataSource, intervals)) {
      return SegmentTimeline.forSegments(iterator);
    }
  }

  private Collection<DataSegment> retrieveAllUsedSegmentsForIntervalsWithHandle(
      final Handle handle,
      final String dataSource,
      final List<Interval> intervals
  ) throws IOException
  {
    try (final CloseableIterator<DataSegment> iterator =
             SqlSegmentsMetadataQuery.forHandle(handle, connector, dbTables, jsonMapper)
                                     .retrieveUsedSegments(dataSource, intervals)) {
      final List<DataSegment> retVal = new ArrayList<>();
      iterator.forEachRemaining(retVal::add);
      return retVal;
    }
  }

  @Override
  public Set<DataSegment> commitSegments(final Set<DataSegment> segments) throws IOException
  {
    final SegmentPublishResult result = commitSegmentsAndMetadata(segments, null, null);

    // Metadata transaction cannot fail because we are not trying to do one.
    if (!result.isSuccess()) {
      throw new ISE("announceHistoricalSegments failed with null metadata, should not happen.");
    }

    return result.getSegments();
  }

  @Override
  public SegmentPublishResult commitSegmentsAndMetadata(
      final Set<DataSegment> segments,
      @Nullable final DataSourceMetadata startMetadata,
      @Nullable final DataSourceMetadata endMetadata
  ) throws IOException
  {
    if (segments.isEmpty()) {
      throw new IllegalArgumentException("segment set must not be empty");
    }

    final String dataSource = segments.iterator().next().getDataSource();
    for (DataSegment segment : segments) {
      if (!dataSource.equals(segment.getDataSource())) {
        throw new IllegalArgumentException("segments must all be from the same dataSource");
      }
    }

    if ((startMetadata == null && endMetadata != null) || (startMetadata != null && endMetadata == null)) {
      throw new IllegalArgumentException("start/end metadata pair must be either null or non-null");
    }

    // Find which segments are used (i.e. not overshadowed).
    final Set<DataSegment> usedSegments = new HashSet<>();
    List<TimelineObjectHolder<String, DataSegment>> segmentHolders =
        SegmentTimeline.forSegments(segments).lookupWithIncompletePartitions(Intervals.ETERNITY);
    for (TimelineObjectHolder<String, DataSegment> holder : segmentHolders) {
      for (PartitionChunk<DataSegment> chunk : holder.getObject()) {
        usedSegments.add(chunk.getObject());
      }
    }

    final AtomicBoolean definitelyNotUpdated = new AtomicBoolean(false);

    try {
      return connector.retryTransaction(
          new TransactionCallback<SegmentPublishResult>()
          {
            @Override
            public SegmentPublishResult inTransaction(
                final Handle handle,
                final TransactionStatus transactionStatus
            ) throws Exception
            {
              // Set definitelyNotUpdated back to false upon retrying.
              definitelyNotUpdated.set(false);

              if (startMetadata != null) {
                final DataStoreMetadataUpdateResult result = updateDataSourceMetadataWithHandle(
                    handle,
                    dataSource,
                    startMetadata,
                    endMetadata
                );

                if (result.isFailed()) {
                  // Metadata was definitely not updated.
                  transactionStatus.setRollbackOnly();
                  definitelyNotUpdated.set(true);

                  if (result.canRetry()) {
                    throw new RetryTransactionException(result.getErrorMsg());
                  } else {
                    throw new RuntimeException(result.getErrorMsg());
                  }
                }
              }

              final Set<DataSegment> inserted = announceHistoricalSegmentBatch(handle, segments, usedSegments);
              return SegmentPublishResult.ok(ImmutableSet.copyOf(inserted));
            }
          },
          3,
          getSqlMetadataMaxRetry()
      );
    }
    catch (CallbackFailedException e) {
      if (definitelyNotUpdated.get()) {
        return SegmentPublishResult.fail(e.getMessage());
      } else {
        // Must throw exception if we are not sure if we updated or not.
        throw e;
      }
    }
  }

  @Override
  public SegmentPublishResult commitReplaceSegments(
      final Set<DataSegment> segments,
      @Nullable Set<TaskLockInfo> taskLockInfos
  )
  {
    if (segments.isEmpty()) {
      throw new IllegalArgumentException("segment set must not be empty");
    }

    final String dataSource = segments.iterator().next().getDataSource();
    for (DataSegment segment : segments) {
      if (!dataSource.equals(segment.getDataSource())) {
        throw new IllegalArgumentException("segments must all be from the same dataSource");
      }
    }

    final AtomicBoolean definitelyNotUpdated = new AtomicBoolean(false);

    try {
      return connector.retryTransaction(
          (handle, transactionStatus) -> {
            // Set definitelyNotUpdated back to false upon retrying.
            definitelyNotUpdated.set(false);

            final Set<DataSegment> inserted = commitReplaceSegmentBatch(handle, segments, taskLockInfos);
            return SegmentPublishResult.ok(ImmutableSet.copyOf(inserted));
          },
          3,
          getSqlMetadataMaxRetry()
      );
    }
    catch (CallbackFailedException e) {
      if (definitelyNotUpdated.get()) {
        return SegmentPublishResult.fail(e.getMessage());
      } else {
        // Must throw exception if we are not sure if we updated or not.
        throw e;
      }
    }
  }

  @Override
  public SegmentPublishResult commitAppendSegments(
      final Set<DataSegment> segments,
      @Nullable final DataSourceMetadata startMetadata,
      @Nullable DataSourceMetadata endMetadata,
      @Nullable Map<DataSegment, TaskLockInfo> segmentLockMap
  )
  {
    if (segments.isEmpty()) {
      throw new IllegalArgumentException("No segments to append");
    }

    final String dataSource = segments.iterator().next().getDataSource();
    for (DataSegment segment : segments) {
      if (!dataSource.equals(segment.getDataSource())) {
        throw new IllegalArgumentException("All segments to append must belong to the same dataSource");
      }
    }

    if ((startMetadata == null && endMetadata != null) || (startMetadata != null && endMetadata == null)) {
      throw new IllegalArgumentException("Start and end metadata must either be both null or both non-null");
    }

    // Find which segments are used (i.e. not overshadowed).
    Set<DataSegment> newSegments = new HashSet<>(segments);
    final Map<DataSegment, Set<SegmentIdWithShardSpec>> segmentToNewMetadataMap = connector.retryTransaction(
        (handle, transactionStatus) -> allocateNewSegmentIds(handle, dataSource, segments),
        0,
        SQLMetadataConnector.DEFAULT_MAX_TRIES
    );
    for (DataSegment segment : segmentToNewMetadataMap.keySet()) {
      for (SegmentIdWithShardSpec newId : segmentToNewMetadataMap.get(segment)) {
        DataSegment newSegment = new DataSegment(
            newId.getDataSource(),
            newId.getInterval(),
            newId.getVersion(),
            segment.getLoadSpec(),
            segment.getDimensions(),
            segment.getMetrics(),
            newId.getShardSpec(),
            segment.getBinaryVersion(),
            segment.getSize()
        );
        newSegments.add(newSegment);
      }
    }

    final AtomicBoolean definitelyNotUpdated = new AtomicBoolean(false);
    try {
      return connector.retryTransaction(
          (handle, transactionStatus) -> {
            // Set definitelyNotUpdated back to false upon retrying.
            definitelyNotUpdated.set(false);

            if (startMetadata != null) {
              final DataStoreMetadataUpdateResult result = updateDataSourceMetadataWithHandle(
                  handle,
                  dataSource,
                  startMetadata,
                  endMetadata
              );

              if (result.isFailed()) {
                // Metadata was definitely not updated.
                transactionStatus.setRollbackOnly();
                definitelyNotUpdated.set(true);

                if (result.canRetry()) {
                  throw new RetryTransactionException(result.getErrorMsg());
                } else {
                  throw new RuntimeException(result.getErrorMsg());
                }
              }
            }

            final Set<DataSegment> inserted = commitAppendSegmentBatch(handle, newSegments, segmentLockMap);
            return SegmentPublishResult.ok(ImmutableSet.copyOf(inserted));
          },
          3,
          getSqlMetadataMaxRetry()
      );
    }
    catch (CallbackFailedException e) {
      if (definitelyNotUpdated.get()) {
        return SegmentPublishResult.fail(e.getMessage());
      } else {
        // Must throw exception if we are not sure if we updated or not.
        throw e;
      }
    }
  }

  @Override
  public SegmentPublishResult commitMetadataOnly(
      String dataSource,
      DataSourceMetadata startMetadata,
      DataSourceMetadata endMetadata
  )
  {
    if (dataSource == null) {
      throw new IllegalArgumentException("datasource name cannot be null");
    }
    if (startMetadata == null) {
      throw new IllegalArgumentException("start metadata cannot be null");
    }
    if (endMetadata == null) {
      throw new IllegalArgumentException("end metadata cannot be null");
    }

    final AtomicBoolean definitelyNotUpdated = new AtomicBoolean(false);

    try {
      return connector.retryTransaction(
          new TransactionCallback<SegmentPublishResult>()
          {
            @Override
            public SegmentPublishResult inTransaction(
                final Handle handle,
                final TransactionStatus transactionStatus
            ) throws Exception
            {
              // Set definitelyNotUpdated back to false upon retrying.
              definitelyNotUpdated.set(false);

              final DataStoreMetadataUpdateResult result = updateDataSourceMetadataWithHandle(
                  handle,
                  dataSource,
                  startMetadata,
                  endMetadata
              );

              if (result.isFailed()) {
                // Metadata was definitely not updated.
                transactionStatus.setRollbackOnly();
                definitelyNotUpdated.set(true);

                if (result.canRetry()) {
                  throw new RetryTransactionException(result.getErrorMsg());
                } else {
                  throw new RuntimeException(result.getErrorMsg());
                }
              }

              return SegmentPublishResult.ok(ImmutableSet.of());
            }
          },
          3,
          getSqlMetadataMaxRetry()
      );
    }
    catch (CallbackFailedException e) {
      if (definitelyNotUpdated.get()) {
        return SegmentPublishResult.fail(e.getMessage());
      } else {
        // Must throw exception if we are not sure if we updated or not.
        throw e;
      }
    }
  }

  @VisibleForTesting
  public int getSqlMetadataMaxRetry()
  {
    return SQLMetadataConnector.DEFAULT_MAX_TRIES;
  }

  @Override
  public Map<SegmentCreateRequest, SegmentIdWithShardSpec> allocatePendingSegments(
      String dataSource,
      Interval allocateInterval,
      boolean skipSegmentLineageCheck,
      List<SegmentCreateRequest> requests
  )
  {
    Preconditions.checkNotNull(dataSource, "dataSource");
    Preconditions.checkNotNull(allocateInterval, "interval");

    final Interval interval = allocateInterval.withChronology(ISOChronology.getInstanceUTC());
    return connector.retryWithHandle(
        handle -> allocatePendingSegments(handle, dataSource, interval, skipSegmentLineageCheck, requests)
    );
  }

  @Override
  public SegmentIdWithShardSpec allocatePendingSegment(
      final String dataSource,
      final String sequenceName,
      @Nullable final String previousSegmentId,
      final Interval interval,
      final PartialShardSpec partialShardSpec,
      final String maxVersion,
      final boolean skipSegmentLineageCheck
  )
  {
    Preconditions.checkNotNull(dataSource, "dataSource");
    Preconditions.checkNotNull(sequenceName, "sequenceName");
    Preconditions.checkNotNull(interval, "interval");
    Preconditions.checkNotNull(maxVersion, "version");
    Interval allocateInterval = interval.withChronology(ISOChronology.getInstanceUTC());

    return connector.retryWithHandle(
        handle -> {
          if (skipSegmentLineageCheck) {
            return allocatePendingSegment(
                handle,
                dataSource,
                sequenceName,
                allocateInterval,
                partialShardSpec,
                maxVersion
            );
          } else {
            return allocatePendingSegmentWithSegmentLineageCheck(
                handle,
                dataSource,
                sequenceName,
                previousSegmentId,
                allocateInterval,
                partialShardSpec,
                maxVersion
            );
          }
        }
    );
  }

  @Nullable
  private SegmentIdWithShardSpec allocatePendingSegmentWithSegmentLineageCheck(
      final Handle handle,
      final String dataSource,
      final String sequenceName,
      @Nullable final String previousSegmentId,
      final Interval interval,
      final PartialShardSpec partialShardSpec,
      final String maxVersion
  ) throws IOException
  {
    final String previousSegmentIdNotNull = previousSegmentId == null ? "" : previousSegmentId;
    final CheckExistingSegmentIdResult result = checkAndGetExistingSegmentId(
        handle.createQuery(
            StringUtils.format(
                "SELECT payload FROM %s WHERE "
                + "dataSource = :dataSource AND "
                + "sequence_name = :sequence_name AND "
                + "sequence_prev_id = :sequence_prev_id",
                dbTables.getPendingSegmentsTable()
            )
        ),
        interval,
        sequenceName,
        previousSegmentIdNotNull,
        Pair.of("dataSource", dataSource),
        Pair.of("sequence_name", sequenceName),
        Pair.of("sequence_prev_id", previousSegmentIdNotNull)
    );

    if (result.found) {
      // The found existing segment identifier can be null if its interval doesn't match with the given interval
      return result.segmentIdentifier;
    }

    final SegmentIdWithShardSpec newIdentifier = createNewSegment(
        handle,
        dataSource,
        interval,
        partialShardSpec,
        maxVersion
    );
    if (newIdentifier == null) {
      return null;
    }

    // SELECT -> INSERT can fail due to races; callers must be prepared to retry.
    // Avoiding ON DUPLICATE KEY since it's not portable.
    // Avoiding try/catch since it may cause inadvertent transaction-splitting.

    // UNIQUE key for the row, ensuring sequences do not fork in two directions.
    // Using a single column instead of (sequence_name, sequence_prev_id) as some MySQL storage engines
    // have difficulty with large unique keys (see https://github.com/apache/druid/issues/2319)
    final String sequenceNamePrevIdSha1 = BaseEncoding.base16().encode(
        Hashing.sha1()
               .newHasher()
               .putBytes(StringUtils.toUtf8(sequenceName))
               .putByte((byte) 0xff)
               .putBytes(StringUtils.toUtf8(previousSegmentIdNotNull))
               .hash()
               .asBytes()
    );

    insertPendingSegmentIntoMetastore(
        handle,
        newIdentifier,
        dataSource,
        interval,
        previousSegmentIdNotNull,
        sequenceName,
        sequenceNamePrevIdSha1
    );
    return newIdentifier;
  }

  private Map<SegmentCreateRequest, SegmentIdWithShardSpec> allocatePendingSegments(
      final Handle handle,
      final String dataSource,
      final Interval interval,
      final boolean skipSegmentLineageCheck,
      final List<SegmentCreateRequest> requests
  ) throws IOException
  {
    final Map<SegmentCreateRequest, CheckExistingSegmentIdResult> existingSegmentIds;
    if (skipSegmentLineageCheck) {
      existingSegmentIds = getExistingSegmentIdsSkipLineageCheck(handle, dataSource, interval, requests);
    } else {
      existingSegmentIds = getExistingSegmentIdsWithLineageCheck(handle, dataSource, interval, requests);
    }

    // For every request see if a segment id already exists
    final Map<SegmentCreateRequest, SegmentIdWithShardSpec> allocatedSegmentIds = new HashMap<>();
    final List<SegmentCreateRequest> requestsForNewSegments = new ArrayList<>();
    for (SegmentCreateRequest request : requests) {
      CheckExistingSegmentIdResult existingSegmentId = existingSegmentIds.get(request);
      if (existingSegmentId == null || !existingSegmentId.found) {
        requestsForNewSegments.add(request);
      } else if (existingSegmentId.segmentIdentifier != null) {
        log.info("Found valid existing segment [%s] for request.", existingSegmentId.segmentIdentifier);
        allocatedSegmentIds.put(request, existingSegmentId.segmentIdentifier);
      } else {
        log.info("Found clashing existing segment [%s] for request.", existingSegmentId);
      }
    }

    // For each of the remaining requests, create a new segment
    final Map<SegmentCreateRequest, SegmentIdWithShardSpec> createdSegments =
        createNewSegments(handle, dataSource, interval, skipSegmentLineageCheck, requestsForNewSegments);

    // SELECT -> INSERT can fail due to races; callers must be prepared to retry.
    // Avoiding ON DUPLICATE KEY since it's not portable.
    // Avoiding try/catch since it may cause inadvertent transaction-splitting.

    // UNIQUE key for the row, ensuring we don't have more than one segment per sequence per interval.
    // Using a single column instead of (sequence_name, sequence_prev_id) as some MySQL storage engines
    // have difficulty with large unique keys (see https://github.com/apache/druid/issues/2319)
    insertPendingSegmentsIntoMetastore(
        handle,
        createdSegments,
        dataSource,
        interval,
        skipSegmentLineageCheck
    );

    allocatedSegmentIds.putAll(createdSegments);
    return allocatedSegmentIds;
  }

  @SuppressWarnings("UnstableApiUsage")
  private String getSequenceNameAndPrevIdSha(
      SegmentCreateRequest request,
      Interval interval,
      boolean skipSegmentLineageCheck
  )
  {
    final Hasher hasher = Hashing.sha1().newHasher()
                                 .putBytes(StringUtils.toUtf8(request.getSequenceName()))
                                 .putByte((byte) 0xff);
    if (skipSegmentLineageCheck) {
      hasher
          .putLong(interval.getStartMillis())
          .putLong(interval.getEndMillis());
    } else {
      hasher
          .putBytes(StringUtils.toUtf8(request.getPreviousSegmentId()));
    }

    return BaseEncoding.base16().encode(hasher.hash().asBytes());
  }

  @Nullable
  private SegmentIdWithShardSpec allocatePendingSegment(
      final Handle handle,
      final String dataSource,
      final String sequenceName,
      final Interval interval,
      final PartialShardSpec partialShardSpec,
      final String maxVersion
  ) throws IOException
  {
    final CheckExistingSegmentIdResult result = checkAndGetExistingSegmentId(
        handle.createQuery(
            StringUtils.format(
                "SELECT payload FROM %s WHERE "
                + "dataSource = :dataSource AND "
                + "sequence_name = :sequence_name AND "
                + "start = :start AND "
                + "%2$send%2$s = :end",
                dbTables.getPendingSegmentsTable(),
                connector.getQuoteString()
            )
        ),
        interval,
        sequenceName,
        null,
        Pair.of("dataSource", dataSource),
        Pair.of("sequence_name", sequenceName),
        Pair.of("start", interval.getStart().toString()),
        Pair.of("end", interval.getEnd().toString())
    );

    if (result.found) {
      return result.segmentIdentifier;
    }

    final SegmentIdWithShardSpec newIdentifier = createNewSegment(
        handle,
        dataSource,
        interval,
        partialShardSpec,
        maxVersion
    );
    if (newIdentifier == null) {
      return null;
    }

    // SELECT -> INSERT can fail due to races; callers must be prepared to retry.
    // Avoiding ON DUPLICATE KEY since it's not portable.
    // Avoiding try/catch since it may cause inadvertent transaction-splitting.

    // UNIQUE key for the row, ensuring we don't have more than one segment per sequence per interval.
    // Using a single column instead of (sequence_name, sequence_prev_id) as some MySQL storage engines
    // have difficulty with large unique keys (see https://github.com/apache/druid/issues/2319)
    final String sequenceNamePrevIdSha1 = BaseEncoding.base16().encode(
        Hashing.sha1()
               .newHasher()
               .putBytes(StringUtils.toUtf8(sequenceName))
               .putByte((byte) 0xff)
               .putLong(interval.getStartMillis())
               .putLong(interval.getEndMillis())
               .hash()
               .asBytes()
    );

    // always insert empty previous sequence id
    insertPendingSegmentIntoMetastore(handle, newIdentifier, dataSource, interval, "", sequenceName, sequenceNamePrevIdSha1);

    log.info("Allocated pending segment [%s] for sequence[%s] in DB", newIdentifier, sequenceName);

    return newIdentifier;
  }

  /**
   * Returns a map from sequenceName to segment id.
   */
  private Map<SegmentCreateRequest, CheckExistingSegmentIdResult> getExistingSegmentIdsSkipLineageCheck(
      Handle handle,
      String dataSource,
      Interval interval,
      List<SegmentCreateRequest> requests
  ) throws IOException
  {
    final Query<Map<String, Object>> query = handle
        .createQuery(
            StringUtils.format(
                "SELECT sequence_name, payload "
                + "FROM %s WHERE "
                + "dataSource = :dataSource AND "
                + "start = :start AND "
                + "%2$send%2$s = :end",
                dbTables.getPendingSegmentsTable(),
                connector.getQuoteString()
            )
        )
        .bind("dataSource", dataSource)
        .bind("start", interval.getStart().toString())
        .bind("end", interval.getEnd().toString());

    final ResultIterator<PendingSegmentsRecord> dbSegments = query
        .map((index, r, ctx) -> PendingSegmentsRecord.fromResultSet(r))
        .iterator();

    // Map from sequenceName to segment id
    final Map<String, SegmentIdWithShardSpec> sequenceToSegmentId = new HashMap<>();
    while (dbSegments.hasNext()) {
      final PendingSegmentsRecord record = dbSegments.next();
      final SegmentIdWithShardSpec segmentId =
          jsonMapper.readValue(record.getPayload(), SegmentIdWithShardSpec.class);
      sequenceToSegmentId.put(record.getSequenceName(), segmentId);
    }

    final Map<SegmentCreateRequest, CheckExistingSegmentIdResult> requestToResult = new HashMap<>();
    for (SegmentCreateRequest request : requests) {
      SegmentIdWithShardSpec segmentId = sequenceToSegmentId.get(request.getSequenceName());
      requestToResult.put(request, new CheckExistingSegmentIdResult(segmentId != null, segmentId));
    }

    return requestToResult;
  }

  /**
   * Returns a map from sequenceName to segment id.
   */
  private Map<SegmentCreateRequest, CheckExistingSegmentIdResult> getExistingSegmentIdsWithLineageCheck(
      Handle handle,
      String dataSource,
      Interval interval,
      List<SegmentCreateRequest> requests
  ) throws IOException
  {
    // This cannot be batched because there doesn't seem to be a clean option:
    // 1. WHERE must have sequence_name and sequence_prev_id but not start or end.
    //    (sequence columns are used to find the matching segment whereas start and
    //    end are used to determine if the found segment is valid or not)
    // 2. IN filters on sequence_name and sequence_prev_id might perform worse than individual SELECTs?
    // 3. IN filter on sequence_name alone might be a feasible option worth evaluating
    final String sql = StringUtils.format(
        "SELECT payload FROM %s WHERE "
        + "dataSource = :dataSource AND "
        + "sequence_name = :sequence_name AND "
        + "sequence_prev_id = :sequence_prev_id",
        dbTables.getPendingSegmentsTable()
    );

    final Map<SegmentCreateRequest, CheckExistingSegmentIdResult> requestToResult = new HashMap<>();
    for (SegmentCreateRequest request : requests) {
      CheckExistingSegmentIdResult result = checkAndGetExistingSegmentId(
          handle.createQuery(sql)
                .bind("dataSource", dataSource)
                .bind("sequence_name", request.getSequenceName())
                .bind("sequence_prev_id", request.getPreviousSegmentId()),
          interval,
          request.getSequenceName(),
          request.getPreviousSegmentId()
      );
      requestToResult.put(request, result);
    }

    return requestToResult;
  }

  private CheckExistingSegmentIdResult checkAndGetExistingSegmentId(
      final Query<Map<String, Object>> query,
      final Interval interval,
      final String sequenceName,
      final @Nullable String previousSegmentId,
      final Pair<String, String>... queryVars
  ) throws IOException
  {
    Query<Map<String, Object>> boundQuery = query;
    for (Pair<String, String> var : queryVars) {
      boundQuery = boundQuery.bind(var.lhs, var.rhs);
    }
    final List<byte[]> existingBytes = boundQuery.map(ByteArrayMapper.FIRST).list();

    if (existingBytes.isEmpty()) {
      return new CheckExistingSegmentIdResult(false, null);
    } else {
      final SegmentIdWithShardSpec existingIdentifier = jsonMapper.readValue(
          Iterables.getOnlyElement(existingBytes),
          SegmentIdWithShardSpec.class
      );

      if (existingIdentifier.getInterval().isEqual(interval)) {
        log.info(
            "Found existing pending segment [%s] for sequence[%s] (previous = [%s]) in DB",
            existingIdentifier,
            sequenceName,
            previousSegmentId
        );

        return new CheckExistingSegmentIdResult(true, existingIdentifier);
      } else {
        log.warn(
            "Cannot use existing pending segment [%s] for sequence[%s] (previous = [%s]) in DB, "
            + "does not match requested interval[%s]",
            existingIdentifier,
            sequenceName,
            previousSegmentId,
            interval
        );

        return new CheckExistingSegmentIdResult(true, null);
      }
    }
  }

  private static class CheckExistingSegmentIdResult
  {
    private final boolean found;
    @Nullable
    private final SegmentIdWithShardSpec segmentIdentifier;

    CheckExistingSegmentIdResult(boolean found, @Nullable SegmentIdWithShardSpec segmentIdentifier)
    {
      this.found = found;
      this.segmentIdentifier = segmentIdentifier;
    }
  }

  private void insertPendingSegmentsIntoMetastore(
      Handle handle,
      Map<SegmentCreateRequest, SegmentIdWithShardSpec> createdSegments,
      String dataSource,
      Interval interval,
      boolean skipSegmentLineageCheck
  ) throws JsonProcessingException
  {
    final PreparedBatch insertBatch = handle.prepareBatch(
        StringUtils.format(
        "INSERT INTO %1$s (id, dataSource, created_date, start, %2$send%2$s, sequence_name, sequence_prev_id, "
        + "sequence_name_prev_id_sha1, payload) "
        + "VALUES (:id, :dataSource, :created_date, :start, :end, :sequence_name, :sequence_prev_id, "
        + ":sequence_name_prev_id_sha1, :payload)",
        dbTables.getPendingSegmentsTable(),
        connector.getQuoteString()
    ));

    // Deduplicate the segment ids by inverting the map
    Map<SegmentIdWithShardSpec, SegmentCreateRequest> segmentIdToRequest = new HashMap<>();
    createdSegments.forEach((request, segmentId) -> segmentIdToRequest.put(segmentId, request));

    for (Map.Entry<SegmentIdWithShardSpec, SegmentCreateRequest> entry : segmentIdToRequest.entrySet()) {
      final SegmentCreateRequest request = entry.getValue();
      final SegmentIdWithShardSpec segmentId = entry.getKey();
      insertBatch.add()
                 .bind("id", segmentId.toString())
                 .bind("dataSource", dataSource)
                 .bind("created_date", DateTimes.nowUtc().toString())
                 .bind("start", interval.getStart().toString())
                 .bind("end", interval.getEnd().toString())
                 .bind("sequence_name", request.getSequenceName())
                 .bind("sequence_prev_id", request.getPreviousSegmentId())
                 .bind(
                     "sequence_name_prev_id_sha1",
                     getSequenceNameAndPrevIdSha(request, interval, skipSegmentLineageCheck)
                 )
                 .bind("payload", jsonMapper.writeValueAsBytes(segmentId));
    }
    insertBatch.execute();
  }

  private void insertPendingSegmentIntoMetastore(
      Handle handle,
      SegmentIdWithShardSpec newIdentifier,
      String dataSource,
      Interval interval,
      String previousSegmentId,
      String sequenceName,
      String sequenceNamePrevIdSha1
  ) throws JsonProcessingException
  {
    handle.createStatement(
        StringUtils.format(
            "INSERT INTO %1$s (id, dataSource, created_date, start, %2$send%2$s, sequence_name, sequence_prev_id, "
            + "sequence_name_prev_id_sha1, payload) "
            + "VALUES (:id, :dataSource, :created_date, :start, :end, :sequence_name, :sequence_prev_id, "
            + ":sequence_name_prev_id_sha1, :payload)",
            dbTables.getPendingSegmentsTable(),
            connector.getQuoteString()
        )
    )
          .bind("id", newIdentifier.toString())
          .bind("dataSource", dataSource)
          .bind("created_date", DateTimes.nowUtc().toString())
          .bind("start", interval.getStart().toString())
          .bind("end", interval.getEnd().toString())
          .bind("sequence_name", sequenceName)
          .bind("sequence_prev_id", previousSegmentId)
          .bind("sequence_name_prev_id_sha1", sequenceNamePrevIdSha1)
          .bind("payload", jsonMapper.writeValueAsBytes(newIdentifier))
          .execute();
  }

  @VisibleForTesting
  Map<DataSegment, Set<SegmentIdWithShardSpec>> allocateNewSegmentIds(
      Handle handle,
      String dataSource,
      Set<DataSegment> segments
  ) throws IOException
  {
    if (segments.isEmpty()) {
      return Collections.emptyMap();
    }

    // Map from version to used committed segments
    Map<String, Set<DataSegment>> versionToSegments = new HashMap<>();
    Map<String, Set<Interval>> versionToIntervals = new HashMap<>();
    Collection<Interval> segmentIntervals = segments.stream()
                                                    .map(DataSegment::getInterval)
                                                    .collect(Collectors.toSet());
    try (final CloseableIterator<DataSegment> iterator =
             SqlSegmentsMetadataQuery.forHandle(handle, connector, dbTables, jsonMapper)
                                     .retrieveUsedSegments(
                                         dataSource,
                                         segmentIntervals
                                     )
    ) {
      while (iterator.hasNext()) {
        final DataSegment segment = iterator.next();
        versionToSegments.computeIfAbsent(segment.getVersion(), v -> new HashSet<>())
                         .add(segment);
        versionToIntervals.computeIfAbsent(segment.getVersion(), v -> new HashSet<>())
                         .add(segment.getInterval());
      }
    }

    // Maps segment to its new metadata with higher versions
    Map<DataSegment, Set<SegmentIdWithShardSpec>> retVal = new HashMap<>();
    for (DataSegment segment : segments) {
      retVal.put(segment, new HashSet<>());
    }

    for (final String version : versionToSegments.keySet()) {
      Set<DataSegment> lowerVersionSegments = new HashSet<>();
      for (final DataSegment segment : segments) {
        if (segment.getVersion().compareTo(version) < 0) {
          for (final Interval interval : versionToIntervals.get(version)) {
            if (interval.overlaps(segment.getInterval())) {
              lowerVersionSegments.add(segment);
              break;
            }
          }
        }
      }

      Map<Interval, Set<DataSegment>> intervalToSegments = new HashMap<>();
      for (DataSegment segment : lowerVersionSegments) {
        for (Interval interval : intervalToSegments.keySet()) {
          if (interval.contains(segment.getInterval())) {
            intervalToSegments.get(interval).add(segment);
            break;
          }
        }
        Collection<DataSegment> overlappingSegments = retrieveUsedSegmentsForIntervals(
            segment.getDataSource(),
            ImmutableList.of(segment.getInterval()),
            Segments.ONLY_VISIBLE
        );
        for (DataSegment overlappingSegment : overlappingSegments) {
          if (overlappingSegment.getInterval().contains(segment.getInterval())) {
            intervalToSegments.computeIfAbsent(overlappingSegment.getInterval(), itvl -> new HashSet<>())
                              .add(segment);
          } else {
            throw new ISE(
                "Existing segment interval[%s] conflicts with that of the new segment[%s]",
                overlappingSegment.getInterval(),
                segment.getInterval()
            );
          }
        }
      }

      for (Interval interval : intervalToSegments.keySet()) {
        Set<SegmentIdWithShardSpec> pendingSegments = new HashSet<>(
            getPendingSegmentsForIntervalWithHandle(handle, dataSource, interval)
        );
        Collection<DataSegment> committedSegments = retrieveAllUsedSegmentsForIntervalsWithHandle(
            handle,
            dataSource,
            ImmutableList.of(interval)
        )
            .stream()
            .filter(s -> s.getVersion().equals(version) && s.getInterval().equals(interval))
            .collect(Collectors.toSet());
        SegmentIdWithShardSpec committedMaxId = null;
        for (DataSegment committedSegment : committedSegments) {
          if (committedMaxId == null
              || committedMaxId.getShardSpec().getPartitionNum() < committedSegment.getShardSpec().getPartitionNum()) {
            committedMaxId = SegmentIdWithShardSpec.fromDataSegment(committedSegment);
          }
        }
        for (DataSegment segment : intervalToSegments.get(interval)) {
          SegmentCreateRequest request = new SegmentCreateRequest(
              segment.getId() + version,
              null,
              version,
              NumberedPartialShardSpec.instance()
          );
          SegmentIdWithShardSpec newId = createNewSegment(
              request,
              dataSource,
              interval,
              version,
              committedMaxId,
              pendingSegments
          );
          pendingSegments.add(newId);
          retVal.get(segment).add(newId);
        }
      }
    }
    return retVal;
  }

  private Map<SegmentCreateRequest, SegmentIdWithShardSpec> createNewSegments(
      Handle handle,
      String dataSource,
      Interval interval,
      boolean skipSegmentLineageCheck,
      List<SegmentCreateRequest> requests
  ) throws IOException
  {
    if (requests.isEmpty()) {
      return Collections.emptyMap();
    }

    // Get the time chunk and associated data segments for the given interval, if any
    final List<TimelineObjectHolder<String, DataSegment>> existingChunks =
        getTimelineForIntervalsWithHandle(handle, dataSource, Collections.singletonList(interval))
            .lookup(interval);

    if (existingChunks.size() > 1) {
      // Not possible to expand more than one chunk with a single segment.
      log.warn(
          "Cannot allocate new segments for dataSource[%s], interval[%s]: already have [%,d] chunks.",
          dataSource,
          interval,
          existingChunks.size()
      );
      return Collections.emptyMap();
    }

    // Shard spec of any of the requests (as they are all compatible) can be used to
    // identify existing shard specs that share partition space with the requested ones.
    final PartialShardSpec partialShardSpec = requests.get(0).getPartialShardSpec();

    // max partitionId of published data segments which share the same partition space.
    SegmentIdWithShardSpec committedMaxId = null;

    @Nullable
    final String versionOfExistingChunk;
    if (existingChunks.isEmpty()) {
      versionOfExistingChunk = null;
    } else {
      TimelineObjectHolder<String, DataSegment> existingHolder = Iterables.getOnlyElement(existingChunks);
      versionOfExistingChunk = existingHolder.getVersion();

      // Don't use the stream API for performance.
      for (DataSegment segment : FluentIterable
          .from(existingHolder.getObject())
          .transform(PartitionChunk::getObject)
          // Here we check only the segments of the shardSpec which shares the same partition space with the given
          // partialShardSpec. Note that OverwriteShardSpec doesn't share the partition space with others.
          // See PartitionIds.
          .filter(segment -> segment.getShardSpec().sharePartitionSpace(partialShardSpec))) {
        if (committedMaxId == null
            || committedMaxId.getShardSpec().getPartitionNum() < segment.getShardSpec().getPartitionNum()) {
          committedMaxId = SegmentIdWithShardSpec.fromDataSegment(segment);
        }
      }
    }


    // Fetch the pending segments for this interval to determine max partitionId
    // across all shard specs (published + pending).
    // A pending segment having a higher partitionId must also be considered
    // to avoid clashes when inserting the pending segment created here.
    final Set<SegmentIdWithShardSpec> pendingSegments =
        getPendingSegmentsForIntervalWithHandle(handle, dataSource, interval);

    final Map<SegmentCreateRequest, SegmentIdWithShardSpec> createdSegments = new HashMap<>();
    final Map<String, SegmentIdWithShardSpec> sequenceHashToSegment = new HashMap<>();

    for (SegmentCreateRequest request : requests) {
      // Check if the required segment has already been created in this batch
      final String sequenceHash = getSequenceNameAndPrevIdSha(request, interval, skipSegmentLineageCheck);

      final SegmentIdWithShardSpec createdSegment;
      if (sequenceHashToSegment.containsKey(sequenceHash)) {
        createdSegment = sequenceHashToSegment.get(sequenceHash);
      } else {
        createdSegment = createNewSegment(
            request,
            dataSource,
            interval,
            versionOfExistingChunk,
            committedMaxId,
            pendingSegments
        );

        // Add to pendingSegments to consider for partitionId
        if (createdSegment != null) {
          pendingSegments.add(createdSegment);
          sequenceHashToSegment.put(sequenceHash, createdSegment);
          log.info("Created new segment [%s]", createdSegment);
        }
      }

      if (createdSegment != null) {
        createdSegments.put(request, createdSegment);
      }
    }

    log.info("Created [%d] new segments for [%d] allocate requests.", sequenceHashToSegment.size(), requests.size());
    return createdSegments;
  }

  private SegmentIdWithShardSpec createNewSegment(
      SegmentCreateRequest request,
      String dataSource,
      Interval interval,
      String versionOfExistingChunk,
      SegmentIdWithShardSpec committedMaxId,
      Set<SegmentIdWithShardSpec> pendingSegments
  )
  {
    final PartialShardSpec partialShardSpec = request.getPartialShardSpec();
    final String existingVersion = request.getVersion();

    // Include the committedMaxId while computing the overallMaxId
    if (committedMaxId != null) {
      pendingSegments.add(committedMaxId);
    }

    // If there is an existing chunk, find the max id with the same version as the existing chunk.
    // There may still be a pending segment with a higher version (but no corresponding used segments)
    // which may generate a clash with an existing segment once the new id is generated
    final SegmentIdWithShardSpec overallMaxId =
        pendingSegments.stream()
                       .filter(id -> id.getShardSpec().sharePartitionSpace(partialShardSpec))
                       .filter(id -> versionOfExistingChunk == null
                                     || id.getVersion().equals(versionOfExistingChunk))
                       .max(Comparator.comparing(SegmentIdWithShardSpec::getVersion)
                                      .thenComparing(id -> id.getShardSpec().getPartitionNum()))
                       .orElse(null);

    // Determine the version of the new segment
    final String newSegmentVersion;
    if (versionOfExistingChunk != null) {
      newSegmentVersion = versionOfExistingChunk;
    } else if (overallMaxId != null) {
      newSegmentVersion = overallMaxId.getVersion();
    } else {
      // this is the first segment for this interval
      newSegmentVersion = null;
    }

    if (overallMaxId == null) {
      // When appending segments, null overallMaxId means that we are allocating the very initial
      // segment for this time chunk.
      // This code is executed when the Overlord coordinates segment allocation, which is either you append segments
      // or you use segment lock. Since the core partitions set is not determined for appended segments, we set
      // it 0. When you use segment lock, the core partitions set doesn't work with it. We simply set it 0 so that the
      // OvershadowableManager handles the atomic segment update.
      final int newPartitionId = partialShardSpec.useNonRootGenerationPartitionSpace()
                                 ? PartitionIds.NON_ROOT_GEN_START_PARTITION_ID
                                 : PartitionIds.ROOT_GEN_START_PARTITION_ID;

      String version = newSegmentVersion == null ? existingVersion : newSegmentVersion;
      return new SegmentIdWithShardSpec(
          dataSource,
          interval,
          version,
          partialShardSpec.complete(jsonMapper, newPartitionId, 0)
      );

    } else if (!overallMaxId.getInterval().equals(interval)) {
      log.warn(
          "Cannot allocate new segment for dataSource[%s], interval[%s], existingVersion[%s]: conflicting segment[%s].",
          dataSource,
          interval,
          existingVersion,
          overallMaxId
      );
      return null;
    } else if (committedMaxId != null
               && committedMaxId.getShardSpec().getNumCorePartitions()
                  == SingleDimensionShardSpec.UNKNOWN_NUM_CORE_PARTITIONS) {
      log.warn(
          "Cannot allocate new segment because of unknown core partition size of segment[%s], shardSpec[%s]",
          committedMaxId,
          committedMaxId.getShardSpec()
      );
      return null;
    } else {
      // The number of core partitions must always be chosen from the set of used segments in the SegmentTimeline.
      // When the core partitions have been dropped, using pending segments may lead to an incorrect state
      // where the chunk is believed to have core partitions and queries results are incorrect.

      return new SegmentIdWithShardSpec(
          dataSource,
          interval,
          Preconditions.checkNotNull(newSegmentVersion, "newSegmentVersion"),
          partialShardSpec.complete(
              jsonMapper,
              overallMaxId.getShardSpec().getPartitionNum() + 1,
              committedMaxId == null ? 0 : committedMaxId.getShardSpec().getNumCorePartitions()
          )
      );
    }
  }

  /**
   * This function creates a new segment for the given datasource/interval/etc. A critical
   * aspect of the creation is to make sure that the new version & new partition number will make
   * sense given the existing segments & pending segments also very important is to avoid
   * clashes with existing pending & used/unused segments.
   * @param handle Database handle
   * @param dataSource datasource for the new segment
   * @param interval interval for the new segment
   * @param partialShardSpec Shard spec info minus segment id stuff
   * @param existingVersion Version of segments in interval, used to compute the version of the very first segment in
   *                        interval
   * @return
   * @throws IOException
   */
  @Nullable
  private SegmentIdWithShardSpec createNewSegment(
      final Handle handle,
      final String dataSource,
      final Interval interval,
      final PartialShardSpec partialShardSpec,
      final String existingVersion
  ) throws IOException
  {
    // Get the time chunk and associated data segments for the given interval, if any
    final List<TimelineObjectHolder<String, DataSegment>> existingChunks = getTimelineForIntervalsWithHandle(
        handle,
        dataSource,
        ImmutableList.of(interval)
    ).lookup(interval);

    if (existingChunks.size() > 1) {
      // Not possible to expand more than one chunk with a single segment.
      log.warn(
          "Cannot allocate new segment for dataSource[%s], interval[%s]: already have [%,d] chunks.",
          dataSource,
          interval,
          existingChunks.size()
      );
      return null;

    } else {
      // max partitionId of published data segments which share the same partition space.
      SegmentIdWithShardSpec committedMaxId = null;

      @Nullable
      final String versionOfExistingChunk;
      if (existingChunks.isEmpty()) {
        versionOfExistingChunk = null;
      } else {
        TimelineObjectHolder<String, DataSegment> existingHolder = Iterables.getOnlyElement(existingChunks);
        versionOfExistingChunk = existingHolder.getVersion();

        // Don't use the stream API for performance.
        for (DataSegment segment : FluentIterable
            .from(existingHolder.getObject())
            .transform(PartitionChunk::getObject)
            // Here we check only the segments of the shardSpec which shares the same partition space with the given
            // partialShardSpec. Note that OverwriteShardSpec doesn't share the partition space with others.
            // See PartitionIds.
            .filter(segment -> segment.getShardSpec().sharePartitionSpace(partialShardSpec))) {
          if (committedMaxId == null
              || committedMaxId.getShardSpec().getPartitionNum() < segment.getShardSpec().getPartitionNum()) {
            committedMaxId = SegmentIdWithShardSpec.fromDataSegment(segment);
          }
        }
      }


      // Fetch the pending segments for this interval to determine max partitionId
      // across all shard specs (published + pending).
      // A pending segment having a higher partitionId must also be considered
      // to avoid clashes when inserting the pending segment created here.
      final Set<SegmentIdWithShardSpec> pendings = getPendingSegmentsForIntervalWithHandle(
          handle,
          dataSource,
          interval
      );
      if (committedMaxId != null) {
        pendings.add(committedMaxId);
      }

      // If there is an existing chunk, find the max id with the same version as the existing chunk.
      // There may still be a pending segment with a higher version (but no corresponding used segments)
      // which may generate a clash with an existing segment once the new id is generated
      final SegmentIdWithShardSpec overallMaxId;
      overallMaxId = pendings.stream()
                             .filter(id -> id.getShardSpec().sharePartitionSpace(partialShardSpec))
                             .filter(id -> versionOfExistingChunk == null
                                           || id.getVersion().equals(versionOfExistingChunk))
                             .max(Comparator.comparing(SegmentIdWithShardSpec::getVersion)
                                            .thenComparing(id -> id.getShardSpec().getPartitionNum()))
                             .orElse(null);


      // Determine the version of the new segment
      final String newSegmentVersion;
      if (versionOfExistingChunk != null) {
        newSegmentVersion = versionOfExistingChunk;
      } else if (overallMaxId != null) {
        newSegmentVersion = overallMaxId.getVersion();
      } else {
        // this is the first segment for this interval
        newSegmentVersion = null;
      }

      if (overallMaxId == null) {
        // When appending segments, null overallMaxId means that we are allocating the very initial
        // segment for this time chunk.
        // This code is executed when the Overlord coordinates segment allocation, which is either you append segments
        // or you use segment lock. Since the core partitions set is not determined for appended segments, we set
        // it 0. When you use segment lock, the core partitions set doesn't work with it. We simply set it 0 so that the
        // OvershadowableManager handles the atomic segment update.
        final int newPartitionId = partialShardSpec.useNonRootGenerationPartitionSpace()
                                   ? PartitionIds.NON_ROOT_GEN_START_PARTITION_ID
                                   : PartitionIds.ROOT_GEN_START_PARTITION_ID;
        String version = newSegmentVersion == null ? existingVersion : newSegmentVersion;
        return new SegmentIdWithShardSpec(
            dataSource,
            interval,
            version,
            partialShardSpec.complete(jsonMapper, newPartitionId, 0)
        );
      } else if (!overallMaxId.getInterval().equals(interval)) {
        log.warn(
            "Cannot allocate new segment for dataSource[%s], interval[%s], existingVersion[%s]: conflicting segment[%s].",
            dataSource,
            interval,
            existingVersion,
            overallMaxId
        );
        return null;
      } else if (committedMaxId != null
                 && committedMaxId.getShardSpec().getNumCorePartitions()
                    == SingleDimensionShardSpec.UNKNOWN_NUM_CORE_PARTITIONS) {
        log.warn(
            "Cannot allocate new segment because of unknown core partition size of segment[%s], shardSpec[%s]",
            committedMaxId,
            committedMaxId.getShardSpec()
        );
        return null;
      } else {
        // The number of core partitions must always be chosen from the set of used segments in the SegmentTimeline.
        // When the core partitions have been dropped, using pending segments may lead to an incorrect state
        // where the chunk is believed to have core partitions and queries results are incorrect.

        return new SegmentIdWithShardSpec(
            dataSource,
            interval,
            Preconditions.checkNotNull(newSegmentVersion, "newSegmentVersion"),
            partialShardSpec.complete(
                jsonMapper,
                overallMaxId.getShardSpec().getPartitionNum() + 1,
                committedMaxId == null ? 0 : committedMaxId.getShardSpec().getNumCorePartitions()
            )
        );
      }
    }
  }

  @Override
  public int deletePendingSegmentsCreatedInInterval(String dataSource, Interval deleteInterval)
  {
    return connector.getDBI().inTransaction(
        (handle, status) -> handle
            .createStatement(
                StringUtils.format(
                    "DELETE FROM %s WHERE datasource = :dataSource AND created_date >= :start AND created_date < :end",
                    dbTables.getPendingSegmentsTable()
                )
            )
            .bind("dataSource", dataSource)
            .bind("start", deleteInterval.getStart().toString())
            .bind("end", deleteInterval.getEnd().toString())
            .execute()
    );
  }

  @Override
  public int deletePendingSegments(String dataSource)
  {
    return connector.getDBI().inTransaction(
        (handle, status) -> handle
            .createStatement(
                StringUtils.format("DELETE FROM %s WHERE datasource = :dataSource", dbTables.getPendingSegmentsTable())
            )
            .bind("dataSource", dataSource)
            .execute()
    );
  }

  private Set<DataSegment> announceHistoricalSegmentBatch(
      final Handle handle,
      final Set<DataSegment> segments,
      final Set<DataSegment> usedSegments
  ) throws IOException
  {
    final Set<DataSegment> toInsertSegments = new HashSet<>();
    try {
      Set<String> existedSegments = segmentExistsBatch(handle, segments);
      log.info("Found these segments already exist in DB: %s", existedSegments);
      for (DataSegment segment : segments) {
        if (!existedSegments.contains(segment.getId().toString())) {
          toInsertSegments.add(segment);
        }
      }

      // SELECT -> INSERT can fail due to races; callers must be prepared to retry.
      // Avoiding ON DUPLICATE KEY since it's not portable.
      // Avoiding try/catch since it may cause inadvertent transaction-splitting.
      final List<List<DataSegment>> partitionedSegments = Lists.partition(
          new ArrayList<>(toInsertSegments),
          MAX_NUM_SEGMENTS_TO_ANNOUNCE_AT_ONCE
      );

      PreparedBatch preparedBatch = handle.prepareBatch(insertSegmentQuery);
      for (List<DataSegment> partition : partitionedSegments) {
        for (DataSegment segment : partition) {
          final String now = DateTimes.nowUtc().toString();
          preparedBatch.add()
                       .bind("id", segment.getId().toString())
                       .bind("dataSource", segment.getDataSource())
                       .bind("created_date", now)
                       .bind("start", segment.getInterval().getStart().toString())
                       .bind("end", segment.getInterval().getEnd().toString())
                       .bind("partitioned", (segment.getShardSpec() instanceof NoneShardSpec) ? false : true)
                       .bind("version", segment.getVersion())
                       .bind("used", usedSegments.contains(segment))
                       .bind("payload", jsonMapper.writeValueAsBytes(segment))
                       .bind("used_status_last_updated", now);
        }
        final int[] affectedRows = preparedBatch.execute();
        final boolean succeeded = Arrays.stream(affectedRows).allMatch(eachAffectedRows -> eachAffectedRows == 1);
        if (succeeded) {
          log.infoSegments(partition, "Published segments to DB");
        } else {
          final List<DataSegment> failedToPublish = IntStream.range(0, partition.size())
                                                             .filter(i -> affectedRows[i] != 1)
                                                             .mapToObj(partition::get)
                                                             .collect(Collectors.toList());
          throw new ISE(
              "Failed to publish segments to DB: %s",
              SegmentUtils.commaSeparatedIdentifiers(failedToPublish)
          );
        }
      }
    }
    catch (Exception e) {
      log.errorSegments(segments, "Exception inserting segments");
      throw e;
    }

    return toInsertSegments;
  }

  private Set<DataSegment> commitReplaceSegmentBatch(
      final Handle handle,
      final Set<DataSegment> segments,
      @Nullable Set<TaskLockInfo> replaceLocks
  ) throws IOException
  {
    final Set<DataSegment> toInsertSegments = new HashSet<>();
    try {
      Set<String> existedSegments = segmentExistsBatch(handle, segments);
      log.info("Found these segments already exist in DB: %s", existedSegments);
      for (DataSegment segment : segments) {
        if (!existedSegments.contains(segment.getId().toString())) {
          toInsertSegments.add(segment);
        }
      }

      // SELECT -> INSERT can fail due to races; callers must be prepared to retry.
      // Avoiding ON DUPLICATE KEY since it's not portable.
      // Avoiding try/catch since it may cause inadvertent transaction-splitting.
      final List<List<DataSegment>> partitionedSegments = Lists.partition(
          new ArrayList<>(toInsertSegments),
          MAX_NUM_SEGMENTS_TO_ANNOUNCE_AT_ONCE
      );

      PreparedBatch preparedBatch = handle.prepareBatch(insertSegmentQuery);
      for (List<DataSegment> partition : partitionedSegments) {
        for (DataSegment segment : partition) {
          final String now = DateTimes.nowUtc().toString();
          preparedBatch.add()
                       .bind("id", segment.getId().toString())
                       .bind("dataSource", segment.getDataSource())
                       .bind("created_date", now)
                       .bind("start", segment.getInterval().getStart().toString())
                       .bind("end", segment.getInterval().getEnd().toString())
                       .bind("partitioned", (segment.getShardSpec() instanceof NoneShardSpec) ? false : true)
                       .bind("version", segment.getVersion())
                       .bind("used", true)
                       .bind("payload", jsonMapper.writeValueAsBytes(segment))
                       .bind("used_status_last_updated", now);
        }
        final int[] affectedInsertRows = preparedBatch.execute();

        final boolean succeeded = Arrays.stream(affectedInsertRows).allMatch(eachAffectedRow -> eachAffectedRow == 1);
        if (succeeded) {
          log.infoSegments(partition, "Published segments to DB");
        } else {
          final List<DataSegment> failedToPublish = IntStream.range(0, partition.size())
                                                             .filter(i -> affectedInsertRows[i] != 1)
                                                             .mapToObj(partition::get)
                                                             .collect(Collectors.toList());
          throw new ISE(
              "Failed to publish segments to DB: %s",
              SegmentUtils.commaSeparatedIdentifiers(failedToPublish)
          );
        }
      }

      Map<String, TaskLockInfo> segmentsToBeForwarded = getAppendedSegmentIds(
          handle,
          segments.iterator().next().getDataSource(),
          replaceLocks
      );
      Map<Interval, Integer> intervalToCorePartition = new HashMap<>();
      Map<Interval, Integer> intervalToPartitionNum = new HashMap<>();
      for (DataSegment segment : toInsertSegments) {
        intervalToCorePartition.put(segment.getInterval(), segment.getShardSpec().getNumCorePartitions());
        intervalToPartitionNum.putIfAbsent(segment.getInterval(), 0);
        int maxPartitionNum = Integer.max(
            intervalToPartitionNum.get(segment.getInterval()),
            segment.getShardSpec().getPartitionNum()
        );
        intervalToPartitionNum.put(segment.getInterval(), maxPartitionNum);
      }
      final List<List<Map.Entry<String, TaskLockInfo>>> forwardSegmentsBatch = Lists.partition(
          new ArrayList<>(segmentsToBeForwarded.entrySet()),
          MAX_NUM_SEGMENTS_TO_ANNOUNCE_AT_ONCE
      );
      for (List<Map.Entry<String, TaskLockInfo>> batch : forwardSegmentsBatch) {
        Map<String, TaskLockInfo> batchMap = new HashMap<>();
        for (Map.Entry<String, TaskLockInfo> entry : batch) {
          batchMap.put(entry.getKey(), entry.getValue());
        }
        List<DataSegment> oldSegments = retrieveSegmentsById(handle, batchMap.keySet());
        for (DataSegment oldSegment : oldSegments) {
          Interval newInterval = oldSegment.getInterval();
          for (DataSegment segment : toInsertSegments) {
            if (segment.getInterval().overlaps(newInterval)) {
              if (segment.getInterval().contains(newInterval)) {
                newInterval = segment.getInterval();
              } else {
                throw new ISE("Incompatible segment intervals for commit: [%s] and [%s].",
                              newInterval,
                              segment.getInterval()
                );
              }
            }
          }
          TaskLockInfo lock = batchMap.get(oldSegment.getId().toString());
          final int partitionNum = intervalToPartitionNum.get(newInterval) + 1;
          final int numCorePartitions = intervalToCorePartition.get(newInterval);
          ShardSpec shardSpec = new NumberedShardSpec(partitionNum, numCorePartitions);
          intervalToPartitionNum.put(newInterval, partitionNum);
          DataSegment newSegment = new DataSegment(
              oldSegment.getDataSource(),
              newInterval,
              lock.getVersion(),
              oldSegment.getLoadSpec(),
              oldSegment.getDimensions(),
              oldSegment.getMetrics(),
              shardSpec,
              oldSegment.getBinaryVersion(),
              oldSegment.getSize()
          );
          final String now = DateTimes.nowUtc().toString();
          preparedBatch.add()
                       .bind("id", newSegment.getId().toString())
                       .bind("dataSource", newSegment.getDataSource())
                       .bind("created_date", now)
                       .bind("start", newSegment.getInterval().getStart().toString())
                       .bind("end", newSegment.getInterval().getEnd().toString())
                       .bind("partitioned", (newSegment.getShardSpec() instanceof NoneShardSpec) ? false : true)
                       .bind("version", newSegment.getVersion())
                       .bind("used", true)
                       .bind("payload", jsonMapper.writeValueAsBytes(newSegment))
                       .bind("used_status_last_updated", now);
        }
        final int[] affectedInsertRows = preparedBatch.execute();

        final boolean succeeded = Arrays.stream(affectedInsertRows).allMatch(eachAffectedRow -> eachAffectedRow == 1);
        if (succeeded) {
          log.info("Published segments with updated metadata to DB");
        } else {
          throw new ISE("Failed to update segment metadatas in DB");
        }
      }
    }
    catch (Exception e) {
      log.errorSegments(segments, "Exception inserting segment metadata");
      throw e;
    }

    return toInsertSegments;
  }

  private Set<DataSegment> commitAppendSegmentBatch(
      final Handle handle,
      final Set<DataSegment> segments,
      @Nullable Map<DataSegment, TaskLockInfo> appendSegmentLockMap
  ) throws IOException
  {
    final Set<DataSegment> toInsertSegments = new HashSet<>();
    try {
      Set<String> existedSegments = segmentExistsBatch(handle, segments);
      log.info("Found these segments already exist in DB: %s", existedSegments);
      for (DataSegment segment : segments) {
        if (!existedSegments.contains(segment.getId().toString())) {
          toInsertSegments.add(segment);
        }
      }

      // SELECT -> INSERT can fail due to races; callers must be prepared to retry.
      // Avoiding ON DUPLICATE KEY since it's not portable.
      // Avoiding try/catch since it may cause inadvertent transaction-splitting.
      final List<List<DataSegment>> partitionedSegments = Lists.partition(
          new ArrayList<>(toInsertSegments),
          MAX_NUM_SEGMENTS_TO_ANNOUNCE_AT_ONCE
      );

      PreparedBatch preparedBatch = handle.prepareBatch(insertSegmentQuery);
      for (List<DataSegment> partition : partitionedSegments) {
        for (DataSegment segment : partition) {
          final String now = DateTimes.nowUtc().toString();
          preparedBatch.add()
                       .bind("id", segment.getId().toString())
                       .bind("dataSource", segment.getDataSource())
                       .bind("created_date", now)
                       .bind("start", segment.getInterval().getStart().toString())
                       .bind("end", segment.getInterval().getEnd().toString())
                       .bind("partitioned", (segment.getShardSpec() instanceof NoneShardSpec) ? false : true)
                       .bind("version", segment.getVersion())
                       .bind("used", true)
                       .bind("payload", jsonMapper.writeValueAsBytes(segment))
                       .bind("used_status_last_updated", now);
        }
        final int[] affectedInsertRows = preparedBatch.execute();

        final boolean succeeded = Arrays.stream(affectedInsertRows).allMatch(eachAffectedRow -> eachAffectedRow == 1);
        if (succeeded) {
          log.infoSegments(partition, "Published segments to DB");
        } else {
          final List<DataSegment> failedToPublish = IntStream.range(0, partition.size())
                                                             .filter(i -> affectedInsertRows[i] != 1)
                                                             .mapToObj(partition::get)
                                                             .collect(Collectors.toList());
          throw new ISE(
              "Failed to publish segments to DB: %s",
              SegmentUtils.commaSeparatedIdentifiers(failedToPublish)
          );
        }
      }

      PreparedBatch appendBatch = handle.prepareBatch(
          StringUtils.format(
              "INSERT INTO %1$s (id, dataSource, start, %2$send%2$s, segment_id, lock_version) "
              + "VALUES (:id, :dataSource, :start, :end, :segment_id, :lock_version)",
              dbTables.getSegmentVersionsTable(),
              connector.getQuoteString()
          )
      );
      if (appendSegmentLockMap == null) {
        appendSegmentLockMap = new HashMap<>();
      }
      final List<List<Map.Entry<DataSegment, TaskLockInfo>>> appendSegmentPartitions = Lists.partition(
          new ArrayList<>(appendSegmentLockMap.entrySet()),
          MAX_NUM_SEGMENTS_TO_ANNOUNCE_AT_ONCE
      );
      for (List<Map.Entry<DataSegment, TaskLockInfo>> partition : appendSegmentPartitions) {
        for (Map.Entry<DataSegment, TaskLockInfo> entry : partition) {
          DataSegment segment = entry.getKey();
          TaskLockInfo lock = entry.getValue();
          appendBatch.add()
                     .bind("id", segment.getId() + ":" + lock.hashCode())
                     .bind("dataSource", segment.getDataSource())
                     .bind("start", lock.getInterval().getStartMillis())
                     .bind("end", lock.getInterval().getEndMillis())
                     .bind("segment_id", segment.getId().toString())
                     .bind("lock_version", lock.getVersion());
        }
        final int[] affectedAppendRows = appendBatch.execute();
        final boolean succeeded = Arrays.stream(affectedAppendRows).allMatch(eachAffectedRow -> eachAffectedRow == 1);
        if (!succeeded) {
          final List<DataSegment> failedToForward = IntStream.range(0, partition.size())
                                                             .filter(i -> affectedAppendRows[i] != 1)
                                                             .mapToObj(partition::get)
                                                             .map(x -> x.getKey())
                                                             .collect(Collectors.toList());
          throw new ISE(
              "Failed to forward appended segments to DB: %s",
              SegmentUtils.commaSeparatedIdentifiers(failedToForward)
          );
        }
      }
    }
    catch (Exception e) {
      log.errorSegments(segments, "Exception inserting segment metadata");
      throw e;
    }

    return toInsertSegments;
  }

  private List<DataSegment> retrieveSegmentsById(Handle handle, Set<String> segmentIds)
  {
    final String segmentIdCsv = segmentIds.stream().map(id -> "'" + id + "'")
                                          .collect(Collectors.joining(","));
    final Query<Map<String, Object>> query = handle.createQuery(
        StringUtils.format(
            "SELECT payload FROM %s WHERE id in (%s)",
            dbTables.getSegmentsTable(), segmentIdCsv
        )
    ).setFetchSize(connector.getStreamingFetchSize());

    ResultIterator<DataSegment> resultIterator = query.map(
        (index, r, ctx) -> JacksonUtils.readValue(jsonMapper, r.getBytes(1), DataSegment.class)
    ).iterator();

    return Lists.newArrayList(resultIterator);
  }

  @VisibleForTesting
  Map<String, TaskLockInfo> getAppendedSegmentIds(
      Handle handle,
      String datasource,
      Set<TaskLockInfo> replaceLocks
  )
  {
    if (CollectionUtils.isNullOrEmpty(replaceLocks)) {
      return Collections.emptyMap();
    }
    final StringBuilder sb = new StringBuilder();
    sb.append(
        StringUtils.format(
            "SELECT segment_id, start, %1$send%1$s, lock_version FROM %2$s where dataSource = :dataSource AND (",
            connector.getQuoteString(),
            dbTables.getSegmentVersionsTable()
        )
    );

    List<TaskLockInfo> locks = new ArrayList<>(replaceLocks);
    int n = locks.size();
    for (int i = 0; i < n; i++) {
      sb.append(
          StringUtils.format(
              "(start = %2$s AND %1$send%1$s = %3$s AND lock_version = %4$s)",
              connector.getQuoteString(),
              StringUtils.format(":start%d", i),
              StringUtils.format(":end%d", i),
              StringUtils.format(":lock_version%d", i)
          )
      );
      if (i < n - 1) {
        sb.append(" OR ");
      }
    }

    sb.append(")");

    Query<Map<String, Object>> query = handle
        .createQuery(
            sb.toString()
        )
        .bind("dataSource", datasource);
    for (int i = 0; i < n; i++) {
      query.bind(StringUtils.format("start%d", i), locks.get(i).getInterval().getStartMillis())
           .bind(StringUtils.format("end%d", i), locks.get(i).getInterval().getEndMillis())
           .bind(StringUtils.format("lock_version%d", i), locks.get(i).getVersion());
    }

    final ResultIterator<Pair<String, TaskLockInfo>> resultIterator = query.map((index, r, ctx) -> {
      String segmentId = r.getString("segment_id");
      Interval interval = Intervals.utc(r.getLong("start"), r.getLong("end"));
      String version = r.getString("lock_version");
      return Pair.of(segmentId, new TaskLockInfo(interval, version));
    }).iterator();
    Map<String, TaskLockInfo> retVal = new HashMap<>();
    while (resultIterator.hasNext()) {
      Pair<String, TaskLockInfo> result = resultIterator.next();
      retVal.put(result.lhs, result.rhs);
    }
    return retVal;
  }

  private Set<String> segmentExistsBatch(final Handle handle, final Set<DataSegment> segments)
  {
    Set<String> existedSegments = new HashSet<>();

    List<List<DataSegment>> segmentsLists = Lists.partition(new ArrayList<>(segments), MAX_NUM_SEGMENTS_TO_ANNOUNCE_AT_ONCE);
    for (List<DataSegment> segmentList : segmentsLists) {
      String segmentIds = segmentList.stream()
          .map(segment -> "'" + StringEscapeUtils.escapeSql(segment.getId().toString()) + "'")
          .collect(Collectors.joining(","));
      List<String> existIds = handle.createQuery(StringUtils.format("SELECT id FROM %s WHERE id in (%s)", dbTables.getSegmentsTable(), segmentIds))
          .mapTo(String.class)
          .list();
      existedSegments.addAll(existIds);
    }
    return existedSegments;
  }

  /**
   * Read dataSource metadata. Returns null if there is no metadata.
   */
  @Override
  public @Nullable DataSourceMetadata retrieveDataSourceMetadata(final String dataSource)
  {
    final byte[] bytes = connector.lookup(
        dbTables.getDataSourceTable(),
        "dataSource",
        "commit_metadata_payload",
        dataSource
    );

    if (bytes == null) {
      return null;
    }

    return JacksonUtils.readValue(jsonMapper, bytes, DataSourceMetadata.class);
  }

  /**
   * Read dataSource metadata as bytes, from a specific handle. Returns null if there is no metadata.
   */
  private @Nullable byte[] retrieveDataSourceMetadataWithHandleAsBytes(
      final Handle handle,
      final String dataSource
  )
  {
    return connector.lookupWithHandle(
        handle,
        dbTables.getDataSourceTable(),
        "dataSource",
        "commit_metadata_payload",
        dataSource
    );
  }

  /**
   * Compare-and-swap dataSource metadata in a transaction. This will only modify dataSource metadata if it equals
   * oldCommitMetadata when this function is called (based on T.equals). This method is idempotent in that if
   * the metadata already equals newCommitMetadata, it will return true.
   *
   * @param handle        database handle
   * @param dataSource    druid dataSource
   * @param startMetadata dataSource metadata pre-insert must match this startMetadata according to
   *                      {@link DataSourceMetadata#matches(DataSourceMetadata)}
   * @param endMetadata   dataSource metadata post-insert will have this endMetadata merged in with
   *                      {@link DataSourceMetadata#plus(DataSourceMetadata)}
   *
   * @return SUCCESS if dataSource metadata was updated from matching startMetadata to matching endMetadata, FAILURE or
   * TRY_AGAIN if it definitely was not updated. This guarantee is meant to help
   * {@link #commitSegmentsAndMetadata} (Set, DataSourceMetadata, DataSourceMetadata)}
   * achieve its own guarantee.
   *
   * @throws RuntimeException if state is unknown after this call
   */
  protected DataStoreMetadataUpdateResult updateDataSourceMetadataWithHandle(
      final Handle handle,
      final String dataSource,
      final DataSourceMetadata startMetadata,
      final DataSourceMetadata endMetadata
  ) throws IOException
  {
    Preconditions.checkNotNull(dataSource, "dataSource");
    Preconditions.checkNotNull(startMetadata, "startMetadata");
    Preconditions.checkNotNull(endMetadata, "endMetadata");

    final byte[] oldCommitMetadataBytesFromDb = retrieveDataSourceMetadataWithHandleAsBytes(handle, dataSource);
    final String oldCommitMetadataSha1FromDb;
    final DataSourceMetadata oldCommitMetadataFromDb;

    if (oldCommitMetadataBytesFromDb == null) {
      oldCommitMetadataSha1FromDb = null;
      oldCommitMetadataFromDb = null;
    } else {
      oldCommitMetadataSha1FromDb = BaseEncoding.base16().encode(
          Hashing.sha1().hashBytes(oldCommitMetadataBytesFromDb).asBytes()
      );
      oldCommitMetadataFromDb = jsonMapper.readValue(oldCommitMetadataBytesFromDb, DataSourceMetadata.class);
    }

    final boolean startMetadataMatchesExisting;

    if (oldCommitMetadataFromDb == null) {
      startMetadataMatchesExisting = startMetadata.isValidStart();
    } else {
      // Checking against the last committed metadata.
      // Converting the last one into start metadata for checking since only the same type of metadata can be matched.
      // Even though kafka/kinesis indexing services use different sequenceNumber types for representing
      // start and end sequenceNumbers, the below conversion is fine because the new start sequenceNumbers are supposed
      // to be same with end sequenceNumbers of the last commit.
      startMetadataMatchesExisting = startMetadata.asStartMetadata().matches(oldCommitMetadataFromDb.asStartMetadata());
    }

    if (!startMetadataMatchesExisting) {
      // Not in the desired start state.
      return new DataStoreMetadataUpdateResult(true, false, StringUtils.format(
          "Inconsistent metadata state. This can happen if you update input topic in a spec without changing " +
              "the supervisor name. Stored state: [%s], Target state: [%s].",
          oldCommitMetadataFromDb,
          startMetadata
      ));
    }

    // Only endOffsets should be stored in metadata store
    final DataSourceMetadata newCommitMetadata = oldCommitMetadataFromDb == null
                                                 ? endMetadata
                                                 : oldCommitMetadataFromDb.plus(endMetadata);
    final byte[] newCommitMetadataBytes = jsonMapper.writeValueAsBytes(newCommitMetadata);
    final String newCommitMetadataSha1 = BaseEncoding.base16().encode(
        Hashing.sha1().hashBytes(newCommitMetadataBytes).asBytes()
    );

    final DataStoreMetadataUpdateResult retVal;
    if (oldCommitMetadataBytesFromDb == null) {
      // SELECT -> INSERT can fail due to races; callers must be prepared to retry.
      final int numRows = handle.createStatement(
          StringUtils.format(
              "INSERT INTO %s (dataSource, created_date, commit_metadata_payload, commit_metadata_sha1) "
              + "VALUES (:dataSource, :created_date, :commit_metadata_payload, :commit_metadata_sha1)",
              dbTables.getDataSourceTable()
          )
      )
                                .bind("dataSource", dataSource)
                                .bind("created_date", DateTimes.nowUtc().toString())
                                .bind("commit_metadata_payload", newCommitMetadataBytes)
                                .bind("commit_metadata_sha1", newCommitMetadataSha1)
                                .execute();

      retVal = numRows == 1
          ? DataStoreMetadataUpdateResult.SUCCESS
          : new DataStoreMetadataUpdateResult(
              true,
          true,
          "Failed to insert metadata for datasource [%s]",
          dataSource);
    } else {
      // Expecting a particular old metadata; use the SHA1 in a compare-and-swap UPDATE
      final int numRows = handle.createStatement(
          StringUtils.format(
              "UPDATE %s SET "
              + "commit_metadata_payload = :new_commit_metadata_payload, "
              + "commit_metadata_sha1 = :new_commit_metadata_sha1 "
              + "WHERE dataSource = :dataSource AND commit_metadata_sha1 = :old_commit_metadata_sha1",
              dbTables.getDataSourceTable()
          )
      )
                                .bind("dataSource", dataSource)
                                .bind("old_commit_metadata_sha1", oldCommitMetadataSha1FromDb)
                                .bind("new_commit_metadata_payload", newCommitMetadataBytes)
                                .bind("new_commit_metadata_sha1", newCommitMetadataSha1)
                                .execute();

      retVal = numRows == 1
          ? DataStoreMetadataUpdateResult.SUCCESS
          : new DataStoreMetadataUpdateResult(
          true,
          true,
          "Failed to update metadata for datasource [%s]",
          dataSource);
    }

    if (retVal.isSuccess()) {
      log.info("Updated metadata from[%s] to[%s].", oldCommitMetadataFromDb, newCommitMetadata);
    } else {
      log.info("Not updating metadata, compare-and-swap failure.");
    }

    return retVal;
  }

  @Override
  public boolean deleteDataSourceMetadata(final String dataSource)
  {
    return connector.retryWithHandle(
        new HandleCallback<Boolean>()
        {
          @Override
          public Boolean withHandle(Handle handle)
          {
            int rows = handle.createStatement(
                StringUtils.format("DELETE from %s WHERE dataSource = :dataSource", dbTables.getDataSourceTable())
            )
                             .bind("dataSource", dataSource)
                             .execute();

            return rows > 0;
          }
        }
    );
  }

  @Override
  public boolean resetDataSourceMetadata(final String dataSource, final DataSourceMetadata dataSourceMetadata)
      throws IOException
  {
    final byte[] newCommitMetadataBytes = jsonMapper.writeValueAsBytes(dataSourceMetadata);
    final String newCommitMetadataSha1 = BaseEncoding.base16().encode(
        Hashing.sha1().hashBytes(newCommitMetadataBytes).asBytes()
    );

    return connector.retryWithHandle(
        new HandleCallback<Boolean>()
        {
          @Override
          public Boolean withHandle(Handle handle)
          {
            final int numRows = handle.createStatement(
                StringUtils.format(
                    "UPDATE %s SET "
                    + "commit_metadata_payload = :new_commit_metadata_payload, "
                    + "commit_metadata_sha1 = :new_commit_metadata_sha1 "
                    + "WHERE dataSource = :dataSource",
                    dbTables.getDataSourceTable()
                )
            )
                                      .bind("dataSource", dataSource)
                                      .bind("new_commit_metadata_payload", newCommitMetadataBytes)
                                      .bind("new_commit_metadata_sha1", newCommitMetadataSha1)
                                      .execute();
            return numRows == 1;
          }
        }
    );
  }

  @Override
  public void updateSegmentMetadata(final Set<DataSegment> segments)
  {
    connector.getDBI().inTransaction(
        new TransactionCallback<Void>()
        {
          @Override
          public Void inTransaction(Handle handle, TransactionStatus transactionStatus) throws Exception
          {
            for (final DataSegment segment : segments) {
              updatePayload(handle, segment);
            }

            return null;
          }
        }
    );
  }

  @Override
  public void deleteSegments(final Set<DataSegment> segments)
  {
    if (segments.isEmpty()) {
      log.info("No segments to delete.");
      return;
    }

    final String deleteSql = StringUtils.format("DELETE from %s WHERE id = :id", dbTables.getSegmentsTable());
    final String dataSource = segments.stream().findFirst().map(DataSegment::getDataSource).get();

    // generate the IDs outside the transaction block
    final List<String> ids = segments.stream().map(s -> s.getId().toString()).collect(Collectors.toList());

    int numDeletedSegments = connector.getDBI().inTransaction((handle, transactionStatus) -> {
          final PreparedBatch batch = handle.prepareBatch(deleteSql);

          for (final String id : ids) {
            batch.bind("id", id).add();
          }

          int[] deletedRows = batch.execute();
          return Arrays.stream(deletedRows).sum();
        }
    );

    log.debugSegments(segments, "Delete the metadata of segments");
    log.info("Deleted [%d] segments from metadata storage for dataSource [%s].", numDeletedSegments, dataSource);
  }

  private void updatePayload(final Handle handle, final DataSegment segment) throws IOException
  {
    try {
      handle
          .createStatement(
              StringUtils.format("UPDATE %s SET payload = :payload WHERE id = :id", dbTables.getSegmentsTable())
          )
          .bind("id", segment.getId().toString())
          .bind("payload", jsonMapper.writeValueAsBytes(segment))
          .execute();
    }
    catch (IOException e) {
      log.error(e, "Exception inserting into DB");
      throw e;
    }
  }

  @Override
  public boolean insertDataSourceMetadata(String dataSource, DataSourceMetadata metadata)
  {
    return 1 == connector.getDBI().inTransaction(
        (handle, status) -> handle
            .createStatement(
                StringUtils.format(
                    "INSERT INTO %s (dataSource, created_date, commit_metadata_payload, commit_metadata_sha1) VALUES" +
                    " (:dataSource, :created_date, :commit_metadata_payload, :commit_metadata_sha1)",
                    dbTables.getDataSourceTable()
                )
            )
            .bind("dataSource", dataSource)
            .bind("created_date", DateTimes.nowUtc().toString())
            .bind("commit_metadata_payload", jsonMapper.writeValueAsBytes(metadata))
            .bind("commit_metadata_sha1", BaseEncoding.base16().encode(
                Hashing.sha1().hashBytes(jsonMapper.writeValueAsBytes(metadata)).asBytes()))
            .execute()
    );
  }

  @Override
  public int removeDataSourceMetadataOlderThan(long timestamp, @NotNull Set<String> excludeDatasources)
  {
    DateTime dateTime = DateTimes.utc(timestamp);
    List<String> datasourcesToDelete = connector.getDBI().withHandle(
        handle -> handle
            .createQuery(
                StringUtils.format(
                    "SELECT dataSource FROM %1$s WHERE created_date < '%2$s'",
                    dbTables.getDataSourceTable(),
                    dateTime.toString()
                )
            )
            .mapTo(String.class)
            .list()
    );
    datasourcesToDelete.removeAll(excludeDatasources);
    return connector.getDBI().withHandle(
        handle -> {
          final PreparedBatch batch = handle.prepareBatch(
              StringUtils.format(
                  "DELETE FROM %1$s WHERE dataSource = :dataSource AND created_date < '%2$s'",
                  dbTables.getDataSourceTable(),
                  dateTime.toString()
              )
          );
          for (String datasource : datasourcesToDelete) {
            batch.bind("dataSource", datasource).add();
          }
          int[] result = batch.execute();
          return IntStream.of(result).sum();
        }
    );
  }

  @Override
  public DataSegment retrieveUsedSegmentForId(final String id)
  {
    return connector.retryTransaction(
        (handle, status) ->
            SqlSegmentsMetadataQuery.forHandle(handle, connector, dbTables, jsonMapper)
                                    .retrieveUsedSegmentForId(id),
        3,
        SQLMetadataConnector.DEFAULT_MAX_TRIES
    );
  }

  private static class PendingSegmentsRecord
  {
    private final String sequenceName;
    private final byte[] payload;

    /**
     * The columns expected in the result set are:
     * <ol>
     *   <li>sequence_name</li>
     *   <li>payload</li>
     * </ol>
     */
    static PendingSegmentsRecord fromResultSet(ResultSet resultSet)
    {
      try {
        return new PendingSegmentsRecord(
            resultSet.getString(1),
            resultSet.getBytes(2)
        );
      }
      catch (SQLException e) {
        throw new RuntimeException(e);
      }
    }

    PendingSegmentsRecord(String sequenceName, byte[] payload)
    {
      this.payload = payload;
      this.sequenceName = sequenceName;
    }

    public byte[] getPayload()
    {
      return payload;
    }

    public String getSequenceName()
    {
      return sequenceName;
    }
  }

  public static class DataStoreMetadataUpdateResult
  {
    private final boolean failed;
    private final boolean canRetry;
    @Nullable private final String errorMsg;

    public static final DataStoreMetadataUpdateResult SUCCESS = new DataStoreMetadataUpdateResult(false, false, null);

    DataStoreMetadataUpdateResult(boolean failed, boolean canRetry, @Nullable String errorMsg, Object... errorFormatArgs)
    {
      this.failed = failed;
      this.canRetry = canRetry;
      this.errorMsg = null == errorMsg ? null : StringUtils.format(errorMsg, errorFormatArgs);

    }

    public boolean isFailed()
    {
      return failed;
    }

    public boolean isSuccess()
    {
      return !failed;
    }

    public boolean canRetry()
    {
      return canRetry;
    }

    @Nullable
    public String getErrorMsg()
    {
      return errorMsg;
    }

    @Override
    public boolean equals(Object o)
    {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      DataStoreMetadataUpdateResult that = (DataStoreMetadataUpdateResult) o;
      return failed == that.failed && canRetry == that.canRetry && Objects.equals(errorMsg, that.errorMsg);
    }

    @Override
    public int hashCode()
    {
      return Objects.hash(failed, canRetry, errorMsg);
    }

    @Override
    public String toString()
    {
      return "DataStoreMetadataUpdateResult{" +
          "failed=" + failed +
          ", canRetry=" + canRetry +
          ", errorMsg='" + errorMsg + '\'' +
          '}';
    }
  }

}
