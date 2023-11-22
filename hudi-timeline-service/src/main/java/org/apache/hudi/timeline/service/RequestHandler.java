/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi.timeline.service;

import org.apache.hudi.common.engine.HoodieEngineContext;
import org.apache.hudi.common.metrics.Registry;
import org.apache.hudi.common.table.marker.MarkerOperation;
import org.apache.hudi.common.table.timeline.HoodieInstant;
import org.apache.hudi.common.table.timeline.HoodieTimeline;
import org.apache.hudi.common.table.timeline.dto.BaseFileDTO;
import org.apache.hudi.common.table.timeline.dto.ClusteringOpDTO;
import org.apache.hudi.common.table.timeline.dto.CompactionOpDTO;
import org.apache.hudi.common.table.timeline.dto.FileGroupDTO;
import org.apache.hudi.common.table.timeline.dto.FileSliceDTO;
import org.apache.hudi.common.table.timeline.dto.InstantDTO;
import org.apache.hudi.common.table.timeline.dto.InstantStateDTO;
import org.apache.hudi.common.table.timeline.dto.TimelineDTO;
import org.apache.hudi.common.table.view.FileSystemViewManager;
import org.apache.hudi.common.table.view.RemoteHoodieTableFileSystemView;
import org.apache.hudi.common.table.view.SyncableFileSystemView;
import org.apache.hudi.common.util.HoodieTimer;
import org.apache.hudi.common.util.Option;
import org.apache.hudi.exception.HoodieException;
import org.apache.hudi.timeline.service.handlers.BaseFileHandler;
import org.apache.hudi.timeline.service.handlers.FileSliceHandler;
import org.apache.hudi.timeline.service.handlers.InstantStateHandler;
import org.apache.hudi.timeline.service.handlers.MarkerHandler;
import org.apache.hudi.timeline.service.handlers.TimelineHandler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.afterburner.AfterburnerModule;
import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Main REST Handler class that handles and delegates calls to timeline relevant handlers.
 */
public class RequestHandler {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().registerModule(new AfterburnerModule());
  private static final Logger LOG = LoggerFactory.getLogger(RequestHandler.class);

  private final TimelineService.Config timelineServiceConfig;
  private final FileSystemViewManager viewManager;
  private final Javalin app;
  private final TimelineHandler instantHandler;
  private final FileSliceHandler sliceHandler;
  private final BaseFileHandler dataFileHandler;
  private final MarkerHandler markerHandler;
  private final InstantStateHandler instantStateHandler;
  private final Registry metricsRegistry = Registry.getRegistry("TimelineService");
  private ScheduledExecutorService asyncResultService = Executors.newSingleThreadScheduledExecutor();

  public RequestHandler(Javalin app, Configuration conf, TimelineService.Config timelineServiceConfig,
                        HoodieEngineContext hoodieEngineContext, FileSystem fileSystem,
                        FileSystemViewManager viewManager) throws IOException {
    this.timelineServiceConfig = timelineServiceConfig;
    this.viewManager = viewManager;
    this.app = app;
    this.instantHandler = new TimelineHandler(conf, timelineServiceConfig, fileSystem, viewManager);
    this.sliceHandler = new FileSliceHandler(conf, timelineServiceConfig, fileSystem, viewManager);
    this.dataFileHandler = new BaseFileHandler(conf, timelineServiceConfig, fileSystem, viewManager);
    if (timelineServiceConfig.enableMarkerRequests) {
      this.markerHandler = new MarkerHandler(
          conf, timelineServiceConfig, hoodieEngineContext, fileSystem, viewManager, metricsRegistry);
    } else {
      this.markerHandler = null;
    }
    if (timelineServiceConfig.enableInstantStateRequests) {
      this.instantStateHandler = new InstantStateHandler(conf, timelineServiceConfig, fileSystem, viewManager);
    } else {
      this.instantStateHandler = null;
    }
    if (timelineServiceConfig.async) {
      asyncResultService = Executors.newSingleThreadScheduledExecutor();
    }
  }

