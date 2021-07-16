package com.snowflake.kafka.connector.internal;

import static org.apache.kafka.common.record.TimestampType.NO_TIMESTAMP_TYPE;

import com.google.common.annotations.VisibleForTesting;
import com.snowflake.kafka.connector.SnowflakeSinkConnectorConfig;
import com.snowflake.kafka.connector.Utils;
import com.snowflake.kafka.connector.records.RecordService;
import com.snowflake.kafka.connector.records.SnowflakeJsonSchema;
import com.snowflake.kafka.connector.records.SnowflakeMetadataConfig;
import com.snowflake.kafka.connector.records.SnowflakeRecordContent;
import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.sink.SinkRecord;

class SnowflakeSinkServiceV1 extends Logging implements SnowflakeSinkService {
  private static final long ONE_HOUR = 60 * 60 * 1000L;
  private static final long TEN_MINUTES = 10 * 60 * 1000L;
  protected static final long CLEAN_TIME = 60 * 1000L; // one minutes

  private long flushTime; // in seconds
  private long fileSize;
  private long recordNum;
  private final SnowflakeConnectionService conn;
  private final Map<String, ServiceContext> pipes;
  private final RecordService recordService;
  private boolean isStopped;
  private final SnowflakeTelemetryService telemetryService;
  private Map<String, String> topic2TableMap;

  // Behavior to be set at the start of connector start. (For tombstone records)
  private SnowflakeSinkConnectorConfig.BehaviorOnNullValues behaviorOnNullValues;

  SnowflakeSinkServiceV1(SnowflakeConnectionService conn) {
    if (conn == null || conn.isClosed()) {
      throw SnowflakeErrors.ERROR_5010.getException();
    }

    this.fileSize = SnowflakeSinkConnectorConfig.BUFFER_SIZE_BYTES_DEFAULT;
    this.recordNum = SnowflakeSinkConnectorConfig.BUFFER_COUNT_RECORDS_DEFAULT;
    this.flushTime = SnowflakeSinkConnectorConfig.BUFFER_FLUSH_TIME_SEC_DEFAULT;
    this.pipes = new HashMap<>();
    this.conn = conn;
    this.recordService = new RecordService();
    isStopped = false;
    this.telemetryService = conn.getTelemetryClient();
    this.topic2TableMap = new HashMap<>();

    // Setting the default value in constructor
    // meaning it will not ignore the null values (Tombstone records wont be ignored/filtered)
    this.behaviorOnNullValues = SnowflakeSinkConnectorConfig.BehaviorOnNullValues.DEFAULT;
  }

  @Override
  public void startTask(final String tableName, final String topic, final int partition) {
    String stageName = Utils.stageName(conn.getConnectorName(), tableName);
    String nameIndex = getNameIndex(topic, partition);
    if (pipes.containsKey(nameIndex)) {
      logError("task is already registered, name: {}", nameIndex);
    } else {
      String pipeName = Utils.pipeName(conn.getConnectorName(), tableName, partition);

      pipes.put(nameIndex, new ServiceContext(tableName, stageName, pipeName, conn, partition));
    }
  }

  @Override
  public void insert(final Collection<SinkRecord> records) {
    // note that records can be empty
    for (SinkRecord record : records) {
      // check if need to handle null value records
      if (shouldSkipNullValue(record)) {
        continue;
      }
      // Might happen a count of record based flushing
      insert(record);
    }
    // check all sink context to see if they need to be flushed
    for (ServiceContext pipe : pipes.values()) {
      // Time based flushing
      if (pipe.shouldFlush()) {
        pipe.flushBuffer();
      }
    }
  }

  /**
   * Returns true if we want to skip this record since the value is null or it is an empty json
   * string.
   *
   * <p>Remember, we need to check what is the value schema. Depending on the value schema, we need
   * to find out if the value is null or empty JSON. It can be empty JSON string in case of custom
   * snowflake converters.
   *
   * <p>If the value is an empty JSON node, we could assume the value passed was null.
   *
   * @see com.snowflake.kafka.connector.records.SnowflakeJsonConverter#toConnectData when bytes ==
   *     null case
   * @param record record sent from Kafka to KC
   * @return true if we would skip adding it to buffer -> skip to internal stage and hence skipped
   *     inside SF Table
   */
  private boolean shouldSkipNullValue(SinkRecord record) {
    if (behaviorOnNullValues == SnowflakeSinkConnectorConfig.BehaviorOnNullValues.DEFAULT) {
      return false;
    } else {
      boolean isRecordValueNull = false;
      // get valueSchema
      Schema valueSchema = record.valueSchema();
      if (valueSchema instanceof SnowflakeJsonSchema) {
        // we can conclude this is a custom/KC defined converter.
        // i.e one of SFJson, SFAvro and SFAvroWithSchemaRegistry Converter
        if (record.value() instanceof SnowflakeRecordContent) {
          SnowflakeRecordContent recordValueContent = (SnowflakeRecordContent) record.value();
          if (recordValueContent.isRecordContentValueNull()) {
            logDebug(
                "Record value schema is:{} and value is Empty Json Node for topic {}, partition {}"
                    + " and offset {}",
                valueSchema.getClass().getName(),
                record.topic(),
                record.kafkaPartition(),
                record.kafkaOffset());
            isRecordValueNull = true;
          }
        }
      } else {
        // Else, it is one of the community converters.
        // Tombstone handler SMT can be used but we need to check here if value is null if SMT is
        // not used
        if (record.value() == null) {
          logDebug(
              "Record value is null for topic {}, partition {} and offset {}",
              record.topic(),
              record.kafkaPartition(),
              record.kafkaOffset());
          isRecordValueNull = true;
        }
      }
      if (isRecordValueNull) {
        logDebug(
            "Null valued record from topic '{}', partition {} and offset {} was skipped.",
            record.topic(),
            record.kafkaPartition(),
            record.kafkaOffset());
        return true;
      }
    }
    return false;
  }

  @Override
  public void insert(SinkRecord record) {
    String nameIndex = getNameIndex(record.topic(), record.kafkaPartition());
    // init a new topic partition
    if (!pipes.containsKey(nameIndex)) {
      logWarn(
          "Topic: {} Partition: {} hasn't been initialized by OPEN " + "function",
          record.topic(),
          record.kafkaPartition());
      startTask(
          Utils.tableName(record.topic(), this.topic2TableMap),
          record.topic(),
          record.kafkaPartition());
    }
    pipes.get(nameIndex).insert(record);
  }

  @Override
  public long getOffset(final TopicPartition topicPartition) {
    String name = getNameIndex(topicPartition.topic(), topicPartition.partition());
    if (pipes.containsKey(name)) {
      return pipes.get(name).getOffset();
    } else {
      logWarn(
          "Topic: {} Partition: {} hasn't been initialized to get offset",
          topicPartition.topic(),
          topicPartition.partition());
      return 0;
    }
  }

  @Override
  public int getPartitionCount() {
    return pipes.size();
  }

  // used for testing only
  @Override
  public void callAllGetOffset() {
    for (ServiceContext pipe : pipes.values()) {
      pipe.getOffset();
    }
  }

  @Override
  public void close(Collection<TopicPartition> partitions) {
    partitions.forEach(
        tp -> {
          String name = getNameIndex(tp.topic(), tp.partition());
          ServiceContext sc = pipes.remove(name);
          if (sc != null) {
            try {
              sc.close();
            } catch (Exception e) {
              logError(
                  "Failed to close sink service for Topic: {}, Partition: " + "{}\nMessage:{}",
                  tp.topic(),
                  tp.partition(),
                  e.getMessage());
            }
          } else {
            logWarn(
                "Failed to close sink service for Topic: {}, Partition: {}, "
                    + "sink service hasn't been initialized",
                tp.topic(),
                tp.partition());
          }
        });
  }

  @Override
  public void closeAll() {
    this.isStopped = true; // release all cleaner and flusher threads
    pipes.forEach((name, context) -> context.close());
    pipes.clear();
  }

  @Override
  public void setIsStoppedToTrue() {
    this.isStopped = true; // release all cleaner and flusher threads
  }

  @Override
  public boolean isClosed() {
    return this.isStopped;
  }

  @Override
  public void setRecordNumber(final long num) {
    if (num < 0) {
      logError("number of record in each file is {}, it is negative, reset to" + " 0");
      this.recordNum = 0;
    } else {
      this.recordNum = num;
      logInfo("set number of record limitation to {}", num);
    }
  }