  /**
   * Serializes the result into JSON String.
   *
   * @param ctx             Javalin context
   * @param obj             object to serialize
   * @param metricsRegistry {@code Registry} instance for storing metrics
   * @param objectMapper    JSON object mapper
   * @param logger          {@code Logger} instance
   * @return JSON String from the input object
   * @throws JsonProcessingException
   */
  public static String jsonifyResult(
      Context ctx, Object obj, Registry metricsRegistry, ObjectMapper objectMapper, Logger logger)
      throws JsonProcessingException {
    HoodieTimer timer = HoodieTimer.start();
    boolean prettyPrint = ctx.queryParam("pretty") != null;
    String result =
        prettyPrint ? objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj)
            : objectMapper.writeValueAsString(obj);
    final long jsonifyTime = timer.endTimer();
    metricsRegistry.add("WRITE_VALUE_CNT", 1);
    metricsRegistry.add("WRITE_VALUE_TIME", jsonifyTime);
    if (logger.isDebugEnabled()) {
      logger.debug("Jsonify TimeTaken=" + jsonifyTime);
    }
    return result;
  }

  private static boolean isRefreshCheckDisabledInQuery(Context ctxt) {
    return Boolean.parseBoolean(ctxt.queryParam(RemoteHoodieTableFileSystemView.REFRESH_OFF));
  }

  public void register() {
    registerDataFilesAPI();
    registerFileSlicesAPI();
    registerTimelineAPI();
    if (markerHandler != null) {
      registerMarkerAPI();
    }
    if (instantStateHandler != null) {
      registerInstantStateAPI();
    }
  }

  public void stop() {
    if (markerHandler != null) {
      markerHandler.stop();
    }
  }

  /**
   * Determines if local view of table's timeline is behind that of client's view.
   */
  private boolean isLocalViewBehind(Context ctx) {
    String basePath = ctx.queryParam(RemoteHoodieTableFileSystemView.BASEPATH_PARAM);
    String lastKnownInstantFromClient =
        ctx.queryParamAsClass(RemoteHoodieTableFileSystemView.LAST_INSTANT_TS, String.class).getOrDefault(HoodieTimeline.INVALID_INSTANT_TS);
    String timelineHashFromClient = ctx.queryParamAsClass(RemoteHoodieTableFileSystemView.TIMELINE_HASH, String.class).getOrDefault("");
    HoodieTimeline localTimeline =
        viewManager.getFileSystemView(basePath).getTimeline().filterCompletedOrMajorOrMinorCompactionInstants();
    if (LOG.isDebugEnabled()) {
      LOG.debug("Client [ LastTs=" + lastKnownInstantFromClient + ", TimelineHash=" + timelineHashFromClient
          + "], localTimeline=" + localTimeline.getInstants());
    }

    if ((!localTimeline.getInstantsAsStream().findAny().isPresent())
        && HoodieTimeline.INVALID_INSTANT_TS.equals(lastKnownInstantFromClient)) {
      return false;
    }

    String localTimelineHash = localTimeline.getTimelineHash();
    // refresh if timeline hash mismatches
    if (!localTimelineHash.equals(timelineHashFromClient)) {
      return true;
    }

    // As a safety check, even if hash is same, ensure instant is present
    return !localTimeline.containsOrBeforeTimelineStarts(lastKnownInstantFromClient);
  }

  /**
   * Syncs data-set view if local view is behind.
   */
  private boolean syncIfLocalViewBehind(Context ctx) {
    String basePath = ctx.queryParam(RemoteHoodieTableFileSystemView.BASEPATH_PARAM);
    SyncableFileSystemView view = viewManager.getFileSystemView(basePath);
    synchronized (view) {
      if (isLocalViewBehind(ctx)) {

        String lastKnownInstantFromClient = ctx.queryParamAsClass(
                RemoteHoodieTableFileSystemView.LAST_INSTANT_TS, String.class)
            .getOrDefault(HoodieTimeline.INVALID_INSTANT_TS);
        HoodieTimeline localTimeline = viewManager.getFileSystemView(basePath).getTimeline();
        LOG.info("Syncing view as client passed last known instant " + lastKnownInstantFromClient
            + " as last known instant but server has the following last instant on timeline :"
            + localTimeline.lastInstant());
        view.sync();
        return true;
      }
    }
    return false;
  }

  private void writeValueAsString(Context ctx, Object obj) throws JsonProcessingException {
    if (timelineServiceConfig.async) {
      writeValueAsStringAsync(ctx, obj);
    } else {
      writeValueAsStringSync(ctx, obj);
    }
  }

  private void writeValueAsStringSync(Context ctx, Object obj) throws JsonProcessingException {
    String result = jsonifyResult(ctx, obj, metricsRegistry, OBJECT_MAPPER, LOG);
    ctx.result(result);
  }

  private void writeValueAsStringAsync(Context ctx, Object obj) {
    ctx.future(CompletableFuture.supplyAsync(() -> {
      try {
        return jsonifyResult(ctx, obj, metricsRegistry, OBJECT_MAPPER, LOG);
      } catch (JsonProcessingException e) {
        throw new HoodieException("Failed to JSON encode the value", e);
      }
    }, asyncResultService));
  }

  /**
   * Register Timeline API calls.
   */
  private void registerTimelineAPI() {
    app.get(RemoteHoodieTableFileSystemView.LAST_INSTANT, new ViewHandler(ctx -> {
      metricsRegistry.add("LAST_INSTANT", 1);
      List<InstantDTO> dtos = instantHandler.getLastInstant(ctx.queryParamAsClass(RemoteHoodieTableFileSystemView.BASEPATH_PARAM, String.class).get());
      writeValueAsString(ctx, dtos);
    }, false));

    app.get(RemoteHoodieTableFileSystemView.TIMELINE, new ViewHandler(ctx -> {
      metricsRegistry.add("TIMELINE", 1);
      TimelineDTO dto = instantHandler.getTimeline(ctx.queryParamAsClass(RemoteHoodieTableFileSystemView.BASEPATH_PARAM, String.class).get());
      writeValueAsString(ctx, dto);
    }, false));
  }

  /**
   * Register Data-Files API calls.
   */
  private void registerDataFilesAPI() {
    app.get(RemoteHoodieTableFileSystemView.LATEST_PARTITION_DATA_FILES_URL, new ViewHandler(ctx -> {
      metricsRegistry.add("LATEST_PARTITION_DATA_FILES", 1);
      List<BaseFileDTO> dtos = dataFileHandler.getLatestDataFiles(
          ctx.queryParamAsClass(RemoteHoodieTableFileSystemView.BASEPATH_PARAM, String.class).getOrThrow(e -> new HoodieException("Basepath is invalid")),
          ctx.queryParamAsClass(RemoteHoodieTableFileSystemView.PARTITION_PARAM, String.class).getOrDefault(""));

      writeValueAsString(ctx, dtos);
    }, true));

    app.get(RemoteHoodieTableFileSystemView.LATEST_PARTITION_DATA_FILE_URL, new ViewHandler(ctx -> {
      metricsRegistry.add("LATEST_PARTITION_DATA_FILE", 1);
      List<BaseFileDTO> dtos = dataFileHandler.getLatestDataFile(
          ctx.queryParamAsClass(RemoteHoodieTableFileSystemView.BASEPATH_PARAM, String.class).getOrThrow(e -> new HoodieException("Basepath is invalid")),
          ctx.queryParamAsClass(RemoteHoodieTableFileSystemView.PARTITION_PARAM, String.class).getOrDefault(""),
          ctx.queryParamAsClass(RemoteHoodieTableFileSystemView.FILEID_PARAM, String.class).getOrThrow(e -> new HoodieException("FILEID is invalid")));
      writeValueAsString(ctx, dtos);
    }, true));

    app.get(RemoteHoodieTableFileSystemView.LATEST_ALL_DATA_FILES, new ViewHandler(ctx -> {
      metricsRegistry.add("LATEST_ALL_DATA_FILES", 1);
      List<BaseFileDTO> dtos = dataFileHandler.getLatestDataFiles(
          ctx.queryParamAsClass(RemoteHoodieTableFileSystemView.BASEPATH_PARAM, String.class).getOrThrow(e -> new HoodieException("Basepath is invalid")));
      writeValueAsString(ctx, dtos);
    }, true));

    app.get(RemoteHoodieTableFileSystemView.LATEST_DATA_FILES_BEFORE_ON_INSTANT_URL, new ViewHandler(ctx -> {
      metricsRegistry.add("LATEST_DATA_FILES_BEFORE_ON_INSTANT", 1);
      List<BaseFileDTO> dtos = dataFileHandler.getLatestDataFilesBeforeOrOn(
          ctx.queryParamAsClass(RemoteHoodieTableFileSystemView.BASEPATH_PARAM, String.class).getOrThrow(e -> new HoodieException("Basepath is invalid")),
          ctx.queryParamAsClass(RemoteHoodieTableFileSystemView.PARTITION_PARAM, String.class).getOrDefault(""),
          ctx.queryParamAsClass(RemoteHoodieTableFileSystemView.MAX_INSTANT_PARAM, String.class).getOrThrow(e -> new HoodieException("MAX_INSTANT_PARAM is invalid")));
      writeValueAsString(ctx, dtos);
    }, true));

    app.get(RemoteHoodieTableFileSystemView.ALL_LATEST_BASE_FILES_BEFORE_ON_INSTANT_URL, new ViewHandler(ctx -> {
      metricsRegistry.add("ALL_LATEST_BASE_FILES_BEFORE_ON_INSTANT", 1);
      Map<String, List<BaseFileDTO>> dtos = dataFileHandler.getAllLatestDataFilesBeforeOrOn(
          ctx.queryParamAsClass(RemoteHoodieTableFileSystemView.BASEPATH_PARAM, String.class).getOrThrow(e -> new HoodieException("Basepath is invalid")),
          ctx.queryParamAsClass(RemoteHoodieTableFileSystemView.MAX_INSTANT_PARAM, String.class).getOrThrow(e -> new HoodieException("MAX_INSTANT_PARAM is invalid")));
      writeValueAsString(ctx, dtos);
    }, true));

    app.get(RemoteHoodieTableFileSystemView.LATEST_DATA_FILE_ON_INSTANT_URL, new ViewHandler(ctx -> {
      metricsRegistry.add("LATEST_DATA_FILE_ON_INSTANT", 1);
      List<BaseFileDTO> dtos = dataFileHandler.getLatestDataFileOn(
          ctx.queryParamAsClass(RemoteHoodieTableFileSystemView.BASEPATH_PARAM, String.class).getOrThrow(e -> new HoodieException("Basepath is invalid")),
          ctx.queryParamAsClass(RemoteHoodieTableFileSystemView.PARTITION_PARAM, String.class).getOrDefault(""),
          ctx.queryParamAsClass(RemoteHoodieTableFileSystemView.INSTANT_PARAM, String.class).get(),
          ctx.queryParamAsClass(RemoteHoodieTableFileSystemView.FILEID_PARAM, String.class).getOrThrow(e -> new HoodieException("FILEID is invalid")));
      writeValueAsString(ctx, dtos);
    }, true));

    app.get(RemoteHoodieTableFileSystemView.ALL_DATA_FILES, new ViewHandler(ctx -> {
      metricsRegistry.add("ALL_DATA_FILES", 1);
      List<BaseFileDTO> dtos = dataFileHandler.getAllDataFiles(
          ctx.queryParamAsClass(RemoteHoodieTableFileSystemView.BASEPATH_PARAM, String.class).getOrThrow(e -> new HoodieException("Basepath is invalid")),
          ctx.queryParamAsClass(RemoteHoodieTableFileSystemView.PARTITION_PARAM, String.class).getOrDefault(""));
      writeValueAsString(ctx, dtos);
    }, true));

    app.get(RemoteHoodieTableFileSystemView.LATEST_DATA_FILES_RANGE_INSTANT_URL, new ViewHandler(ctx -> {
      metricsRegistry.add("LATEST_DATA_FILES_RANGE_INSTANT", 1);
      List<BaseFileDTO> dtos = dataFileHandler.getLatestDataFilesInRange(
          ctx.queryParamAsClass(RemoteHoodieTableFileSystemView.BASEPATH_PARAM, String.class).getOrThrow(e -> new HoodieException("Basepath is invalid")),
          Arrays.asList(ctx.queryParamAsClass(RemoteHoodieTableFileSystemView.INSTANTS_PARAM, String.class).getOrThrow(e -> new HoodieException("INSTANTS_PARAM is invalid")).split(",")));
      writeValueAsString(ctx, dtos);
    }, true));
  }

  /**
   * Register File Slices API calls.
   */
  private void registerFileSlicesAPI() {
    app.get(RemoteHoodieTableFileSystemView.LATEST_PARTITION_SLICES_URL, new ViewHandler(ctx -> {
      metricsRegistry.add("LATEST_PARTITION_SLICES", 1);
      List<FileSliceDTO> dtos = sliceHandler.getLatestFileSlices(
          ctx.queryParamAsClass(RemoteHoodieTableFileSystemView.BASEPATH_PARAM, String.class).getOrThrow(e -> new HoodieException("Basepath is invalid")),
          ctx.queryParamAsClass(RemoteHoodieTableFileSystemView.PARTITION_PARAM, String.class).getOrDefault(""));
      writeValueAsString(ctx, dtos);
    }, true));

    app.get(RemoteHoodieTableFileSystemView.LATEST_PARTITION_SLICE_URL, new ViewHandler(ctx -> {
      metricsRegistry.add("LATEST_PARTITION_SLICE", 1);
      List<FileSliceDTO> dtos = sliceHandler.getLatestFileSlice(
          ctx.queryParamAsClass(RemoteHoodieTableFileSystemView.BASEPATH_PARAM, String.class).getOrThrow(e -> new HoodieException("Basepath is invalid")),
          ctx.queryParamAsClass(RemoteHoodieTableFileSystemView.PARTITION_PARAM, String.class).getOrDefault(""),
          ctx.queryParamAsClass(RemoteHoodieTableFileSystemView.FILEID_PARAM, String.class).getOrThrow(e -> new HoodieException("FILEID is invalid")));
      writeValueAsString(ctx, dtos);
    }, true));

    app.get(RemoteHoodieTableFileSystemView.LATEST_PARTITION_UNCOMPACTED_SLICES_URL, new ViewHandler(ctx -> {
      metricsRegistry.add("LATEST_PARTITION_UNCOMPACTED_SLICES", 1);
      List<FileSliceDTO> dtos = sliceHandler.getLatestUnCompactedFileSlices(
          ctx.queryParamAsClass(RemoteHoodieTableFileSystemView.BASEPATH_PARAM, String.class).getOrThrow(e -> new HoodieException("Basepath is invalid")),
          ctx.queryParamAsClass(RemoteHoodieTableFileSystemView.PARTITION_PARAM, String.class).getOrDefault(""));
      writeValueAsString(ctx, dtos);
    }, true));

    app.get(RemoteHoodieTableFileSystemView.ALL_SLICES_URL, new ViewHandler(ctx -> {
      metricsRegistry.add("ALL_SLICES", 1);
      List<FileSliceDTO> dtos = sliceHandler.getAllFileSlices(
          ctx.queryParamAsClass(RemoteHoodieTableFileSystemView.BASEPATH_PARAM, String.class).getOrThrow(e -> new HoodieException("Basepath is invalid")),
          ctx.queryParamAsClass(RemoteHoodieTableFileSystemView.PARTITION_PARAM, String.class).getOrDefault(""));
      writeValueAsString(ctx, dtos);
    }, true));

    app.get(RemoteHoodieTableFileSystemView.LATEST_SLICES_RANGE_INSTANT_URL, new ViewHandler(ctx -> {
      metricsRegistry.add("LATEST_SLICE_RANGE_INSTANT", 1);
      List<FileSliceDTO> dtos = sliceHandler.getLatestFileSliceInRange(
          ctx.queryParamAsClass(RemoteHoodieTableFileSystemView.BASEPATH_PARAM, String.class).getOrThrow(e -> new HoodieException("Basepath is invalid")),
          Arrays.asList(ctx.queryParamAsClass(RemoteHoodieTableFileSystemView.INSTANTS_PARAM, String.class).getOrThrow(e -> new HoodieException("INSTANTS_PARAM is invalid")).split(",")));
      writeValueAsString(ctx, dtos);
    }, true));

    app.get(RemoteHoodieTableFileSystemView.LATEST_SLICES_MERGED_BEFORE_ON_INSTANT_URL, new ViewHandler(ctx -> {
      metricsRegistry.add("LATEST_SLICES_MERGED_BEFORE_ON_INSTANT", 1);
      List<FileSliceDTO> dtos = sliceHandler.getLatestMergedFileSlicesBeforeOrOn(
          ctx.queryParamAsClass(RemoteHoodieTableFileSystemView.BASEPATH_PARAM, String.class).getOrThrow(e -> new HoodieException("Basepath is invalid")),
          ctx.queryParamAsClass(RemoteHoodieTableFileSystemView.PARTITION_PARAM, String.class).getOrDefault(""),
          ctx.queryParamAsClass(RemoteHoodieTableFileSystemView.MAX_INSTANT_PARAM, String.class).getOrThrow(e -> new HoodieException("MAX_INSTANT_PARAM is invalid")));
      writeValueAsString(ctx, dtos);
    }, true));

    app.get(RemoteHoodieTableFileSystemView.LATEST_SLICES_BEFORE_ON_INSTANT_URL, new ViewHandler(ctx -> {
      metricsRegistry.add("LATEST_SLICES_BEFORE_ON_INSTANT", 1);
      List<FileSliceDTO> dtos = sliceHandler.getLatestFileSlicesBeforeOrOn(
          ctx.queryParamAsClass(RemoteHoodieTableFileSystemView.BASEPATH_PARAM, String.class).getOrThrow(e -> new HoodieException("Basepath is invalid")),
          ctx.queryParamAsClass(RemoteHoodieTableFileSystemView.PARTITION_PARAM, String.class).getOrDefault(""),
          ctx.queryParamAsClass(RemoteHoodieTableFileSystemView.MAX_INSTANT_PARAM, String.class).getOrThrow(e -> new HoodieException("MAX_INSTANT_PARAM is invalid")),
          Boolean.parseBoolean(
              ctx.queryParamAsClass(RemoteHoodieTableFileSystemView.INCLUDE_FILES_IN_PENDING_COMPACTION_PARAM, String.class)
                  .getOrThrow(e -> new HoodieException("INCLUDE_FILES_IN_PENDING_COMPACTION_PARAM is invalid"))));
      writeValueAsString(ctx, dtos);
    }, true));

    app.get(RemoteHoodieTableFileSystemView.ALL_LATEST_SLICES_BEFORE_ON_INSTANT_URL, new ViewHandler(ctx -> {
      metricsRegistry.add("ALL_LATEST_SLICES_BEFORE_ON_INSTANT", 1);
      Map<String, List<FileSliceDTO>> dtos = sliceHandler.getAllLatestFileSlicesBeforeOrOn(
          ctx.queryParamAsClass(RemoteHoodieTableFileSystemView.BASEPATH_PARAM, String.class).getOrThrow(e -> new HoodieException("Basepath is invalid")),
          ctx.queryParamAsClass(RemoteHoodieTableFileSystemView.MAX_INSTANT_PARAM, String.class).getOrThrow(e -> new HoodieException("MAX_INSTANT_PARAM is invalid")));
      writeValueAsString(ctx, dtos);
    }, true));

    app.get(RemoteHoodieTableFileSystemView.PENDING_COMPACTION_OPS, new ViewHandler(ctx -> {
      metricsRegistry.add("PEDING_COMPACTION_OPS", 1);
      List<CompactionOpDTO> dtos = sliceHandler.getPendingCompactionOperations(
          ctx.queryParamAsClass(RemoteHoodieTableFileSystemView.BASEPATH_PARAM, String.class).getOrThrow(e -> new HoodieException("Basepath is invalid")));
      writeValueAsString(ctx, dtos);
    }, true));

    app.get(RemoteHoodieTableFileSystemView.PENDING_LOG_COMPACTION_OPS, new ViewHandler(ctx -> {
      metricsRegistry.add("PEDING_LOG_COMPACTION_OPS", 1);
      List<CompactionOpDTO> dtos = sliceHandler.getPendingLogCompactionOperations(
          ctx.queryParamAsClass(RemoteHoodieTableFileSystemView.BASEPATH_PARAM, String.class).getOrThrow(e -> new HoodieException("Basepath is invalid")));
      writeValueAsString(ctx, dtos);
    }, true));

    app.get(RemoteHoodieTableFileSystemView.ALL_FILEGROUPS_FOR_PARTITION_URL, new ViewHandler(ctx -> {
      metricsRegistry.add("ALL_FILEGROUPS_FOR_PARTITION", 1);
      List<FileGroupDTO> dtos = sliceHandler.getAllFileGroups(
          ctx.queryParamAsClass(RemoteHoodieTableFileSystemView.BASEPATH_PARAM, String.class).getOrThrow(e -> new HoodieException("Basepath is invalid")),
          ctx.queryParamAsClass(RemoteHoodieTableFileSystemView.PARTITION_PARAM, String.class).getOrDefault(""));
      writeValueAsString(ctx, dtos);
    }, true));

    app.get(RemoteHoodieTableFileSystemView.ALL_FILEGROUPS_FOR_PARTITION_STATELESS_URL, new ViewHandler(ctx -> {
      metricsRegistry.add("ALL_FILEGROUPS_FOR_PARTITION_STATELESS", 1);
      List<FileGroupDTO> dtos = sliceHandler.getAllFileGroupsStateless(
          ctx.queryParamAsClass(RemoteHoodieTableFileSystemView.BASEPATH_PARAM, String.class).getOrThrow(e -> new HoodieException("Basepath is invalid")),
          ctx.queryParamAsClass(RemoteHoodieTableFileSystemView.PARTITION_PARAM, String.class).getOrDefault(""));
      writeValueAsString(ctx, dtos);
    }, true));

    app.post(RemoteHoodieTableFileSystemView.REFRESH_TABLE, new ViewHandler(ctx -> {
      metricsRegistry.add("REFRESH_TABLE", 1);
      boolean success = sliceHandler
          .refreshTable(ctx.queryParamAsClass(RemoteHoodieTableFileSystemView.BASEPATH_PARAM, String.class).getOrThrow(e -> new HoodieException("Basepath is invalid")));
      writeValueAsString(ctx, success);
    }, false));

    app.post(RemoteHoodieTableFileSystemView.LOAD_ALL_PARTITIONS_URL, new ViewHandler(ctx -> {
      metricsRegistry.add("LOAD_ALL_PARTITIONS", 1);
      boolean success = sliceHandler
          .loadAllPartitions(ctx.queryParamAsClass(RemoteHoodieTableFileSystemView.BASEPATH_PARAM, String.class).getOrThrow(e -> new HoodieException("Basepath is invalid")));
      writeValueAsString(ctx, success);
    }, false));

    app.get(RemoteHoodieTableFileSystemView.ALL_REPLACED_FILEGROUPS_BEFORE_OR_ON, new ViewHandler(ctx -> {
      metricsRegistry.add("ALL_REPLACED_FILEGROUPS_BEFORE_OR_ON", 1);
      List<FileGroupDTO> dtos = sliceHandler.getReplacedFileGroupsBeforeOrOn(
          ctx.queryParamAsClass(RemoteHoodieTableFileSystemView.BASEPATH_PARAM, String.class).getOrThrow(e -> new HoodieException("Basepath is invalid")),
          ctx.queryParamAsClass(RemoteHoodieTableFileSystemView.MAX_INSTANT_PARAM, String.class).getOrDefault(""),
          ctx.queryParamAsClass(RemoteHoodieTableFileSystemView.PARTITION_PARAM, String.class).getOrDefault(""));
      writeValueAsString(ctx, dtos);
    }, true));

    app.get(RemoteHoodieTableFileSystemView.ALL_REPLACED_FILEGROUPS_BEFORE, new ViewHandler(ctx -> {
      metricsRegistry.add("ALL_REPLACED_FILEGROUPS_BEFORE", 1);
      List<FileGroupDTO> dtos = sliceHandler.getReplacedFileGroupsBefore(
          ctx.queryParamAsClass(RemoteHoodieTableFileSystemView.BASEPATH_PARAM, String.class).getOrThrow(e -> new HoodieException("Basepath is invalid")),
          ctx.queryParamAsClass(RemoteHoodieTableFileSystemView.MAX_INSTANT_PARAM, String.class).getOrDefault(""),
          ctx.queryParamAsClass(RemoteHoodieTableFileSystemView.PARTITION_PARAM, String.class).getOrDefault(""));
      writeValueAsString(ctx, dtos);
    }, true));

    app.get(RemoteHoodieTableFileSystemView.ALL_REPLACED_FILEGROUPS_AFTER_OR_ON, new ViewHandler(ctx -> {
      metricsRegistry.add("ALL_REPLACED_FILEGROUPS_AFTER_OR_ON", 1);
      List<FileGroupDTO> dtos = sliceHandler.getReplacedFileGroupsAfterOrOn(
          ctx.queryParamAsClass(RemoteHoodieTableFileSystemView.BASEPATH_PARAM, String.class).getOrThrow(e -> new HoodieException("Basepath is invalid")),
          ctx.queryParamAsClass(RemoteHoodieTableFileSystemView.MIN_INSTANT_PARAM, String.class).getOrDefault(""),
          ctx.queryParamAsClass(RemoteHoodieTableFileSystemView.PARTITION_PARAM, String.class).getOrDefault(""));
      writeValueAsString(ctx, dtos);
    }, true));

    app.get(RemoteHoodieTableFileSystemView.ALL_REPLACED_FILEGROUPS_PARTITION, new ViewHandler(ctx -> {
      metricsRegistry.add("ALL_REPLACED_FILEGROUPS_PARTITION", 1);
      List<FileGroupDTO> dtos = sliceHandler.getAllReplacedFileGroups(
          ctx.queryParamAsClass(RemoteHoodieTableFileSystemView.BASEPATH_PARAM, String.class).getOrThrow(e -> new HoodieException("Basepath is invalid")),
          ctx.queryParamAsClass(RemoteHoodieTableFileSystemView.PARTITION_PARAM, String.class).getOrDefault(""));
      writeValueAsString(ctx, dtos);
    }, true));

    app.get(RemoteHoodieTableFileSystemView.PENDING_CLUSTERING_FILEGROUPS, new ViewHandler(ctx -> {
      metricsRegistry.add("PENDING_CLUSTERING_FILEGROUPS", 1);
      List<ClusteringOpDTO> dtos = sliceHandler.getFileGroupsInPendingClustering(
          ctx.queryParamAsClass(RemoteHoodieTableFileSystemView.BASEPATH_PARAM, String.class).getOrThrow(e -> new HoodieException("Basepath is invalid")));
      writeValueAsString(ctx, dtos);
    }, true));
  }

  private void registerMarkerAPI() {
    app.get(MarkerOperation.ALL_MARKERS_URL, new ViewHandler(ctx -> {
      metricsRegistry.add("ALL_MARKERS", 1);
      Set<String> markers = markerHandler.getAllMarkers(
          ctx.queryParamAsClass(MarkerOperation.MARKER_DIR_PATH_PARAM, String.class).getOrDefault(""));
      writeValueAsString(ctx, markers);
    }, false));

    app.get(MarkerOperation.CREATE_AND_MERGE_MARKERS_URL, new ViewHandler(ctx -> {
      metricsRegistry.add("CREATE_AND_MERGE_MARKERS", 1);
      Set<String> markers = markerHandler.getCreateAndMergeMarkers(
          ctx.queryParamAsClass(MarkerOperation.MARKER_DIR_PATH_PARAM, String.class).getOrDefault(""));
      writeValueAsString(ctx, markers);
    }, false));

    app.get(MarkerOperation.MARKERS_DIR_EXISTS_URL, new ViewHandler(ctx -> {
      metricsRegistry.add("MARKERS_DIR_EXISTS", 1);
      boolean exist = markerHandler.doesMarkerDirExist(
          ctx.queryParamAsClass(MarkerOperation.MARKER_DIR_PATH_PARAM, String.class).getOrDefault(""));
      writeValueAsString(ctx, exist);
    }, false));

    app.post(MarkerOperation.CREATE_MARKER_URL, new ViewHandler(ctx -> {
      metricsRegistry.add("CREATE_MARKER", 1);
      ctx.future(markerHandler.createMarker(
          ctx,
          ctx.queryParamAsClass(MarkerOperation.MARKER_DIR_PATH_PARAM, String.class).getOrDefault(""),
          ctx.queryParamAsClass(MarkerOperation.MARKER_NAME_PARAM, String.class).getOrDefault(""),
          ctx.queryParamAsClass(MarkerOperation.MARKER_BASEPATH_PARAM, String.class).getOrDefault("")));
    }, false));

    app.post(MarkerOperation.DELETE_MARKER_DIR_URL, new ViewHandler(ctx -> {
      metricsRegistry.add("DELETE_MARKER_DIR", 1);
      boolean success = markerHandler.deleteMarkers(
          ctx.queryParamAsClass(MarkerOperation.MARKER_DIR_PATH_PARAM, String.class).getOrDefault(""));
      writeValueAsString(ctx, success);
    }, false));
  }

  private void registerInstantStateAPI() {
    app.get(InstantStateHandler.ALL_INSTANT_STATE_URL, new ViewHandler(ctx -> {
      metricsRegistry.add("ALL_INSTANT_STATE", 1);
      List<InstantStateDTO> instantStates = instantStateHandler.getAllInstantStates(
          ctx.queryParam(InstantStateHandler.INSTANT_STATE_DIR_PATH_PARAM)
      );
      writeValueAsString(ctx, instantStates);
    }, false));

    app.post(InstantStateHandler.REFRESH_INSTANT_STATE, new ViewHandler(ctx -> {
      metricsRegistry.add("REFRESH_INSTANT_STATE", 1);
      boolean success = instantStateHandler.refresh(
          ctx.queryParam(InstantStateHandler.INSTANT_STATE_DIR_PATH_PARAM)
      );
      writeValueAsString(ctx, success);
    }, false));
  }

  /**
   * Determine whether to throw an exception when local view of table's timeline is behind that of client's view.
   */
  private boolean shouldThrowExceptionIfLocalViewBehind(HoodieTimeline localTimeline, String timelineHashFromClient) {
    Option<HoodieInstant> lastInstant = localTimeline.lastInstant();
    // When performing async clean, we may have one more .clean.completed after lastInstantTs.
    // In this case, we do not need to throw an exception.
    return !lastInstant.isPresent() || !lastInstant.get().getAction().equals(HoodieTimeline.CLEAN_ACTION)
        || !localTimeline.findInstantsBefore(lastInstant.get().getTimestamp()).getTimelineHash().equals(timelineHashFromClient);
  }

  /**
   * Used for logging and performing refresh check.
   */
  private class ViewHandler implements Handler {

    private final Handler handler;
    private final boolean performRefreshCheck;

    ViewHandler(Handler handler, boolean performRefreshCheck) {
      this.handler = handler;
      this.performRefreshCheck = performRefreshCheck;
    }

    @Override
    public void handle(@NotNull Context context) throws Exception {
      boolean success = true;
      long beginTs = System.currentTimeMillis();
      boolean synced = false;
      boolean refreshCheck = performRefreshCheck && !isRefreshCheckDisabledInQuery(context);
      long refreshCheckTimeTaken = 0;
      long handleTimeTaken = 0;
      long finalCheckTimeTaken = 0;
      try {
        if (refreshCheck) {
          long beginRefreshCheck = System.currentTimeMillis();
          synced = syncIfLocalViewBehind(context);
          long endRefreshCheck = System.currentTimeMillis();
          refreshCheckTimeTaken = endRefreshCheck - beginRefreshCheck;
        }

        long handleBeginMs = System.currentTimeMillis();
        handler.handle(context);
        long handleEndMs = System.currentTimeMillis();
        handleTimeTaken = handleEndMs - handleBeginMs;

        if (refreshCheck) {
          long beginFinalCheck = System.currentTimeMillis();
          if (isLocalViewBehind(context)) {
            String lastKnownInstantFromClient = context.queryParamAsClass(RemoteHoodieTableFileSystemView.LAST_INSTANT_TS, String.class).getOrDefault(HoodieTimeline.INVALID_INSTANT_TS);
            String timelineHashFromClient = context.queryParamAsClass(RemoteHoodieTableFileSystemView.TIMELINE_HASH, String.class).getOrDefault("");
            HoodieTimeline localTimeline =
                viewManager.getFileSystemView(context.queryParam(RemoteHoodieTableFileSystemView.BASEPATH_PARAM)).getTimeline();
            if (shouldThrowExceptionIfLocalViewBehind(localTimeline, timelineHashFromClient)) {
              String errMsg =
                  "Last known instant from client was "
                      + lastKnownInstantFromClient
                      + " but server has the following timeline "
                      + localTimeline.getInstants();
              throw new BadRequestResponse(errMsg);
            }
          }
          long endFinalCheck = System.currentTimeMillis();
          finalCheckTimeTaken = endFinalCheck - beginFinalCheck;
        }
      } catch (RuntimeException re) {
        success = false;
        if (re instanceof BadRequestResponse) {
          LOG.warn("Bad request response due to client view behind server view. " + re.getMessage());
        } else {
          LOG.error("Got runtime exception servicing request " + context.queryString(), re);
        }
        throw re;
      } finally {
        long endTs = System.currentTimeMillis();
        long timeTakenMillis = endTs - beginTs;
        metricsRegistry.add("TOTAL_API_TIME", timeTakenMillis);
        metricsRegistry.add("TOTAL_REFRESH_TIME", refreshCheckTimeTaken);
        metricsRegistry.add("TOTAL_HANDLE_TIME", handleTimeTaken);
        metricsRegistry.add("TOTAL_CHECK_TIME", finalCheckTimeTaken);
        metricsRegistry.add("TOTAL_API_CALLS", 1);

        LOG.debug(String.format(
            "TimeTakenMillis[Total=%d, Refresh=%d, handle=%d, Check=%d], "
                + "Success=%s, Query=%s, Host=%s, synced=%s",
            timeTakenMillis, refreshCheckTimeTaken, handleTimeTaken, finalCheckTimeTaken, success,
            context.queryString(), context.host(), synced));
      }
    }
  }
}