  @Override
  public void setFileSize(final long size) {
    if (size < SnowflakeSinkConnectorConfig.BUFFER_SIZE_BYTES_MIN) {
      logError(
          "file size is {} bytes, it is smaller than the minimum file "
              + "size {} bytes, reset to the default file size",
          size,
          SnowflakeSinkConnectorConfig.BUFFER_SIZE_BYTES_DEFAULT);
      this.fileSize = SnowflakeSinkConnectorConfig.BUFFER_SIZE_BYTES_DEFAULT;
    } else {
      this.fileSize = size;
      logInfo("set file size limitation to {} bytes", size);
    }
  }

  @Override
  public void setFlushTime(final long time) {
    if (time < SnowflakeSinkConnectorConfig.BUFFER_FLUSH_TIME_SEC_MIN) {
      logError(
          "flush time is {} seconds, it is smaller than the minimum "
              + "flush time {} seconds, reset to the minimum flush time",
          time,
          SnowflakeSinkConnectorConfig.BUFFER_FLUSH_TIME_SEC_MIN);
      this.flushTime = SnowflakeSinkConnectorConfig.BUFFER_FLUSH_TIME_SEC_MIN;
    } else {
      this.flushTime = time;
      logInfo("set flush time to {} seconds", time);
    }
  }

  @Override
  public void setTopic2TableMap(Map<String, String> topic2TableMap) {
    this.topic2TableMap = topic2TableMap;
  }

  @Override
  public void setMetadataConfig(SnowflakeMetadataConfig configMap) {
    this.recordService.setMetadataConfig(configMap);
  }

  @Override
  public long getRecordNumber() {
    return this.recordNum;
  }

  @Override
  public long getFlushTime() {
    return this.flushTime;
  }

  @Override
  public long getFileSize() {
    return this.fileSize;
  }

  @Override
  public void setBehaviorOnNullValuesConfig(
      SnowflakeSinkConnectorConfig.BehaviorOnNullValues behavior) {
    this.behaviorOnNullValues = behavior;
  }

  @Override
  public SnowflakeSinkConnectorConfig.BehaviorOnNullValues getBehaviorOnNullValuesConfig() {
    return this.behaviorOnNullValues;
  }

  @VisibleForTesting
  protected static String getNameIndex(String topic, int partition) {
    return topic + "_" + partition;
  }

  private class ServiceContext {
    private final String tableName;
    private final String stageName;
    private final String pipeName;
    private final SnowflakeConnectionService conn;
    private final SnowflakeIngestionService ingestionService;
    private List<String> fileNames;

    // Includes a list of files:
    // 1. Which are added after a flush into internal stage is successful
    // 2. While an app restarts and we do list on an internal stage to find out what needs to be
    // done on leaked files.
    private List<String> cleanerFileNames;
    private PartitionBuffer buffer;
    private final String prefix;
    private final AtomicLong committedOffset; // loaded offset + 1
    private final AtomicLong flushedOffset; // flushed offset (file on stage)
    private final AtomicLong processedOffset; // processed offset
    private long previousFlushTimeStamp;

    // threads
    private final ExecutorService cleanerExecutor;
    private final ExecutorService reprocessCleanerExecutor;
    private final Lock bufferLock;
    private final Lock fileListLock;

    // telemetry
    private final SnowflakeTelemetryPipeStatus pipeStatus;

    // make the initialization lazy
    private boolean hasInitialized = false;
    private boolean forceCleanerFileReset = false;

    private ServiceContext(
        String tableName,
        String stageName,
        String pipeName,
        SnowflakeConnectionService conn,
        int partition) {
      this.pipeName = pipeName;
      this.tableName = tableName;
      this.stageName = stageName;
      this.conn = conn;
      this.fileNames = new LinkedList<>();
      this.cleanerFileNames = new LinkedList<>();
      this.buffer = new PartitionBuffer();
      this.ingestionService = conn.buildIngestService(stageName, pipeName);
      this.prefix = FileNameUtils.filePrefix(conn.getConnectorName(), tableName, partition);
      this.processedOffset = new AtomicLong(-1);
      this.flushedOffset = new AtomicLong(-1);
      this.committedOffset = new AtomicLong(0);
      this.previousFlushTimeStamp = System.currentTimeMillis();

      this.bufferLock = new ReentrantLock();
      this.fileListLock = new ReentrantLock();

      this.pipeStatus =
          new SnowflakeTelemetryPipeStatus(tableName, stageName, pipeName, conn.getConnectorName());

      this.cleanerExecutor = Executors.newSingleThreadExecutor();
      this.reprocessCleanerExecutor = Executors.newSingleThreadExecutor();

      logInfo("pipe: {} - service started", pipeName);
    }

    private void init(long recordOffset) {
      logInfo("init pipe: {}", pipeName);
      SnowflakeTelemetryPipeCreation pipeCreation =
          new SnowflakeTelemetryPipeCreation(
              tableName, stageName, pipeName, this.conn.getConnectorName());

      // wait for sinkConnector to start
      createTableAndStage(pipeCreation);
      // recover will only check pipe status and create pipe if it does not exist.
      recover(pipeCreation);

      try {
        startCleaner(recordOffset, pipeCreation);
        telemetryService.reportKafkaPipeStart(pipeCreation);
      } catch (Exception e) {
        logWarn("Cleaner and Flusher threads shut down before initialization");
      }
    }

    private boolean resetCleanerFiles() {
      try {
        logWarn("Resetting cleaner files {}", pipeName);
        pipeStatus.cleanerRestartCount.incrementAndGet();
        // list stage again and try to clean the files leaked on stage
        // this can throw unchecked, it needs to be wrapped in a try/catch
        // if it fails again do not reset forceCleanerFileReset
        List<String> tmpCleanerFileNames = conn.listStage(stageName, prefix);
        fileListLock.lock();
        try {
          cleanerFileNames.addAll(tmpCleanerFileNames);
          cleanerFileNames = cleanerFileNames.stream().distinct().collect(Collectors.toList());
        } finally {
          fileListLock.unlock();
        }
        forceCleanerFileReset = false;
        logWarn("Resetting cleaner files {} done", pipeName);
      } catch (Throwable t) {
        logWarn("Cleaner file reset encountered an error:\n{}", t.getMessage());
      }

      return forceCleanerFileReset;
    }

    // If there are files already on stage, we need to decide whether we will reprocess the offsets
    // or we will purge them.
    private void startCleaner(long recordOffset, SnowflakeTelemetryPipeCreation pipeCreation) {
      // When cleaner start, scan stage for all files of this pipe.
      // If we know that we are going to reprocess the file, then safely delete the file.
      List<String> currentFilesOnStage = conn.listStage(stageName, prefix);
      List<String> reprocessFiles = new ArrayList<>();

      filterFileReprocess(currentFilesOnStage, reprocessFiles, recordOffset);

      // Telemetry
      pipeCreation.fileCountRestart = currentFilesOnStage.size();
      pipeCreation.fileCountReprocessPurge = reprocessFiles.size();
      // Files left on stage must be on ingestion, otherwise offset won't be committed and
      // the file will be removed by the reprocess filter.
      pipeStatus.fileCountOnIngestion.addAndGet(currentFilesOnStage.size());
      pipeStatus.fileCountOnStage.addAndGet(currentFilesOnStage.size());

      fileListLock.lock();
      try {
        cleanerFileNames.addAll(currentFilesOnStage);
      } finally {
        fileListLock.unlock();
      }

      cleanerExecutor.submit(
          () -> {
            logInfo("pipe {}: cleaner started", pipeName);
            while (!isStopped) {
              try {
                telemetryService.reportKafkaPipeUsage(pipeStatus, false);
                Thread.sleep(CLEAN_TIME);

                if (forceCleanerFileReset && resetCleanerFiles()) {
                  continue;
                }

                checkStatus();
              } catch (InterruptedException e) {
                logInfo("Cleaner terminated by an interrupt:\n{}", e.getMessage());
                break;
              } catch (Exception e) {
                logWarn(
                    "Cleaner encountered an exception {}:\n{}\n{}",
                    e.getClass(),
                    e.getMessage(),
                    e.getStackTrace());
                telemetryService.reportKafkaFatalError(e.getMessage());
                forceCleanerFileReset = true;
              }
            }
          });

      if (reprocessFiles.size() > 0) {
        // After we start the cleaner thread, delay a while and start deleting files.
        reprocessCleanerExecutor.submit(
            () -> {
              try {
                Thread.sleep(CLEAN_TIME);
                purge(reprocessFiles);
              } catch (Exception e) {
                logError(
                    "Reprocess cleaner encountered an exception {}:\n{}\n{}",
                    e.getClass(),
                    e.getMessage(),
                    e.getStackTrace());
              }
            });
      }
    }

    /**
     * Does in place manipulation of passed currentFilesOnStage. The caller of this function passes
     * in the list of files(name) on the stage. (ls @stageName)
     *
     * <p>In return it expects the list of files (reprocessFiles) which is a subset of
     * currentFilesOnStage.
     *
     * <p>How do we find list of reprocessFiles?
     *
     * <p>1. Find out the start offset from the list of files currently on stage.
     *
     * <p>2. If the current offset passed by the connector is less than any of the start offset of
     * found files, we will reprocess this files and at the same time remove from
     * currentListOfFiles. (Idea being if the current offset is still found on stage, it is not
     * purged, so we will reprocess)
     *
     * @param currentFilesOnStage LIST.OF((ls @stageNAME))
     * @param reprocessFiles Empty but we will fill this.
     * @param recordOffset current offset
     */
    private void filterFileReprocess(
        List<String> currentFilesOnStage, List<String> reprocessFiles, long recordOffset) {
      // iterate over a copy since reprocess files get removed from it
      new LinkedList<>(currentFilesOnStage)
          .forEach(
              name -> {
                long fileStartOffset = FileNameUtils.fileNameToStartOffset(name);
                // If start offset of this file is greater than the offset of the record that is
                // sent to the connector,
                // all content of this file will be reprocessed. Thus this file can be deleted.
                if (recordOffset <= fileStartOffset) {
                  reprocessFiles.add(name);
                  currentFilesOnStage.remove(name);
                }
              });
    }

    private void stopCleaner() {
      cleanerExecutor.shutdownNow();
      reprocessCleanerExecutor.shutdownNow();
      logInfo("pipe {}: cleaner terminated", pipeName);
    }

    private void insert(final SinkRecord record) {
      // init pipe
      if (!hasInitialized) {
        // This will only be called once at the beginning when an offset arrives for first time
        // after connector starts/rebalance
        init(record.kafkaOffset());

        // register Mbean in MbeanServer for this pipe
        this.pipeStatus.registerMBean();
        this.hasInitialized = true;
      }

      // ignore ingested files
      if (record.kafkaOffset() > processedOffset.get()) {
        SinkRecord snowflakeRecord = record;
        if (shouldConvertContent(snowflakeRecord.value())) {
          snowflakeRecord = handleNativeRecord(snowflakeRecord, false);
        }
        if (shouldConvertContent(snowflakeRecord.key())) {
          snowflakeRecord = handleNativeRecord(snowflakeRecord, true);
        }

        // broken record
        if (isRecordBroken(snowflakeRecord)) {
          writeBrokenDataToTableStage(snowflakeRecord);
          // don't move committed offset in this case
          // only move it in the normal cases
        } else {
          // lag telemetry, note that sink record timestamp might be null
          if (snowflakeRecord.timestamp() != null
              && snowflakeRecord.timestampType() != NO_TIMESTAMP_TYPE) {
            pipeStatus.updateKafkaLag(System.currentTimeMillis() - snowflakeRecord.timestamp());
          }

          PartitionBuffer tmpBuff = null;
          bufferLock.lock();
          try {
            processedOffset.set(snowflakeRecord.kafkaOffset());
            pipeStatus.processedOffset.set(snowflakeRecord.kafkaOffset());
            buffer.insert(snowflakeRecord);
            if (buffer.getBufferSize() >= getFileSize()
                || (getRecordNumber() != 0 && buffer.getNumOfRecord() >= getRecordNumber())) {
              tmpBuff = buffer;
              this.buffer = new PartitionBuffer();
            }
          } finally {
            bufferLock.unlock();
          }

          if (tmpBuff != null) {
            flush(tmpBuff);
          }
        }
      }
    }

    private boolean shouldConvertContent(final Object content) {
      return content != null && !(content instanceof SnowflakeRecordContent);
    }

    private boolean isRecordBroken(final SinkRecord record) {
      return isContentBroken(record.value()) || isContentBroken(record.key());
    }

    private boolean isContentBroken(final Object content) {
      return content != null && ((SnowflakeRecordContent) content).isBroken();
    }

    private SinkRecord handleNativeRecord(SinkRecord record, boolean isKey) {
      SnowflakeRecordContent newSFContent;
      Schema schema = isKey ? record.keySchema() : record.valueSchema();
      Object content = isKey ? record.key() : record.value();
      try {
        newSFContent = new SnowflakeRecordContent(schema, content);
      } catch (Exception e) {
        logError("Native content parser error:\n{}", e.getMessage());
        try {
          // try to serialize this object and send that as broken record
          ByteArrayOutputStream out = new ByteArrayOutputStream();
          ObjectOutputStream os = new ObjectOutputStream(out);
          os.writeObject(content);
          newSFContent = new SnowflakeRecordContent(out.toByteArray());
        } catch (Exception serializeError) {
          logError(
              "Failed to convert broken native record to byte data:\n{}",
              serializeError.getMessage());
          throw e;
        }
      }
      // create new sinkRecord
      Schema keySchema = isKey ? new SnowflakeJsonSchema() : record.keySchema();
      Object keyContent = isKey ? newSFContent : record.key();
      Schema valueSchema = isKey ? record.valueSchema() : new SnowflakeJsonSchema();
      Object valueContent = isKey ? record.value() : newSFContent;
      return new SinkRecord(
          record.topic(),
          record.kafkaPartition(),
          keySchema,
          keyContent,
          valueSchema,
          valueContent,
          record.kafkaOffset(),
          record.timestamp(),
          record.timestampType(),
          record.headers());
    }

    private boolean shouldFlush() {
      return (System.currentTimeMillis() - this.previousFlushTimeStamp) >= (getFlushTime() * 1000);
    }

    private void flushBuffer() {
      // Just checking buffer size, no atomic operation required
      if (buffer.isEmpty()) {
        return;
      }
      PartitionBuffer tmpBuff;
      bufferLock.lock();
      try {
        tmpBuff = buffer;
        this.buffer = new PartitionBuffer();
      } finally {
        bufferLock.unlock();
      }
      flush(tmpBuff);
    }

    private void writeBrokenDataToTableStage(SinkRecord record) {
      SnowflakeRecordContent key = (SnowflakeRecordContent) record.key();
      SnowflakeRecordContent value = (SnowflakeRecordContent) record.value();
      if (key != null) {
        String fileName = FileNameUtils.brokenRecordFileName(prefix, record.kafkaOffset(), true);
        conn.putToTableStage(tableName, fileName, snowflakeContentToByteArray(key));
        pipeStatus.fileCountTableStageBrokenRecord.incrementAndGet();
      }
      if (value != null) {
        String fileName = FileNameUtils.brokenRecordFileName(prefix, record.kafkaOffset(), false);
        conn.putToTableStage(tableName, fileName, snowflakeContentToByteArray(value));
        pipeStatus.fileCountTableStageBrokenRecord.incrementAndGet();
      }
    }

    private byte[] snowflakeContentToByteArray(SnowflakeRecordContent content) {
      if (content == null) {
        return null;
      }
      if (content.isBroken()) {
        return content.getBrokenData();
      }
      return Arrays.asList(content.getData()).toString().getBytes();
    }

    private long getOffset() {
      if (fileNames.isEmpty()) {
        return committedOffset.get();
      }

      List<String> fileNamesCopy = new ArrayList<>();
      fileListLock.lock();
      try {
        fileNamesCopy.addAll(fileNames);
        fileNames = new LinkedList<>();
      } finally {
        fileListLock.unlock();
      }

      committedOffset.set(flushedOffset.get());
      // update telemetry data
      long currentTime = System.currentTimeMillis();
      pipeStatus.committedOffset.set(committedOffset.get() - 1);
      pipeStatus.fileCountOnIngestion.addAndGet(fileNamesCopy.size());
      fileNamesCopy.forEach(
          name ->
              pipeStatus.updateCommitLag(currentTime - FileNameUtils.fileNameToTimeIngested(name)));
      logInfo("pipe {}, ingest files: {}", pipeName, fileNamesCopy);

      // This api should throw exception if backoff failed. It also clears the input list
      ingestionService.ingestFiles(fileNamesCopy);

      return committedOffset.get();
    }

    private void flush(final PartitionBuffer buff) {
      if (buff == null || buff.isEmpty()) {
        return;
      }
      this.previousFlushTimeStamp = System.currentTimeMillis();

      // If we failed to submit/put, throw an runtime exception that kills the connector.
      // SnowflakeThreadPoolUtils.flusherThreadPool.submit(
      String fileName = FileNameUtils.fileName(prefix, buff.getFirstOffset(), buff.getLastOffset());
      String content = buff.getData();
      conn.putWithCache(stageName, fileName, content);

      // This is safe and atomic
      flushedOffset.updateAndGet((value) -> Math.max(buff.getLastOffset() + 1, value));
      pipeStatus.flushedOffset.set(flushedOffset.get() - 1);
      pipeStatus.fileCountOnStage.incrementAndGet(); // plus one
      pipeStatus.memoryUsage.set(0);

      fileListLock.lock();
      try {
        fileNames.add(fileName);
        cleanerFileNames.add(fileName);
      } finally {
        fileListLock.unlock();
      }

      logInfo("pipe {}, flush pipe: {}", pipeName, fileName);
    }

    private void checkStatus() {
      // We are using a temporary list which will reset the cleanerFileNames
      // After this checkStatus() call, we will have an updated cleanerFileNames which are subset of
      // existing cleanerFileNames
      // this time th
      List<String> tmpFileNames;

      fileListLock.lock();
      try {
        tmpFileNames = cleanerFileNames;
        cleanerFileNames = new LinkedList<>();
      } finally {
        fileListLock.unlock();
      }

      long currentTime = System.currentTimeMillis();
      List<String> loadedFiles = new LinkedList<>();
      List<String> failedFiles = new LinkedList<>();

      // ingest report
      // This will update the loadedFiles (successfully loaded) &
      // failedFiles: PARTIAL + FAILED
      // In any cases tmpFileNames will be updated.
      // If we get all files in ingestReport, tmpFileNames will be empty
      filterResultFromSnowpipeScan(
          ingestionService.readIngestReport(tmpFileNames), tmpFileNames, loadedFiles, failedFiles);

      // old files
      List<String> oldFiles = new LinkedList<>();

      // iterate over a copy since failed files get removed from it
      // Iterate over those files which were not found in ingest report call and are sitting more
      // than an hour earlier.
      // Also add those files into oldFiles which are not purged/found in ingestReport since last 10
      // minutes.
      new LinkedList<>(tmpFileNames)
          .forEach(
              name -> {
                long time = FileNameUtils.fileNameToTimeIngested(name);
                if (time < currentTime - ONE_HOUR) {
                  failedFiles.add(name);
                  tmpFileNames.remove(name);
                } else if (time < currentTime - TEN_MINUTES) {
                  oldFiles.add(name);
                }
              });
      // load history
      // Use loadHistoryScan API to scan last one hour of data and if filter files from above
      // filtered list.
      // This is the last filtering we do and after this, we start purging loadedFiles and moving
      // failedFiles to tableStage
      if (!oldFiles.isEmpty()) {
        filterResultFromSnowpipeScan(
            ingestionService.readOneHourHistory(tmpFileNames, currentTime - ONE_HOUR),
            tmpFileNames,
            loadedFiles,
            failedFiles);
      }

      purge(loadedFiles);
      moveToTableStage(failedFiles);

      fileListLock.lock();
      try {
        // Add back all those files which were neither found in ingestReport nor in loadHistoryScan
        cleanerFileNames.addAll(tmpFileNames);
      } finally {
        fileListLock.unlock();
      }

      // update purged offset in telemetry
      loadedFiles.forEach(
          name ->
              pipeStatus.purgedOffset.updateAndGet(
                  value -> Math.max(FileNameUtils.fileNameToEndOffset(name), value)));
      // update file count in telemetry
      int fileCountRevomedFromStage = loadedFiles.size() + failedFiles.size();
      pipeStatus.fileCountOnStage.addAndGet(-fileCountRevomedFromStage);
      pipeStatus.fileCountOnIngestion.addAndGet(-fileCountRevomedFromStage);
      pipeStatus.fileCountTableStageIngestFail.addAndGet(failedFiles.size());
      pipeStatus.fileCountPurged.addAndGet(loadedFiles.size());
      // update lag information
      loadedFiles.forEach(
          name ->
              pipeStatus.updateIngestionLag(
                  currentTime - FileNameUtils.fileNameToTimeIngested(name)));
    }

    // fileStatus Map may include mapping of fileNames with their ingestion status.
    // It can be received either from insertReport API or loadHistoryScan
    private void filterResultFromSnowpipeScan(
        Map<String, InternalUtils.IngestedFileStatus> fileStatus,
        List<String> allFiles,
        List<String> loadedFiles,
        List<String> failedFiles) {
      fileStatus.forEach(
          (name, status) -> {
            switch (status) {
              case LOADED:
                loadedFiles.add(name);
                allFiles.remove(name);
                break;
              case FAILED:
              case PARTIALLY_LOADED:
                failedFiles.add(name);
                allFiles.remove(name);
                break;
              default:
                // otherwise, do nothing
            }
          });
    }

    private void purge(List<String> files) {
      if (!files.isEmpty()) {
        conn.purgeStage(stageName, files);
      }
    }

    private void moveToTableStage(List<String> files) {
      if (!files.isEmpty()) {
        conn.moveToTableStage(tableName, stageName, files);
      }
    }

    private void recover(SnowflakeTelemetryPipeCreation pipeCreation) {
      if (conn.pipeExist(pipeName)) {
        if (!conn.isPipeCompatible(tableName, stageName, pipeName)) {
          throw SnowflakeErrors.ERROR_5005.getException(
              "pipe name: " + pipeName, conn.getTelemetryClient());
        }
        logInfo("pipe {}, recovered from existing pipe", pipeName);
        pipeCreation.isReusePipe = true;
      } else {
        conn.createPipe(tableName, stageName, pipeName);
      }
    }

    private void close() {
      try {
        stopCleaner();
      } catch (Exception e) {
        logWarn("Failed to terminate Cleaner or Flusher");
      }
      this.pipeStatus.unregisterMBean();
      ingestionService.close();
      telemetryService.reportKafkaPipeUsage(pipeStatus, true);
      logInfo("pipe {}: service closed", pipeName);
    }

    /**
     * SinkConnector ans SinkTasks start at the same time, however, SinkTasks need create table and
     * wait SinkConnector to create stage. This method checks table and stage existence for at most
     * 120 times(10 min) And then throws exceptions if table or stage doesn't exit
     */
    private void createTableAndStage(SnowflakeTelemetryPipeCreation pipeCreation) {
      // create table if not exists
      if (conn.tableExist(tableName)) {
        if (conn.isTableCompatible(tableName)) {
          logInfo("Using existing table {}.", tableName);
          pipeCreation.isReuseTable = true;
        } else {
          throw SnowflakeErrors.ERROR_5003.getException(
              "table name: " + tableName, telemetryService);
        }
      } else {
        logInfo("Creating new table {}.", tableName);
        conn.createTable(tableName);
      }

      if (conn.stageExist(stageName)) {
        if (conn.isStageCompatible(stageName)) {
          logInfo("Using existing stage {}.", stageName);
          pipeCreation.isReuseStage = true;
        } else {
          throw SnowflakeErrors.ERROR_5004.getException(
              "stage name: " + stageName, telemetryService);
        }
      } else {
        logInfo("Creating new stage {}.", stageName);
        conn.createStage(stageName);
      }
    }

    private boolean isBufferEmpty() {
      return this.buffer.isEmpty();
    }

    private class PartitionBuffer {
      private final StringBuilder stringBuilder;
      private int numOfRecord;
      private int bufferSize;
      private long firstOffset;
      private long lastOffset;

      private int getNumOfRecord() {
        return numOfRecord;
      }

      private int getBufferSize() {
        return bufferSize;
      }

      private long getFirstOffset() {
        return firstOffset;
      }

      private long getLastOffset() {
        return lastOffset;
      }

      private PartitionBuffer() {
        stringBuilder = new StringBuilder();
        numOfRecord = 0;
        bufferSize = 0;
        firstOffset = -1;
        lastOffset = -1;
      }

      private void insert(SinkRecord record) {
        String data = recordService.processRecord(record);
        if (bufferSize == 0) {
          firstOffset = record.kafkaOffset();
        }

        stringBuilder.append(data);
        numOfRecord++;
        bufferSize += data.length() * 2; // 1 char = 2 bytes
        pipeStatus.memoryUsage.addAndGet(data.length() * 2);
        lastOffset = record.kafkaOffset();
      }

      private boolean isEmpty() {
        return numOfRecord == 0;
      }

      private String getData() {
        String result = stringBuilder.toString();
        logDebug(
            "flush buffer: {} records, {} bytes, offset {} - {}",
            numOfRecord,
            bufferSize,
            firstOffset,
            lastOffset);
        pipeStatus.totalSizeOfData.addAndGet(this.bufferSize);
        pipeStatus.totalNumberOfRecord.addAndGet(this.numOfRecord);
        return result;
      }
    }
  }

  /**
   * Only used for testing Given a pipename, find out if buffer for this pipe has any data inserted.
   *
   * @param pipeName
   * @return
   */
  protected boolean isPartitionBufferEmpty(final String pipeName) {
    if (pipes.containsKey(pipeName)) {
      return pipes.get(pipeName).isBufferEmpty();
    }
    return false;
  }
}
