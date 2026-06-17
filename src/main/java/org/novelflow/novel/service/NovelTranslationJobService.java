package org.novelflow.novel.service;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.GraphLifecycleListener;
import com.alibaba.cloud.ai.graph.GraphRepresentation;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.file.FileSystemSaver;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.alibaba.cloud.ai.graph.state.StateSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.PostConstruct;
import org.novelflow.config.NovelGraphCheckpointProperties;
import org.novelflow.config.NovelTranslationProperties;
import org.novelflow.novel.dto.NovelGraphDiagramResponse;
import org.novelflow.novel.dto.NovelGraphHistoryResponse;
import org.novelflow.novel.dto.NovelGraphSnapshotResponse;
import org.novelflow.novel.dto.StartTranslationJobRequest;
import org.novelflow.novel.dto.TranslateChapterResponse;
import org.novelflow.novel.dto.TranslationJobResponse;
import org.novelflow.novel.dto.TranslationReadResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

@Service
public class NovelTranslationJobService {

    private static final Logger logger = LoggerFactory.getLogger(NovelTranslationJobService.class);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String NODE_INIT_JOB = "INIT_JOB";
    private static final String NODE_PREPARE_CHAPTER = "PREPARE_CHAPTER";
    private static final String NODE_BUILD_CHUNK_INPUT = "BUILD_CHUNK_INPUT";
    private static final String NODE_MODEL_TRANSLATE = "MODEL_TRANSLATE";
    private static final String NODE_POST_PROCESS_TRANSLATION = "POST_PROCESS_TRANSLATION";
    private static final String NODE_PERSIST_CHUNK = "PERSIST_CHUNK";
    private static final String NODE_RECORD_TERM_MEMORY = "RECORD_TERM_MEMORY";
    private static final String NODE_REVIEW_CHUNK = "REVIEW_CHUNK";
    private static final String NODE_FIX_CHUNK = "FIX_CHUNK";
    private static final String NODE_ACCEPT_CHUNK = "ACCEPT_CHUNK";
    private static final String NODE_REVIEW_CHAPTER = "REVIEW_CHAPTER";
    private static final String NODE_FINALIZE_JOB = "FINALIZE_JOB";

    private final NovelProjectService novelProjectService;
    private final NovelTranslationService novelTranslationService;
    private final NovelTranslationReviewService novelTranslationReviewService;
    private final NovelTermMemoryService novelTermMemoryService;
    private final NovelEventLogService eventLogService;
    private final NovelRuntimeStateService runtimeStateService;
    private final NovelTranslationProperties translationProperties;
    private final OllamaNovelTranslationClient translationClient;
    private final ObjectMapper objectMapper;
    private final ExecutorService executorService;
    private final BaseCheckpointSaver checkpointSaver;
    private final Path checkpointDirectory;
    private final String checkpointStore;
    private final String checkpointLocation;
    private final RedissonClient redissonClient;
    private final Map<String, RunningJob> runningJobs = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();
    private volatile CompiledGraph translationGraph;

    public NovelTranslationJobService(
            NovelProjectService novelProjectService,
            NovelTranslationService novelTranslationService,
            NovelTranslationReviewService novelTranslationReviewService,
            NovelTermMemoryService novelTermMemoryService,
            NovelEventLogService eventLogService,
            NovelRuntimeStateService runtimeStateService,
            NovelTranslationProperties translationProperties,
            OllamaNovelTranslationClient translationClient,
            ObjectMapper objectMapper,
            NovelGraphCheckpointProperties checkpointProperties) {
        this.novelProjectService = novelProjectService;
        this.novelTranslationService = novelTranslationService;
        this.novelTranslationReviewService = novelTranslationReviewService;
        this.novelTermMemoryService = novelTermMemoryService;
        this.eventLogService = eventLogService;
        this.runtimeStateService = runtimeStateService;
        this.translationProperties = translationProperties;
        this.translationClient = translationClient;
        this.objectMapper = objectMapper;
        this.executorService = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("novel-runtime-" + thread.getId());
            thread.setDaemon(true);
            return thread;
        });
        this.checkpointDirectory = novelProjectService.getWorkspaceRoot().resolve(".graph-checkpoints").normalize();
        CheckpointRuntime checkpointRuntime = createCheckpointRuntime(checkpointProperties);
        this.checkpointSaver = checkpointRuntime.saver();
        this.checkpointStore = checkpointRuntime.store();
        this.checkpointLocation = checkpointRuntime.location();
        this.redissonClient = checkpointRuntime.redissonClient();
        logger.info("NovelFlow Graph checkpoint saver initialized: store={}, location={}",
                checkpointStore, checkpointLocation);
    }

    @PostConstruct
    public void markInterruptedJobsOnStartup() {
        Path workspace = novelProjectService.getWorkspaceRoot();
        if (!Files.isDirectory(workspace)) {
            return;
        }
        int interruptedCount = 0;
        try (Stream<Path> projectStream = Files.list(workspace)) {
            for (Path projectDir : projectStream
                    .filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith("novel-"))
                    .toList()) {
                interruptedCount += markInterruptedJobs(projectDir);
            }
        } catch (IOException e) {
            logger.warn("扫描 NovelFlow 中断翻译任务失败: workspace={}, error={}",
                    workspace.toAbsolutePath().normalize(), e.getMessage());
        }
        if (interruptedCount > 0) {
            logger.warn("NovelFlow 检测到 {} 个服务停止前未结束的翻译任务，已标记为 INTERRUPTED", interruptedCount);
        }
    }

    public TranslationJobResponse startTranslationJob(String projectId, StartTranslationJobRequest request) {
        int chapterIndex = request == null || request.getChapterIndex() <= 0 ? 1 : request.getChapterIndex();
        int sourceStartOffset = request == null ? 0 : Math.max(0, request.getSourceStartOffset());
        int sourceCharLimit = request == null ? 0 : Math.max(0, request.getSourceCharLimit());
        Path projectDir = novelProjectService.resolveProjectDir(projectId);
        assertNoDuplicateRunningJob(projectId, chapterIndex);

        String jobId = "translation-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + "-" + UUID.randomUUID().toString().substring(0, 8);
        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        cancelFlags.put(jobId, cancelFlag);

        TranslationJobResponse response = newJobResponse(projectDir, jobId, projectId, chapterIndex, sourceStartOffset, sourceCharLimit);
        writeJob(projectDir, response);
        eventLogService.append(projectDir, "TRANSLATION_JOB_CREATED", "翻译任务已创建",
                Map.of("jobId", jobId, "chapterIndex", chapterIndex,
                        "jobType", response.getJobType(),
                        "sourceStartOffset", sourceStartOffset,
                        "sourceCharLimit", sourceCharLimit));

        Future<?> future = executorService.submit(() -> runGraphJob(projectDir, jobId, projectId, chapterIndex,
                sourceStartOffset, sourceCharLimit));
        runningJobs.put(jobId, new RunningJob(projectId, chapterIndex, future, cancelFlag));
        return response;
    }

    public TranslationJobResponse getJob(String projectId, String jobId) {
        Path projectDir = novelProjectService.resolveProjectDir(projectId);
        return readJob(projectDir, jobId);
    }

    public Optional<TranslationJobResponse> findLatestCompletedReadableJob(String projectId) {
        Path projectDir = novelProjectService.resolveProjectDir(projectId);
        Path jobsDir = projectDir.resolve("config").resolve("jobs");
        if (!Files.isDirectory(jobsDir)) {
            return Optional.empty();
        }
        try (Stream<Path> stream = Files.list(jobsDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted((left, right) -> Long.compare(lastModified(right), lastModified(left)))
                    .map(path -> safeReadJobFile(path, projectDir))
                    .filter(Objects::nonNull)
                    .filter(job -> "COMPLETED".equalsIgnoreCase(job.getStatus()))
                    .filter(job -> readableJobOutputPath(projectDir, job).isPresent())
                    .findFirst();
        } catch (IOException e) {
            logger.warn("查询最近可读取翻译任务失败: projectId={}, error={}", projectId, e.getMessage());
            return Optional.empty();
        }
    }

    public TranslationReadResponse readJobOutput(String projectId, String jobId, int offset, int limit) {
        Path projectDir = novelProjectService.resolveProjectDir(projectId);
        TranslationJobResponse job = readJob(projectDir, jobId);
        Path readPath = readableJobOutputPath(projectDir, job)
                .orElseThrow(() -> new IllegalArgumentException("翻译任务译文文件不存在或不可读取: " + jobId));

        int safeLimit = Math.max(200, Math.min(limit <= 0 ? 3000 : limit, 12000));
        try {
            String text = Files.readString(readPath, StandardCharsets.UTF_8).strip();
            int safeOffset = Math.max(0, Math.min(offset, text.length()));
            int nextOffset = Math.min(text.length(), safeOffset + safeLimit);
            TranslationReadResponse response = new TranslationReadResponse();
            response.setProjectId(projectId);
            response.setJobId(job.getJobId());
            response.setJobType(job.getJobType());
            response.setChapterIndex(job.getChapterIndex());
            response.setTitle(job.getTitle());
            response.setOutputFileName(readPath.getFileName().toString());
            response.setOutputPath(readPath.toString());
            response.setSourceStartOffset(job.getSourceStartOffset());
            response.setSourceEndOffset(job.getSourceEndOffset());
            response.setSourceCharLimit(job.getSourceCharLimit());
            response.setSourceCharCount(job.getSourceCharCount());
            response.setFullSourceCharCount(job.getFullSourceCharCount());
            response.setOffset(safeOffset);
            response.setNextOffset(nextOffset);
            response.setTotalChars(text.length());
            response.setHasMore(nextOffset < text.length());
            response.setContent(text.substring(safeOffset, nextOffset));
            return response;
        } catch (IOException e) {
            throw new IllegalStateException("读取翻译任务译文失败: " + e.getMessage(), e);
        }
    }

    private Optional<Path> readableJobOutputPath(Path projectDir, TranslationJobResponse job) {
        if (job == null) {
            return Optional.empty();
        }
        String outputPath = job.getOutputPath() == null ? "" : job.getOutputPath().trim();
        if (outputPath.isBlank()) {
            return Optional.empty();
        }

        Path projectRoot = projectDir.toAbsolutePath().normalize();
        Path rawOutputPath = Path.of(outputPath);
        Path resolvedOutputPath = (rawOutputPath.isAbsolute() ? rawOutputPath : projectRoot.resolve(rawOutputPath))
                .toAbsolutePath()
                .normalize();
        if (!resolvedOutputPath.startsWith(projectRoot)) {
            return Optional.empty();
        }

        Path polishedPath = projectRoot.resolve("polished")
                .resolve(resolvedOutputPath.getFileName().toString())
                .normalize();
        if (Files.isRegularFile(polishedPath)) {
            return Optional.of(polishedPath);
        }
        return Files.isRegularFile(resolvedOutputPath) ? Optional.of(resolvedOutputPath) : Optional.empty();
    }

    public Optional<TranslationJobResponse> findLatestInterruptedJob(String projectId) {
        Path projectDir = novelProjectService.resolveProjectDir(projectId);
        Path jobsDir = projectDir.resolve("config").resolve("jobs");
        if (!Files.isDirectory(jobsDir)) {
            return Optional.empty();
        }
        try (Stream<Path> stream = Files.list(jobsDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted((left, right) -> Long.compare(lastModified(right), lastModified(left)))
                    .map(path -> safeReadJobFile(path, projectDir))
                    .filter(Objects::nonNull)
                    .filter(job -> "INTERRUPTED".equalsIgnoreCase(job.getStatus()))
                    .findFirst();
        } catch (IOException e) {
            logger.warn("查询最新中断翻译任务失败: projectId={}, error={}", projectId, e.getMessage());
            return Optional.empty();
        }
    }

    public TranslationJobResponse cancelJob(String projectId, String jobId) {
        Path projectDir = novelProjectService.resolveProjectDir(projectId);
        TranslationJobResponse response = readJob(projectDir, jobId);
        if (isTerminal(response.getStatus())) {
            return response;
        }

        RunningJob runningJob = runningJobs.get(jobId);
        if (runningJob != null) {
            runningJob.cancelFlag().set(true);
            runningJob.future().cancel(true);
        }
        cancelFlags.computeIfAbsent(jobId, ignored -> new AtomicBoolean(true)).set(true);

        updateJob(projectDir, jobId, job -> {
            job.setStatus("CANCEL_REQUESTED");
            job.setMessage("已请求取消，当前分段会尽快停止");
            job.setUpdatedAt(now());
        });
        eventLogService.append(projectDir, "TRANSLATION_JOB_CANCEL_REQUESTED", "翻译任务请求取消",
                Map.of("jobId", jobId, "chapterIndex", response.getChapterIndex()));
        return readJob(projectDir, jobId);
    }

    public TranslationJobResponse retryJob(String projectId, String jobId) {
        Path projectDir = novelProjectService.resolveProjectDir(projectId);
        TranslationJobResponse previous = readJob(projectDir, jobId);
        StartTranslationJobRequest request = new StartTranslationJobRequest();
        request.setChapterIndex(previous.getChapterIndex());
        request.setSourceStartOffset(previous.getSourceStartOffset());
        request.setSourceCharLimit(previous.getSourceCharLimit());
        TranslationJobResponse retry = startTranslationJob(projectId, request);
        eventLogService.append(projectDir, "TRANSLATION_JOB_RETRIED", "翻译任务已重试",
                Map.of("previousJobId", jobId, "newJobId", retry.getJobId(), "chapterIndex", previous.getChapterIndex()));
        return retry;
    }

    public TranslationJobResponse forkJobFromCheckpoint(String projectId, String sourceJobId, String checkpointId) throws Exception {
        Path projectDir = novelProjectService.resolveProjectDir(projectId);
        TranslationJobResponse sourceJob = readJob(projectDir, sourceJobId);
        String safeCheckpointId = requireCheckpointId(checkpointId);

        RunnableConfig sourceConfig = RunnableConfig.builder()
                .threadId(sourceJobId)
                .checkPointId(safeCheckpointId)
                .build();
        Checkpoint sourceCheckpoint = checkpointSaver.get(sourceConfig)
                .orElseThrow(() -> new IllegalArgumentException("Graph checkpoint 不存在: " + safeCheckpointId));

        if (sourceCheckpoint.getNextNodeId() == null
                || sourceCheckpoint.getNextNodeId().isBlank()
                || StateGraph.END.equals(sourceCheckpoint.getNextNodeId())) {
            throw new IllegalArgumentException("不能从终态 checkpoint 恢复，请选择 FINALIZE_JOB 前的历史 checkpoint");
        }

        Map<String, Object> checkpointState = sourceCheckpoint.getState() == null
                ? Map.of()
                : sourceCheckpoint.getState();
        int chapterIndex = intState(checkpointState, "chapterIndex", sourceJob.getChapterIndex());
        int sourceStartOffset = intState(checkpointState, "sourceStartOffset", sourceJob.getSourceStartOffset());
        int sourceCharLimit = intState(checkpointState, "sourceCharLimit", sourceJob.getSourceCharLimit());
        assertNoDuplicateRunningJob(projectId, chapterIndex);

        String newJobId = "translation-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + "-" + UUID.randomUUID().toString().substring(0, 8);
        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        cancelFlags.put(newJobId, cancelFlag);

        TranslationJobResponse response = newJobResponse(projectDir, newJobId, projectId, chapterIndex,
                sourceStartOffset, sourceCharLimit);
        response.setForkedFromJobId(sourceJobId);
        response.setForkedFromCheckpointId(safeCheckpointId);
        response.setCurrentNode("CHECKPOINT_RESTORE");
        response.setMessage("等待从 Graph checkpoint 恢复执行");
        hydrateJobFromCheckpoint(response, checkpointState);
        writeJob(projectDir, response);

        Map<String, Object> stateUpdates = new LinkedHashMap<>();
        stateUpdates.put("jobId", newJobId);
        stateUpdates.put("projectId", projectId);
        stateUpdates.put("chapterIndex", chapterIndex);
        stateUpdates.put("sourceStartOffset", Math.max(0, sourceStartOffset));
        stateUpdates.put("sourceCharLimit", Math.max(0, sourceCharLimit));
        stateUpdates.put("forkedFromJobId", sourceJobId);
        stateUpdates.put("forkedFromCheckpointId", safeCheckpointId);
        stateUpdates.put("timeTravelFork", true);
        stateUpdates.put("_graph_execution_id_", UUID.randomUUID().toString());

        Checkpoint forkCheckpoint = Checkpoint.copyOf(sourceCheckpoint)
                .updateState(stateUpdates, translationGraph().getKeyStrategyMap());
        RunnableConfig forkConfig = checkpointSaver.put(
                RunnableConfig.builder().threadId(newJobId).build(),
                forkCheckpoint);
        String restoredCheckpointId = forkConfig.checkPointId().orElse(forkCheckpoint.getId());
        RunnableConfig resumeConfig = RunnableConfig.builder(forkConfig)
                .threadId(newJobId)
                .checkPointId(restoredCheckpointId)
                .build();

        updateJob(projectDir, newJobId, job -> {
            job.setRestoredCheckpointId(restoredCheckpointId);
            job.setUpdatedAt(now());
        });
        eventLogService.append(projectDir, "TRANSLATION_JOB_FORKED_FROM_CHECKPOINT", "已从 Graph checkpoint 派生翻译任务",
                Map.of("sourceJobId", sourceJobId,
                        "sourceCheckpointId", safeCheckpointId,
                        "newJobId", newJobId,
                        "restoredCheckpointId", restoredCheckpointId,
                        "chapterIndex", chapterIndex,
                        "nextNode", sourceCheckpoint.getNextNodeId()));

        Future<?> future = executorService.submit(() -> runGraphCheckpointJob(projectDir, newJobId, projectId,
                chapterIndex, resumeConfig));
        runningJobs.put(newJobId, new RunningJob(projectId, chapterIndex, future, cancelFlag));
        return readJob(projectDir, newJobId);
    }

    public NovelGraphSnapshotResponse getGraphState(String projectId, String jobId) throws Exception {
        Path projectDir = novelProjectService.resolveProjectDir(projectId);
        readJob(projectDir, jobId);
        Optional<StateSnapshot> snapshot = translationGraph().stateOf(runnableConfig(jobId));
        if (snapshot.isEmpty()) {
            throw new IllegalArgumentException("Graph checkpoint 不存在: " + jobId);
        }
        return toSnapshotResponse(jobId, snapshot.get());
    }

    public NovelGraphHistoryResponse getGraphHistory(String projectId, String jobId) throws Exception {
        Path projectDir = novelProjectService.resolveProjectDir(projectId);
        readJob(projectDir, jobId);
        Collection<StateSnapshot> snapshots = translationGraph().getStateHistory(runnableConfig(jobId));
        NovelGraphHistoryResponse response = new NovelGraphHistoryResponse();
        response.setJobId(jobId);
        response.setCheckpointStore(checkpointStore);
        response.setCheckpointLocation(checkpointLocation);
        response.setCheckpointDirectory("file".equals(checkpointStore)
                ? checkpointDirectory.toAbsolutePath().normalize().toString()
                : null);
        response.setSnapshots(snapshots.stream()
                .map(snapshot -> toSnapshotResponse(jobId, snapshot))
                .toList());
        response.setTotalSnapshots(response.getSnapshots().size());
        return response;
    }

    public NovelGraphDiagramResponse getTranslationGraphDiagram() throws Exception {
        GraphRepresentation representation = translationGraph()
                .getGraph(GraphRepresentation.Type.MERMAID, "NovelFlow Translation Runtime", false);
        NovelGraphDiagramResponse response = new NovelGraphDiagramResponse();
        response.setGraphName("NovelFlow Translation Runtime");
        response.setType(representation.type().name());
        response.setContent(representation.content());
        return response;
    }

    @PreDestroy
    public void shutdown() {
        executorService.shutdownNow();
        if (redissonClient != null && !redissonClient.isShutdown()) {
            redissonClient.shutdown();
        }
    }

    private void runGraphJob(
            Path projectDir,
            String jobId,
            String projectId,
            int chapterIndex,
            int sourceStartOffset,
            int sourceCharLimit) {
        try {
            translationGraph().invoke(Map.of(
                    "jobId", jobId,
                    "projectId", projectId,
                    "chapterIndex", chapterIndex,
                    "sourceStartOffset", Math.max(0, sourceStartOffset),
                    "sourceCharLimit", Math.max(0, sourceCharLimit)
            ), runnableConfig(jobId));
        } catch (Exception e) {
            if (isCancelRequested(jobId) || e instanceof NovelTranslationCancelledException) {
                markCancelled(projectDir, jobId, e.getMessage());
                logger.info("小说翻译任务已取消: projectId={}, jobId={}, chapterIndex={}", projectId, jobId, chapterIndex);
            } else {
                markFailed(projectDir, jobId, e);
                logger.error("小说翻译任务失败: projectId={}, jobId={}, chapterIndex={}", projectId, jobId, chapterIndex, e);
            }
        } finally {
            runningJobs.remove(jobId);
            cancelFlags.remove(jobId);
        }
    }

    private void runGraphCheckpointJob(
            Path projectDir,
            String jobId,
            String projectId,
            int chapterIndex,
            RunnableConfig resumeConfig) {
        try {
            translationGraph().invoke(Map.of(), resumeConfig);
        } catch (Exception e) {
            if (isCancelRequested(jobId) || e instanceof NovelTranslationCancelledException) {
                markCancelled(projectDir, jobId, e.getMessage());
                logger.info("小说翻译 checkpoint 恢复任务已取消: projectId={}, jobId={}, chapterIndex={}",
                        projectId, jobId, chapterIndex);
            } else {
                markFailed(projectDir, jobId, e);
                logger.error("小说翻译 checkpoint 恢复任务失败: projectId={}, jobId={}, chapterIndex={}",
                        projectId, jobId, chapterIndex, e);
            }
        } finally {
            runningJobs.remove(jobId);
            cancelFlags.remove(jobId);
        }
    }

    private int markInterruptedJobs(Path projectDir) {
        Path jobsDir = projectDir.resolve("config").resolve("jobs");
        if (!Files.isDirectory(jobsDir)) {
            return 0;
        }
        int count = 0;
        try (Stream<Path> jobStream = Files.list(jobsDir)) {
            for (Path jobPath : jobStream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .toList()) {
                TranslationJobResponse job;
                try {
                    job = objectMapper.readValue(jobPath.toFile(), TranslationJobResponse.class);
                } catch (IOException e) {
                    logger.warn("读取 NovelFlow 翻译任务失败，跳过中断检测: file={}, error={}",
                            jobPath.toAbsolutePath().normalize(), e.getMessage());
                    continue;
                }
                if (!isRecoverableStaleStatus(job.getStatus())) {
                    continue;
                }
                job.setStatus("INTERRUPTED");
                job.setMessage("服务在任务运行中停止，任务已中断；可查看 Graph history 后从 checkpoint 派生恢复任务");
                job.setErrorMessage("Process stopped before the Graph job reached a terminal state");
                job.setUpdatedAt(now());
                writeJob(projectDir, job);
                try {
                    eventLogService.append(projectDir, "TRANSLATION_JOB_INTERRUPTED", "检测到服务重启前未结束的翻译任务",
                            Map.of("jobId", job.getJobId(),
                                    "chapterIndex", job.getChapterIndex(),
                                    "jobType", job.getJobType() == null ? "" : job.getJobType(),
                                    "status", "INTERRUPTED"));
                } catch (Exception e) {
                    logger.debug("写入 NovelFlow 中断任务事件失败: jobId={}, error={}", job.getJobId(), e.getMessage());
                }
                count++;
            }
        } catch (IOException e) {
            logger.warn("扫描 NovelFlow 翻译任务目录失败: jobsDir={}, error={}",
                    jobsDir.toAbsolutePath().normalize(), e.getMessage());
        }
        return count;
    }

    private CompiledGraph translationGraph() throws Exception {
        CompiledGraph current = translationGraph;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (translationGraph == null) {
                translationGraph = buildTranslationGraph();
            }
            return translationGraph;
        }
    }

    private CompiledGraph buildTranslationGraph() throws Exception {
        Map<String, KeyStrategy> strategies = new LinkedHashMap<>();
        strategies.put("jobId", KeyStrategy.REPLACE);
        strategies.put("projectId", KeyStrategy.REPLACE);
        strategies.put("chapterIndex", KeyStrategy.REPLACE);
        strategies.put("jobType", KeyStrategy.REPLACE);
        strategies.put("title", KeyStrategy.REPLACE);
        strategies.put("sourceFileName", KeyStrategy.REPLACE);
        strategies.put("sourceStartOffset", KeyStrategy.REPLACE);
        strategies.put("sourceEndOffset", KeyStrategy.REPLACE);
        strategies.put("sourceCharLimit", KeyStrategy.REPLACE);
        strategies.put("sourceCharCount", KeyStrategy.REPLACE);
        strategies.put("fullSourceCharCount", KeyStrategy.REPLACE);
        strategies.put("currentNode", KeyStrategy.REPLACE);
        strategies.put("chapterPlan", KeyStrategy.REPLACE);
        strategies.put("currentChunkIndex", KeyStrategy.REPLACE);
        strategies.put("completedChunks", KeyStrategy.REPLACE);
        strategies.put("totalChunks", KeyStrategy.REPLACE);
        strategies.put("translatedText", KeyStrategy.REPLACE);
        strategies.put("previousSourceText", KeyStrategy.REPLACE);
        strategies.put("previousTranslatedText", KeyStrategy.REPLACE);
        strategies.put("chunkInput", KeyStrategy.REPLACE);
        strategies.put("chunkSourceText", KeyStrategy.REPLACE);
        strategies.put("chunkGlossaryPrompt", KeyStrategy.REPLACE);
        strategies.put("rawTranslatedText", KeyStrategy.REPLACE);
        strategies.put("postProcessedTranslatedText", KeyStrategy.REPLACE);
        strategies.put("outputPath", KeyStrategy.REPLACE);
        strategies.put("translatedCharCount", KeyStrategy.REPLACE);
        strategies.put("latestChunkIndex", KeyStrategy.REPLACE);
        strategies.put("latestSourceText", KeyStrategy.REPLACE);
        strategies.put("latestTranslatedText", KeyStrategy.REPLACE);
        strategies.put("reviewIssueCount", KeyStrategy.REPLACE);
        strategies.put("latestReviewStatus", KeyStrategy.REPLACE);
        strategies.put("latestReviewSummary", KeyStrategy.REPLACE);
        strategies.put("latestReviewPath", KeyStrategy.REPLACE);
        strategies.put("latestReviewNeedsFix", KeyStrategy.REPLACE);
        strategies.put("latestFixStatus", KeyStrategy.REPLACE);
        strategies.put("latestFixSummary", KeyStrategy.REPLACE);
        strategies.put("latestFixPath", KeyStrategy.REPLACE);
        strategies.put("reviewUnresolvedCount", KeyStrategy.REPLACE);
        strategies.put("acceptedChunks", KeyStrategy.REPLACE);
        strategies.put("chapterSourceText", KeyStrategy.REPLACE);
        strategies.put("chapterReviewStatus", KeyStrategy.REPLACE);
        strategies.put("chapterReviewSummary", KeyStrategy.REPLACE);
        strategies.put("chapterReviewPath", KeyStrategy.REPLACE);
        strategies.put("chapterReviewIssueCount", KeyStrategy.REPLACE);
        strategies.put("chapterReviewApplied", KeyStrategy.REPLACE);
        strategies.put("chapterPolishedText", KeyStrategy.REPLACE);
        strategies.put("chapterPolishedCharCount", KeyStrategy.REPLACE);
        strategies.put("termMemoryPath", KeyStrategy.REPLACE);
        strategies.put("forkedFromJobId", KeyStrategy.REPLACE);
        strategies.put("forkedFromCheckpointId", KeyStrategy.REPLACE);
        strategies.put("timeTravelFork", KeyStrategy.REPLACE);

        StateGraph graph = new StateGraph(() -> strategies);
        graph.addNode(NODE_INIT_JOB, AsyncNodeAction.node_async(this::initJobNode));
        graph.addNode(NODE_PREPARE_CHAPTER, AsyncNodeAction.node_async(this::prepareChapterNode));
        graph.addNode(NODE_BUILD_CHUNK_INPUT, AsyncNodeAction.node_async(this::buildChunkInputNode));
        graph.addNode(NODE_MODEL_TRANSLATE, AsyncNodeAction.node_async(this::modelTranslateNode));
        graph.addNode(NODE_POST_PROCESS_TRANSLATION, AsyncNodeAction.node_async(this::postProcessTranslationNode));
        graph.addNode(NODE_PERSIST_CHUNK, AsyncNodeAction.node_async(this::persistChunkNode));
        graph.addNode(NODE_RECORD_TERM_MEMORY, AsyncNodeAction.node_async(this::recordTermMemoryNode));
        graph.addNode(NODE_REVIEW_CHUNK, AsyncNodeAction.node_async(this::reviewChunkNode));
        graph.addNode(NODE_FIX_CHUNK, AsyncNodeAction.node_async(this::fixChunkNode));
        graph.addNode(NODE_ACCEPT_CHUNK, AsyncNodeAction.node_async(this::acceptChunkNode));
        graph.addNode(NODE_REVIEW_CHAPTER, AsyncNodeAction.node_async(this::reviewChapterNode));
        graph.addNode(NODE_FINALIZE_JOB, AsyncNodeAction.node_async(this::finalizeJobNode));
        graph.addEdge(StateGraph.START, NODE_INIT_JOB);
        graph.addEdge(NODE_INIT_JOB, NODE_PREPARE_CHAPTER);
        graph.addEdge(NODE_PREPARE_CHAPTER, NODE_BUILD_CHUNK_INPUT);
        graph.addEdge(NODE_BUILD_CHUNK_INPUT, NODE_MODEL_TRANSLATE);
        graph.addEdge(NODE_MODEL_TRANSLATE, NODE_POST_PROCESS_TRANSLATION);
        graph.addEdge(NODE_POST_PROCESS_TRANSLATION, NODE_PERSIST_CHUNK);
        graph.addEdge(NODE_PERSIST_CHUNK, NODE_RECORD_TERM_MEMORY);
        graph.addConditionalEdges(NODE_RECORD_TERM_MEMORY, AsyncEdgeAction.edge_async(this::routeAfterRecordTermMemory), Map.of(
                "chunk_review", NODE_REVIEW_CHUNK,
                "accept", NODE_ACCEPT_CHUNK
        ));
        graph.addConditionalEdges(NODE_REVIEW_CHUNK, AsyncEdgeAction.edge_async(this::routeAfterReview), Map.of(
                "fix", NODE_FIX_CHUNK,
                "accept", NODE_ACCEPT_CHUNK
        ));
        graph.addEdge(NODE_FIX_CHUNK, NODE_ACCEPT_CHUNK);
        graph.addConditionalEdges(NODE_ACCEPT_CHUNK, AsyncEdgeAction.edge_async(this::routeAfterChunk), Map.of(
                "continue", NODE_BUILD_CHUNK_INPUT,
                "finish", NODE_REVIEW_CHAPTER
        ));
        graph.addEdge(NODE_REVIEW_CHAPTER, NODE_FINALIZE_JOB);
        graph.addEdge(NODE_FINALIZE_JOB, StateGraph.END);
        CompileConfig compileConfig = CompileConfig.builder()
                .saverConfig(SaverConfig.builder().register(checkpointSaver).build())
                .withLifecycleListener(graphLoggingListener())
                .build();
        CompiledGraph compiledGraph = graph.compile(compileConfig);
        compiledGraph.setMaxIterations(10000);
        return compiledGraph;
    }

    private GraphLifecycleListener graphLoggingListener() {
        return new GraphLifecycleListener() {
            @Override
            public void onStart(String nodeId, Map<String, Object> state, RunnableConfig config) {
                logGraphRuntime("START", nodeId, state, config, null, null);
            }

            @Override
            public void before(String nodeId, Map<String, Object> state, RunnableConfig config, Long timestamp) {
                logGraphRuntime("BEFORE", nodeId, state, config, timestamp, null);
            }

            @Override
            public void after(String nodeId, Map<String, Object> state, RunnableConfig config, Long timestamp) {
                logGraphRuntime("AFTER", nodeId, state, config, timestamp, null);
            }

            @Override
            public void onComplete(String nodeId, Map<String, Object> state, RunnableConfig config) {
                logGraphRuntime("COMPLETE", nodeId, state, config, null, null);
            }

            @Override
            public void onError(String nodeId, Map<String, Object> state, Throwable throwable, RunnableConfig config) {
                logGraphRuntime("ERROR", nodeId, state, config, null, throwable);
            }
        };
    }

    private void logGraphRuntime(
            String phase,
            String nodeId,
            Map<String, Object> state,
            RunnableConfig config,
            Long timestamp,
            Throwable throwable) {
        String jobId = stringState(state, "jobId", config.threadId().orElse(""));
        String projectId = stringState(state, "projectId", "");
        int chapterIndex = intState(state, "chapterIndex", 0);
        String jobType = stringState(state, "jobType", "");
        int sourceStartOffset = intState(state, "sourceStartOffset", 0);
        int sourceEndOffset = intState(state, "sourceEndOffset", 0);
        int sourceCharLimit = intState(state, "sourceCharLimit", 0);
        int currentChunkIndex = intState(state, "currentChunkIndex", 0);
        int completedChunks = intState(state, "completedChunks", 0);
        int totalChunks = intState(state, "totalChunks", 0);
        int latestChunkIndex = intState(state, "latestChunkIndex", 0);
        int reviewIssueCount = intState(state, "reviewIssueCount", 0);
        int reviewUnresolvedCount = intState(state, "reviewUnresolvedCount", 0);
        int acceptedChunks = intState(state, "acceptedChunks", 0);
        int translatedCharCount = intState(state, "translatedCharCount", 0);
        String outputPath = stringState(state, "outputPath", "");
        String reviewStatus = stringState(state, "latestReviewStatus", "");
        String fixStatus = stringState(state, "latestFixStatus", "");
        String reviewSummary = stringState(state, "latestReviewSummary", "");
        String fixSummary = stringState(state, "latestFixSummary", "");
        String chapterReviewStatus = stringState(state, "chapterReviewStatus", "");
        String chapterReviewSummary = stringState(state, "chapterReviewSummary", "");
        int chapterReviewIssueCount = intState(state, "chapterReviewIssueCount", 0);
        int chapterPolishedCharCount = intState(state, "chapterPolishedCharCount", 0);
        String checkpointPath = checkpointLocation(jobId);

        if (throwable == null) {
            logger.info(
                    "NovelFlow Graph {}: node={}, jobId={}, projectId={}, chapter={}, jobType={}, sourceRange={}-{}, sourceLimit={}, currentChunk={}, completed={}/{}, latestChunk={}, acceptedChunks={}, reviewIssues={}, unresolvedReviews={}, reviewStatus={}, fixStatus={}, reviewSummary={}, fixSummary={}, chapterReviewStatus={}, chapterReviewIssues={}, chapterReviewSummary={}, chapterPolishedChars={}, translatedChars={}, checkpoint={}, output={}, timestamp={}",
                    phase,
                    nodeId,
                    jobId,
                    projectId,
                    chapterIndex,
                    jobType,
                    sourceStartOffset,
                    sourceEndOffset,
                    sourceCharLimit,
                    currentChunkIndex,
                    completedChunks,
                    totalChunks,
                    latestChunkIndex,
                    acceptedChunks,
                    reviewIssueCount,
                    reviewUnresolvedCount,
                    reviewStatus,
                    fixStatus,
                    reviewSummary,
                    fixSummary,
                    chapterReviewStatus,
                    chapterReviewIssueCount,
                    chapterReviewSummary,
                    chapterPolishedCharCount,
                    translatedCharCount,
                    checkpointPath,
                    outputPath,
                    timestamp == null ? "-" : timestamp
            );
        } else {
            logger.error(
                    "NovelFlow Graph {}: node={}, jobId={}, projectId={}, chapter={}, jobType={}, sourceRange={}-{}, sourceLimit={}, currentChunk={}, completed={}/{}, latestChunk={}, reviewIssues={}, reviewStatus={}, fixStatus={}, reviewSummary={}, fixSummary={}, chapterReviewStatus={}, chapterReviewIssues={}, chapterReviewSummary={}, checkpoint={}, error={}",
                    phase,
                    nodeId,
                    jobId,
                    projectId,
                    chapterIndex,
                    jobType,
                    sourceStartOffset,
                    sourceEndOffset,
                    sourceCharLimit,
                    currentChunkIndex,
                    completedChunks,
                    totalChunks,
                    latestChunkIndex,
                    reviewIssueCount,
                    reviewStatus,
                    fixStatus,
                    reviewSummary,
                    fixSummary,
                    chapterReviewStatus,
                    chapterReviewIssueCount,
                    chapterReviewSummary,
                    checkpointPath,
                    throwable.getMessage(),
                    throwable
            );
        }
    }

    private String stringState(Map<String, Object> state, String key, String fallback) {
        if (state == null) {
            return fallback;
        }
        Object value = state.get(key);
        return value == null ? fallback : value.toString();
    }

    private int intState(Map<String, Object> state, String key, int fallback) {
        if (state == null) {
            return fallback;
        }
        Object value = state.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private Map<String, Object> initJobNode(OverAllState state) {
        JobInput input = readInput(state);
        Path projectDir = novelProjectService.resolveProjectDir(input.projectId());
        String termMemoryPath = novelTermMemoryService.memoryPath(input.projectId());
        novelTermMemoryService.load(input.projectId());
        updateJob(projectDir, input.jobId(), job -> {
            job.setStatus("RUNNING");
            job.setCurrentNode(NODE_INIT_JOB);
            job.setMessage(input.excerpt() ? "片段翻译任务初始化" : "翻译任务初始化");
            job.setStartedAt(now());
            job.setUpdatedAt(now());
        });
        runtimeStateService.markCompleted(projectDir, input.projectId(), NovelRuntimeStateService.NODE_BUILD_TERMS,
                "术语记忆已准备完成", Map.of(
                        "jobId", input.jobId(),
                        "jobType", input.jobType(),
                        "termMemoryPath", termMemoryPath));
        runtimeStateService.markRunning(projectDir, input.projectId(), NovelRuntimeStateService.NODE_TRANSLATE_CHAPTER,
                input.excerpt() ? "片段翻译任务已启动" : "章节翻译任务已启动",
                Map.of(
                        "jobId", input.jobId(),
                        "jobType", input.jobType(),
                        "chapterIndex", input.chapterIndex(),
                        "sourceStartOffset", input.sourceStartOffset(),
                        "sourceCharLimit", input.sourceCharLimit()));
        eventLogService.append(projectDir, "TRANSLATION_JOB_STARTED", "翻译任务已启动",
                Map.of(
                        "jobId", input.jobId(),
                        "jobType", input.jobType(),
                        "chapterIndex", input.chapterIndex(),
                        "sourceStartOffset", input.sourceStartOffset(),
                        "sourceCharLimit", input.sourceCharLimit()));
        eventLogService.append(projectDir, "TERMS_MEMORY_READY", "术语记忆已准备完成",
                Map.of("jobId", input.jobId(), "termMemoryPath", termMemoryPath));
        return Map.of(
                "currentNode", NODE_INIT_JOB,
                "termMemoryPath", termMemoryPath,
                "jobType", input.jobType(),
                "sourceStartOffset", input.sourceStartOffset(),
                "sourceCharLimit", input.sourceCharLimit());
    }

    private Map<String, Object> prepareChapterNode(OverAllState state) {
        JobInput input = readInput(state);
        throwIfCancelled(input.jobId());

        Path projectDir = novelProjectService.resolveProjectDir(input.projectId());
        updateJob(projectDir, input.jobId(), job -> {
            job.setStatus("RUNNING");
            job.setCurrentNode(NODE_PREPARE_CHAPTER);
            job.setMessage("正在准备章节分段");
            job.setCurrentChunkIndex(0);
            job.setCompletedChunks(0);
            job.setUpdatedAt(now());
        });

        NovelTranslationService.RuntimeChapterPlan plan;
        int sourceStartOffset;
        int sourceEndOffset;
        int sourceCharLimit;
        int fullSourceCharCount;
        if (input.excerpt()) {
            NovelTranslationService.RuntimeChapterExcerptPlan excerptPlan =
                    novelTranslationService.prepareRuntimeChapterExcerpt(
                            input.projectId(),
                            input.chapterIndex(),
                            input.sourceStartOffset(),
                            input.sourceCharLimit());
            plan = excerptPlan.plan();
            sourceStartOffset = excerptPlan.startSourceOffset();
            sourceEndOffset = excerptPlan.endSourceOffset();
            sourceCharLimit = excerptPlan.requestedSourceCharLimit();
            fullSourceCharCount = excerptPlan.fullSourceCharCount();
        } else {
            plan = novelTranslationService.prepareRuntimeChapter(input.projectId(), input.chapterIndex());
            sourceStartOffset = 0;
            sourceEndOffset = plan.sourceCharCount();
            sourceCharLimit = 0;
            fullSourceCharCount = plan.sourceCharCount();
        }
        int sourceCharCount = plan.sourceCharCount();
        updateJob(projectDir, input.jobId(), job -> {
            job.setJobType(input.jobType());
            job.setTitle(plan.title());
            job.setSourceFileName(plan.sourceFileName());
            job.setSourceStartOffset(sourceStartOffset);
            job.setSourceEndOffset(sourceEndOffset);
            job.setSourceCharLimit(sourceCharLimit);
            job.setSourceCharCount(sourceCharCount);
            job.setFullSourceCharCount(fullSourceCharCount);
            job.setTotalChunks(plan.totalChunks());
            job.setCurrentChunkIndex(1);
            job.setCompletedChunks(0);
            job.setOutputPath(plan.outputPath());
            job.setUpdatedAt(now());
        });
        runtimeStateService.markRunning(projectDir, input.projectId(), NovelRuntimeStateService.NODE_TRANSLATE_CHUNK,
                "正在执行分段翻译", Map.of(
                        "jobId", input.jobId(),
                        "jobType", input.jobType(),
                        "chapterIndex", input.chapterIndex(),
                        "sourceStartOffset", sourceStartOffset,
                        "sourceEndOffset", sourceEndOffset,
                        "sourceCharLimit", sourceCharLimit,
                        "sourceCharCount", sourceCharCount,
                        "fullSourceCharCount", fullSourceCharCount,
                        "totalChunks", plan.totalChunks(),
                        "completedChunks", 0
                ));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobType", input.jobType());
        result.put("title", plan.title());
        result.put("sourceFileName", plan.sourceFileName());
        result.put("sourceStartOffset", sourceStartOffset);
        result.put("sourceEndOffset", sourceEndOffset);
        result.put("sourceCharLimit", sourceCharLimit);
        result.put("sourceCharCount", sourceCharCount);
        result.put("fullSourceCharCount", fullSourceCharCount);
        result.put("chapterPlan", plan);
        result.put("currentChunkIndex", 1);
        result.put("completedChunks", 0);
        result.put("totalChunks", plan.totalChunks());
        result.put("translatedText", "");
        result.put("previousSourceText", "");
        result.put("previousTranslatedText", "");
        result.put("outputPath", plan.outputPath());
        result.put("translatedCharCount", 0);
        result.put("latestChunkIndex", 0);
        result.put("latestSourceText", "");
        result.put("latestTranslatedText", "");
        result.put("reviewIssueCount", 0);
        result.put("latestReviewNeedsFix", false);
        result.put("reviewUnresolvedCount", 0);
        result.put("acceptedChunks", 0);
        result.put("chapterSourceText", novelTranslationService.runtimeChapterSourceText(plan));
        result.put("chapterReviewStatus", "");
        result.put("chapterReviewSummary", "");
        result.put("chapterReviewPath", "");
        result.put("chapterReviewIssueCount", 0);
        result.put("chapterReviewApplied", false);
        result.put("chapterPolishedText", "");
        result.put("chapterPolishedCharCount", 0);
        result.put("currentNode", NODE_PREPARE_CHAPTER);
        return result;
    }

    private Map<String, Object> buildChunkInputNode(OverAllState state) {
        JobInput input = readInput(state);
        throwIfCancelled(input.jobId());

        NovelTranslationService.RuntimeChapterPlan plan = chapterPlan(state);
        int chunkIndex = state.value("currentChunkIndex", 1);
        int totalChunks = plan.totalChunks();
        Path projectDir = novelProjectService.resolveProjectDir(input.projectId());
        updateJob(projectDir, input.jobId(), job -> {
            job.setStatus("RUNNING");
            job.setCurrentNode(NODE_BUILD_CHUNK_INPUT);
            job.setMessage("正在构造分段输入 " + chunkIndex + "/" + totalChunks);
            job.setCurrentChunkIndex(chunkIndex);
            job.setCompletedChunks(Math.max(0, chunkIndex - 1));
            job.setTotalChunks(totalChunks);
            job.setUpdatedAt(now());
        });

        NovelTranslationService.RuntimeChunkInput chunkInput = novelTranslationService.buildRuntimeChunkInput(
                plan,
                chunkIndex,
                state.value("previousSourceText", ""),
                state.value("previousTranslatedText", "")
        );

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("chunkInput", chunkInput);
        result.put("chunkSourceText", chunkInput.sourceText());
        result.put("chunkGlossaryPrompt", chunkInput.glossaryPrompt());
        result.put("rawTranslatedText", "");
        result.put("postProcessedTranslatedText", "");
        result.put("currentNode", NODE_BUILD_CHUNK_INPUT);
        runtimeStateService.markRunning(projectDir, input.projectId(), NovelRuntimeStateService.NODE_TRANSLATE_CHUNK,
                "分段输入已构造 " + chunkIndex + "/" + totalChunks,
                Map.of(
                        "jobId", input.jobId(),
                        "chapterIndex", input.chapterIndex(),
                        "chunkIndex", chunkIndex,
                        "glossaryPromptChars", chunkInput.glossaryPrompt().length()
                ));
        return result;
    }

    private Map<String, Object> modelTranslateNode(OverAllState state) {
        JobInput input = readInput(state);
        throwIfCancelled(input.jobId());

        NovelTranslationService.RuntimeChapterPlan plan = chapterPlan(state);
        NovelTranslationService.RuntimeChunkInput chunkInput = chunkInput(state);
        Path projectDir = novelProjectService.resolveProjectDir(input.projectId());
        updateJob(projectDir, input.jobId(), job -> {
            job.setStatus("RUNNING");
            job.setCurrentNode(NODE_MODEL_TRANSLATE);
            job.setMessage("正在调用翻译模型 " + chunkInput.chunkIndex() + "/" + plan.totalChunks());
            job.setCurrentChunkIndex(chunkInput.chunkIndex());
            job.setUpdatedAt(now());
        });

        String rawTranslatedText = novelTranslationService.modelTranslateRuntimeChunk(
                chunkInput,
                () -> isCancelRequested(input.jobId())
        );
        logger.info("NovelFlow Agent model output: jobId={}, projectId={}, chapter={}, chunk={}/{}, rawChars={}",
                input.jobId(), input.projectId(), input.chapterIndex(), chunkInput.chunkIndex(), plan.totalChunks(),
                rawTranslatedText == null ? 0 : rawTranslatedText.length());
        return Map.of(
                "rawTranslatedText", rawTranslatedText == null ? "" : rawTranslatedText,
                "currentNode", NODE_MODEL_TRANSLATE
        );
    }

    private Map<String, Object> postProcessTranslationNode(OverAllState state) {
        JobInput input = readInput(state);
        throwIfCancelled(input.jobId());

        NovelTranslationService.RuntimeChunkInput chunkInput = chunkInput(state);
        String rawTranslatedText = state.value("rawTranslatedText", "");
        String processedTranslatedText = novelTranslationService.postProcessRuntimeChunk(rawTranslatedText);
        logger.info("NovelFlow Agent post-process: jobId={}, projectId={}, chapter={}, chunk={}, rawChars={}, processedChars={}",
                input.jobId(), input.projectId(), input.chapterIndex(), chunkInput.chunkIndex(),
                rawTranslatedText.length(), processedTranslatedText == null ? 0 : processedTranslatedText.length());
        return Map.of(
                "postProcessedTranslatedText", processedTranslatedText == null ? "" : processedTranslatedText,
                "currentNode", NODE_POST_PROCESS_TRANSLATION
        );
    }

    private Map<String, Object> persistChunkNode(OverAllState state) {
        JobInput input = readInput(state);
        throwIfCancelled(input.jobId());

        NovelTranslationService.RuntimeChapterPlan plan = chapterPlan(state);
        NovelTranslationService.RuntimeChunkInput chunkInput = chunkInput(state);
        int chunkIndex = chunkInput.chunkIndex();
        int totalChunks = plan.totalChunks();
        Path projectDir = novelProjectService.resolveProjectDir(input.projectId());

        NovelTranslationService.RuntimeChunkResult chunkResult = novelTranslationService.persistRuntimeChunk(
                plan,
                chunkIndex,
                state.value("translatedText", ""),
                state.value("postProcessedTranslatedText", ""),
                () -> isCancelRequested(input.jobId())
        );

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("translatedText", chunkResult.translatedText());
        result.put("previousSourceText", chunkResult.previousSourceText());
        result.put("previousTranslatedText", chunkResult.previousTranslatedText());
        result.put("completedChunks", chunkResult.completedChunks());
        result.put("currentChunkIndex", chunkIndex + 1);
        result.put("translatedCharCount", chunkResult.translatedCharCount());
        result.put("outputPath", plan.outputPath());
        result.put("latestChunkIndex", chunkIndex);
        result.put("latestSourceText", chunkInput.sourceText());
        result.put("latestTranslatedText", chunkResult.latestChunkText());
        result.put("currentNode", NODE_PERSIST_CHUNK);
        updateJob(projectDir, input.jobId(), job -> {
            job.setCurrentChunkIndex(Math.min(chunkIndex + 1, totalChunks));
            job.setCompletedChunks(chunkResult.completedChunks());
            job.setTotalChunks(totalChunks);
            job.setOutputPath(plan.outputPath());
            job.setUpdatedAt(now());
        });
        runtimeStateService.markRunning(projectDir, input.projectId(), NovelRuntimeStateService.NODE_TRANSLATE_CHUNK,
                "分段翻译进度 " + chunkResult.completedChunks() + "/" + totalChunks,
                Map.of(
                        "jobId", input.jobId(),
                        "chapterIndex", input.chapterIndex(),
                        "completedChunks", chunkResult.completedChunks(),
                        "totalChunks", totalChunks,
                        "latestChunkTranslatedCharCount", chunkResult.latestChunkTranslatedCharCount()
                ));
        return result;
    }

    private Map<String, Object> recordTermMemoryNode(OverAllState state) {
        JobInput input = readInput(state);
        throwIfCancelled(input.jobId());

        NovelTranslationService.RuntimeChapterPlan plan = chapterPlan(state);
        NovelTranslationService.RuntimeChunkInput chunkInput = chunkInput(state);
        String translatedText = state.value("latestTranslatedText", "");
        novelTranslationService.recordRuntimeChunkTerms(plan, chunkInput.chunkIndex(), chunkInput.sourceText(), translatedText);
        logger.info("NovelFlow Agent term memory recorded: jobId={}, projectId={}, chapter={}, chunk={}, translatedChars={}",
                input.jobId(), input.projectId(), input.chapterIndex(), chunkInput.chunkIndex(), translatedText.length());
        return Map.of("currentNode", NODE_RECORD_TERM_MEMORY);
    }

    private String routeAfterRecordTermMemory(OverAllState state) {
        return isChunkReviewEnabled() ? "chunk_review" : "accept";
    }

    private Map<String, Object> reviewChunkNode(OverAllState state) {
        JobInput input = readInput(state);
        throwIfCancelled(input.jobId());

        NovelTranslationService.RuntimeChapterPlan plan = chapterPlan(state);
        int latestChunkIndex = state.value("latestChunkIndex", 0);
        if (latestChunkIndex <= 0) {
            return Map.of("currentNode", NODE_REVIEW_CHUNK);
        }

        Path projectDir = novelProjectService.resolveProjectDir(input.projectId());
        NovelTranslationReviewService.ReviewResult reviewResult = novelTranslationReviewService.reviewChunk(
                plan,
                latestChunkIndex,
                state.value("latestSourceText", ""),
                state.value("latestTranslatedText", "")
        );
        int issueCount = reviewResult.issueCount();
        Map<String, Object> metadata = Map.of(
                "jobId", input.jobId(),
                "chapterIndex", input.chapterIndex(),
                "chunkIndex", latestChunkIndex,
                "issueCount", issueCount,
                "reviewPath", reviewResult.reviewPath(),
                "summary", reviewResult.summary());
        if (reviewResult.issueCount() > 0) {
            runtimeStateService.markNeedReview(projectDir, input.projectId(), NovelRuntimeStateService.NODE_REVIEW_CHUNK,
                    reviewResult.needsFix() ? "审校发现问题，准备修订" : "审校发现问题，但自动修订未启用", metadata);
        } else {
            runtimeStateService.markCompleted(projectDir, input.projectId(), NovelRuntimeStateService.NODE_REVIEW_CHUNK,
                    "审校通过 " + latestChunkIndex + "/" + plan.totalChunks(), metadata);
        }

        return Map.of(
                "reviewIssueCount", issueCount,
                "latestReviewStatus", reviewResult.status(),
                "latestReviewSummary", reviewResult.summary(),
                "latestReviewPath", reviewResult.reviewPath(),
                "latestReviewNeedsFix", reviewResult.needsFix(),
                "currentNode", NODE_REVIEW_CHUNK
        );
    }

    private Map<String, Object> fixChunkNode(OverAllState state) {
        JobInput input = readInput(state);
        throwIfCancelled(input.jobId());

        NovelTranslationService.RuntimeChapterPlan plan = chapterPlan(state);
        int latestChunkIndex = state.value("latestChunkIndex", 0);
        if (latestChunkIndex <= 0) {
            return Map.of("currentNode", NODE_FIX_CHUNK);
        }

        Path projectDir = novelProjectService.resolveProjectDir(input.projectId());
        NovelTranslationReviewService.ReviewResult reviewResult = novelTranslationReviewService.readReviewResult(plan, latestChunkIndex);
        if (reviewResult == null) {
            reviewResult = new NovelTranslationReviewService.ReviewResult(
                    plan.projectId(),
                    plan.chapterIndex(),
                    latestChunkIndex,
                    "NEED_REVIEW",
                    List.of(),
                    reviewFilePath(projectDir, plan.sourceFileName(), latestChunkIndex),
                    now(),
                    "审校结果缺失",
                    false,
                    "ERROR",
                    "");
        }

        NovelTranslationReviewService.FixResult fixResult = novelTranslationReviewService.fixChunk(
                plan,
                latestChunkIndex,
                state.value("latestSourceText", ""),
                state.value("latestTranslatedText", ""),
                reviewResult,
                () -> isCancelRequested(input.jobId())
        );

        Map<String, Object> metadata = Map.of(
                "jobId", input.jobId(),
                "chapterIndex", input.chapterIndex(),
                "chunkIndex", latestChunkIndex,
                "fixStatus", fixResult.status(),
                "fixPath", fixResult.fixPath(),
                "summary", fixResult.summary());
        if (fixResult.applied()) {
            runtimeStateService.markCompleted(projectDir, input.projectId(), NovelRuntimeStateService.NODE_FIX_CHUNK,
                    "审校修订完成", metadata);
            runtimeStateService.markCompleted(projectDir, input.projectId(), NovelRuntimeStateService.NODE_REVIEW_CHUNK,
                    "审校问题已修订", metadata);
        } else {
            runtimeStateService.markNeedReview(projectDir, input.projectId(), NovelRuntimeStateService.NODE_FIX_CHUNK,
                    "审校修订未能应用", metadata);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("translatedText", fixResult.applied()
                ? fixResult.translatedText()
                : state.value("translatedText", ""));
        result.put("previousSourceText", tail(state.value("latestSourceText", ""), translationProperties.getContextSourceChars()));
        result.put("previousTranslatedText", tail(fixResult.revisedText(), translationProperties.getContextTranslationChars()));
        result.put("completedChunks", latestChunkIndex);
        result.put("currentChunkIndex", state.value("currentChunkIndex", latestChunkIndex + 1));
        result.put("reviewIssueCount", fixResult.applied() ? 0 : state.value("reviewIssueCount", 0));
        result.put("latestTranslatedText", fixResult.revisedText());
        result.put("latestReviewStatus", fixResult.applied() ? "FIXED" : reviewResult.status());
        result.put("latestReviewSummary", fixResult.summary());
        result.put("latestReviewNeedsFix", false);
        result.put("latestFixStatus", fixResult.status());
        result.put("latestFixSummary", fixResult.summary());
        result.put("latestFixPath", fixResult.fixPath());
        result.put("currentNode", NODE_FIX_CHUNK);
        return result;
    }

    private String routeAfterReview(OverAllState state) {
        int issueCount = state.value("reviewIssueCount", 0);
        boolean needsFix = state.value("latestReviewNeedsFix", false);
        if (issueCount > 0 && needsFix && translationProperties.isFixEnabled()) {
            return "fix";
        }
        return "accept";
    }

    private Map<String, Object> acceptChunkNode(OverAllState state) {
        JobInput input = readInput(state);
        throwIfCancelled(input.jobId());

        NovelTranslationService.RuntimeChapterPlan plan = chapterPlan(state);
        int latestChunkIndex = state.value("latestChunkIndex", 0);
        int issueCount = state.value("reviewIssueCount", 0);
        int acceptedChunks = state.value("acceptedChunks", 0);
        int unresolvedCount = state.value("reviewUnresolvedCount", 0);
        String reviewStatus = state.value("latestReviewStatus", "");
        String fixStatus = state.value("latestFixStatus", "");
        String summary = state.value("latestReviewSummary", "");
        Path projectDir = novelProjectService.resolveProjectDir(input.projectId());

        Map<String, Object> metadata = Map.of(
                "jobId", input.jobId(),
                "chapterIndex", input.chapterIndex(),
                "chunkIndex", latestChunkIndex,
                "reviewStatus", reviewStatus,
                "fixStatus", fixStatus,
                "issueCount", issueCount,
                "summary", summary);

        boolean accepted = issueCount == 0 || "FIXED".equalsIgnoreCase(reviewStatus)
                || "APPLIED".equalsIgnoreCase(fixStatus);
        if (accepted) {
            acceptedChunks++;
            runtimeStateService.markCompleted(projectDir, input.projectId(), NovelRuntimeStateService.NODE_ACCEPT_CHUNK,
                    "分段已接受 " + acceptedChunks + "/" + plan.totalChunks(), metadata);
        } else {
            unresolvedCount++;
            runtimeStateService.markNeedReview(projectDir, input.projectId(), NovelRuntimeStateService.NODE_ACCEPT_CHUNK,
                    "分段需要人工确认", metadata);
        }

        return Map.of(
                "acceptedChunks", acceptedChunks,
                "reviewUnresolvedCount", unresolvedCount,
                "currentNode", NODE_ACCEPT_CHUNK
        );
    }

    private String routeAfterChunk(OverAllState state) {
        NovelTranslationService.RuntimeChapterPlan plan = chapterPlan(state);
        int nextChunkIndex = state.value("currentChunkIndex", 1);
        return nextChunkIndex <= plan.totalChunks() ? "continue" : "finish";
    }

    private Map<String, Object> reviewChapterNode(OverAllState state) {
        JobInput input = readInput(state);
        throwIfCancelled(input.jobId());

        NovelTranslationService.RuntimeChapterPlan plan = chapterPlan(state);
        Path projectDir = novelProjectService.resolveProjectDir(input.projectId());
        String translatedText = state.value("translatedText", "");
        String sourceText = state.value("chapterSourceText", "");
        if (sourceText.isBlank()) {
            sourceText = novelTranslationService.runtimeChapterSourceText(plan);
        }

        if (!isChapterReviewEnabled()) {
            runtimeStateService.markSkipped(projectDir, input.projectId(), NovelRuntimeStateService.NODE_REVIEW_CHAPTER,
                    "章节级审校已跳过", Map.of("jobId", input.jobId(), "chapterIndex", input.chapterIndex()));
            return Map.of(
                    "chapterReviewStatus", "SKIPPED",
                    "chapterReviewSummary", "章节级审校已跳过",
                    "chapterReviewIssueCount", 0,
                    "chapterReviewApplied", false,
                    "chapterPolishedText", "",
                    "chapterPolishedCharCount", 0,
                    "currentNode", NODE_REVIEW_CHAPTER
            );
        }

        updateJob(projectDir, input.jobId(), job -> {
            job.setStatus("RUNNING");
            job.setCurrentNode(NODE_REVIEW_CHAPTER);
            job.setMessage("正在执行章节级总审校");
            job.setUpdatedAt(now());
        });
        runtimeStateService.markRunning(projectDir, input.projectId(), NovelRuntimeStateService.NODE_REVIEW_CHAPTER,
                "正在执行章节级总审校", Map.of(
                        "jobId", input.jobId(),
                        "chapterIndex", input.chapterIndex(),
                        "sourceChars", sourceText.length(),
                        "translatedChars", translatedText.length()
                ));

        NovelTranslationReviewService.ChapterPolishResult reviewResult =
                novelTranslationReviewService.polishChapter(
                        plan,
                        sourceText,
                        translatedText,
                        () -> isCancelRequested(input.jobId())
                );

        String finalText = translatedText;
        if (reviewResult.applied()) {
            finalText = novelTranslationService.applyRuntimeChapterPolish(plan, reviewResult.polishedText());
        }

        Map<String, Object> metadata = Map.of(
                "jobId", input.jobId(),
                "chapterIndex", input.chapterIndex(),
                "reviewStatus", reviewResult.status(),
                "issueCount", reviewResult.issueCount(),
                "applied", reviewResult.applied(),
                "reviewPath", reviewResult.reviewPath(),
                "polishedChars", finalText.length(),
                "summary", reviewResult.summary()
        );
        if ("NEED_REVIEW".equalsIgnoreCase(reviewResult.status())) {
            runtimeStateService.markNeedReview(projectDir, input.projectId(), NovelRuntimeStateService.NODE_REVIEW_CHAPTER,
                    "章节级审校仍需人工确认", metadata);
        } else {
            runtimeStateService.markCompleted(projectDir, input.projectId(), NovelRuntimeStateService.NODE_REVIEW_CHAPTER,
                    reviewResult.applied() ? "章节级审校润色完成" : "章节级审校通过", metadata);
        }
        eventLogService.append(projectDir, "CHAPTER_REVIEWED", "章节级审校完成", metadata);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("translatedText", finalText);
        result.put("translatedCharCount", finalText.length());
        result.put("chapterReviewStatus", reviewResult.status());
        result.put("chapterReviewSummary", reviewResult.summary());
        result.put("chapterReviewPath", reviewResult.reviewPath());
        result.put("chapterReviewIssueCount", reviewResult.issueCount());
        result.put("chapterReviewApplied", reviewResult.applied());
        result.put("chapterPolishedText", reviewResult.applied() ? finalText : "");
        result.put("chapterPolishedCharCount", reviewResult.applied() ? finalText.length() : 0);
        result.put("currentNode", NODE_REVIEW_CHAPTER);
        return result;
    }

    private Map<String, Object> finalizeJobNode(OverAllState state) {
        JobInput input = readInput(state);
        throwIfCancelled(input.jobId());

        Path projectDir = novelProjectService.resolveProjectDir(input.projectId());
        NovelTranslationService.RuntimeChapterPlan plan = chapterPlan(state);
        String translatedText = state.value("translatedText", "");
        TranslateChapterResponse response = novelTranslationService.completeRuntimeChapter(plan, translatedText);
        int translatedCharCount = response.getTranslatedCharCount();
        int sourceStartOffset = state.value("sourceStartOffset", 0);
        int sourceEndOffset = state.value("sourceEndOffset", plan.sourceCharCount());
        int sourceCharLimit = state.value("sourceCharLimit", input.sourceCharLimit());
        int sourceCharCount = state.value("sourceCharCount", plan.sourceCharCount());
        int fullSourceCharCount = state.value("fullSourceCharCount", plan.sourceCharCount());
        updateJob(projectDir, input.jobId(), job -> {
            job.setStatus("COMPLETED");
            job.setCurrentNode(NODE_FINALIZE_JOB);
            job.setMessage("翻译任务完成");
            job.setJobType(input.jobType());
            job.setTitle(plan.title());
            job.setSourceFileName(plan.sourceFileName());
            job.setSourceStartOffset(sourceStartOffset);
            job.setSourceEndOffset(sourceEndOffset);
            job.setSourceCharLimit(sourceCharLimit);
            job.setSourceCharCount(sourceCharCount);
            job.setFullSourceCharCount(fullSourceCharCount);
            job.setOutputPath(response.getOutputPath());
            job.setCurrentChunkIndex(plan.totalChunks());
            job.setCompletedChunks(plan.totalChunks());
            job.setTotalChunks(plan.totalChunks());
            job.setCompletedAt(now());
            job.setUpdatedAt(now());
        });
        runtimeStateService.markCompleted(projectDir, input.projectId(), NovelRuntimeStateService.NODE_TRANSLATE_CHUNK,
                "分段翻译完成", Map.of("jobId", input.jobId(), "chapterIndex", input.chapterIndex(),
                        "jobType", input.jobType(),
                        "sourceStartOffset", sourceStartOffset,
                        "sourceEndOffset", sourceEndOffset,
                        "sourceCharCount", sourceCharCount,
                        "fullSourceCharCount", fullSourceCharCount,
                        "totalChunks", plan.totalChunks()));
        int reviewIssueCount = state.value("reviewIssueCount", 0);
        int reviewUnresolvedCount = state.value("reviewUnresolvedCount", 0);
        String fixStatus = state.value("latestFixStatus", "");
        if (!isChunkReviewEnabled()) {
            runtimeStateService.markSkipped(projectDir, input.projectId(), NovelRuntimeStateService.NODE_REVIEW_CHUNK,
                    "已使用章节级审校，跳过分段审校", Map.of("jobId", input.jobId(), "chapterIndex", input.chapterIndex()));
            runtimeStateService.markCompleted(projectDir, input.projectId(), NovelRuntimeStateService.NODE_ACCEPT_CHUNK,
                    "所有分段已进入章节级审校", Map.of("jobId", input.jobId(), "chapterIndex", input.chapterIndex(),
                            "acceptedChunks", state.value("acceptedChunks", 0)));
        } else if (reviewUnresolvedCount > 0) {
            runtimeStateService.markNeedReview(projectDir, input.projectId(), NovelRuntimeStateService.NODE_REVIEW_CHUNK,
                    "审校仍存在未解决问题", Map.of("jobId", input.jobId(), "chapterIndex", input.chapterIndex(),
                            "issueCount", reviewIssueCount, "unresolvedCount", reviewUnresolvedCount));
            runtimeStateService.markNeedReview(projectDir, input.projectId(), NovelRuntimeStateService.NODE_ACCEPT_CHUNK,
                    "存在分段需要人工确认", Map.of("jobId", input.jobId(), "chapterIndex", input.chapterIndex(),
                            "unresolvedCount", reviewUnresolvedCount));
        } else {
            runtimeStateService.markCompleted(projectDir, input.projectId(), NovelRuntimeStateService.NODE_REVIEW_CHUNK,
                    "审校并修订完成", Map.of("jobId", input.jobId(), "chapterIndex", input.chapterIndex(),
                            "issueCount", 0, "fixStatus", fixStatus));
            runtimeStateService.markCompleted(projectDir, input.projectId(), NovelRuntimeStateService.NODE_ACCEPT_CHUNK,
                    "所有分段已接受", Map.of("jobId", input.jobId(), "chapterIndex", input.chapterIndex(),
                            "acceptedChunks", state.value("acceptedChunks", 0)));
        }
        if (!isChunkReviewEnabled()) {
            runtimeStateService.markSkipped(projectDir, input.projectId(), NovelRuntimeStateService.NODE_FIX_CHUNK,
                    "已使用章节级审校，跳过分段修订", Map.of("jobId", input.jobId(), "chapterIndex", input.chapterIndex()));
        } else if ("APPLIED".equalsIgnoreCase(fixStatus)) {
            runtimeStateService.markCompleted(projectDir, input.projectId(), NovelRuntimeStateService.NODE_FIX_CHUNK,
                    "修订节点已完成", Map.of("jobId", input.jobId(), "chapterIndex", input.chapterIndex(),
                            "fixStatus", fixStatus));
        } else if (reviewUnresolvedCount > 0) {
            runtimeStateService.markNeedReview(projectDir, input.projectId(), NovelRuntimeStateService.NODE_FIX_CHUNK,
                    "修订未能消化所有问题", Map.of("jobId", input.jobId(), "chapterIndex", input.chapterIndex(),
                            "fixStatus", fixStatus, "unresolvedCount", reviewUnresolvedCount));
        } else {
            runtimeStateService.markSkipped(projectDir, input.projectId(), NovelRuntimeStateService.NODE_FIX_CHUNK,
                    "本章未触发修订", Map.of("jobId", input.jobId(), "chapterIndex", input.chapterIndex(),
                            "fixStatus", fixStatus));
        }
        runtimeStateService.markCompleted(projectDir, input.projectId(), NovelRuntimeStateService.NODE_MERGE_CHAPTER,
                "章节译文已合并", Map.of("jobId", input.jobId(), "chapterIndex", input.chapterIndex(),
                        "jobType", input.jobType(),
                        "outputPath", response.getOutputPath()));
        runtimeStateService.markCompleted(projectDir, input.projectId(), NovelRuntimeStateService.NODE_EXPORT_BOOK,
                "章节译文已导出", Map.of("jobId", input.jobId(), "chapterIndex", input.chapterIndex(),
                        "jobType", input.jobType(),
                        "outputPath", response.getOutputPath(),
                        "polishedPath", projectDir.resolve("polished").resolve(response.getOutputFileName()).toAbsolutePath().normalize().toString()));
        runtimeStateService.markCompleted(projectDir, input.projectId(), NovelRuntimeStateService.NODE_TRANSLATE_CHAPTER,
                "章节翻译完成", Map.of("jobId", input.jobId(), "chapterIndex", input.chapterIndex(),
                        "jobType", input.jobType(),
                        "sourceStartOffset", sourceStartOffset,
                        "sourceEndOffset", sourceEndOffset,
                        "sourceCharCount", sourceCharCount,
                        "fullSourceCharCount", fullSourceCharCount,
                        "translatedCharCount", translatedCharCount));
        eventLogService.append(projectDir, "TRANSLATION_JOB_COMPLETED", "翻译任务已完成",
                Map.of("jobId", input.jobId(), "chapterIndex", input.chapterIndex(),
                        "jobType", input.jobType(),
                        "sourceStartOffset", sourceStartOffset,
                        "sourceEndOffset", sourceEndOffset,
                        "sourceCharCount", sourceCharCount,
                        "fullSourceCharCount", fullSourceCharCount,
                        "translatedCharCount", translatedCharCount));
        return Map.of(
                "currentNode", NODE_FINALIZE_JOB,
                "translatedCharCount", translatedCharCount);
    }

    private TranslationJobResponse newJobResponse(
            Path projectDir,
            String jobId,
            String projectId,
            int chapterIndex,
            int sourceStartOffset,
            int sourceCharLimit) {
        TranslationJobResponse response = new TranslationJobResponse();
        response.setJobId(jobId);
        response.setProjectId(projectId);
        response.setChapterIndex(chapterIndex);
        response.setJobType(sourceCharLimit > 0 ? "EXCERPT" : "CHAPTER");
        response.setSourceStartOffset(Math.max(0, sourceStartOffset));
        response.setSourceCharLimit(Math.max(0, sourceCharLimit));
        response.setStatus("PENDING");
        response.setCurrentNode("PENDING");
        response.setMessage("等待后台执行");
        response.setCreatedAt(now());
        response.setUpdatedAt(now());
        response.setJobFilePath(jobFile(projectDir, jobId).toAbsolutePath().normalize().toString());
        response.setProgressFilePath(projectDir.resolve("output").resolve("chunks").toAbsolutePath().normalize().toString());
        response.setTranslationModel(translationClient.modelName());
        return response;
    }

    private CheckpointRuntime createCheckpointRuntime(NovelGraphCheckpointProperties checkpointProperties) {
        String saver = checkpointProperties == null ? "file" : checkpointProperties.getSaver();
        if ("redis".equalsIgnoreCase(saver)) {
            RedissonClient client = createRedissonClient(checkpointProperties.getRedis());
            try {
                client.getKeys().count();
            } catch (RuntimeException e) {
                client.shutdown();
                throw new IllegalStateException("连接 Redis checkpoint 失败: " + e.getMessage(), e);
            }
            String location = checkpointProperties.getRedis().getAddress()
                    + "/db/" + checkpointProperties.getRedis().getDatabase()
                    + " keys=graph:*";
            return new CheckpointRuntime(
                    RedisSaver.builder().redisson(client).build(),
                    "redis",
                    location,
                    client
            );
        }

        BaseCheckpointSaver fileSaver = FileSystemSaver.builder()
                .targetFolder(this.checkpointDirectory)
                .build();
        return new CheckpointRuntime(
                fileSaver,
                "file",
                this.checkpointDirectory.toAbsolutePath().normalize().toString(),
                null
        );
    }

    private RedissonClient createRedissonClient(NovelGraphCheckpointProperties.Redis redisProperties) {
        Config config = new Config();
        SingleServerConfig serverConfig = config.useSingleServer()
                .setAddress(redisProperties.getAddress())
                .setDatabase(redisProperties.getDatabase())
                .setConnectionPoolSize(redisProperties.getConnectionPoolSize())
                .setConnectionMinimumIdleSize(redisProperties.getConnectionMinimumIdleSize())
                .setTimeout(redisProperties.getTimeoutMs());
        if (redisProperties.getPassword() != null && !redisProperties.getPassword().isBlank()) {
            serverConfig.setPassword(redisProperties.getPassword());
        }
        return Redisson.create(config);
    }

    private void assertNoDuplicateRunningJob(String projectId, int chapterIndex) {
        runningJobs.values().stream()
                .filter(job -> job.projectId().equals(projectId) && job.chapterIndex() == chapterIndex)
                .filter(job -> !job.future().isDone())
                .findFirst()
                .ifPresent(job -> {
                    throw new IllegalStateException("该章节已有翻译任务正在运行");
                });
    }

    private void markFailed(Path projectDir, String jobId, Exception e) {
        TranslationJobResponse current = safeReadJob(projectDir, jobId);
        updateJob(projectDir, jobId, job -> {
            job.setStatus("FAILED");
            job.setMessage("翻译任务失败");
            job.setErrorMessage(e.getMessage());
            job.setCompletedAt(now());
            job.setUpdatedAt(now());
        });
        if (current != null) {
            runtimeStateService.markFailed(projectDir, current.getProjectId(), NovelRuntimeStateService.NODE_TRANSLATE_CHAPTER,
                    "章节翻译失败", Map.of("jobId", jobId, "chapterIndex", current.getChapterIndex(),
                            "error", e.getMessage() == null ? "" : e.getMessage()));
        }
        eventLogService.append(projectDir, "TRANSLATION_JOB_FAILED", "翻译任务失败",
                Map.of("jobId", jobId, "error", e.getMessage() == null ? "" : e.getMessage()));
    }

    private void markCancelled(Path projectDir, String jobId, String message) {
        TranslationJobResponse current = safeReadJob(projectDir, jobId);
        updateJob(projectDir, jobId, job -> {
            job.setStatus("CANCELLED");
            job.setMessage("翻译任务已取消");
            job.setErrorMessage(message);
            job.setCompletedAt(now());
            job.setUpdatedAt(now());
        });
        if (current != null) {
            runtimeStateService.markCancelled(projectDir, current.getProjectId(), NovelRuntimeStateService.NODE_TRANSLATE_CHAPTER,
                    "章节翻译已取消", Map.of("jobId", jobId, "chapterIndex", current.getChapterIndex()));
        }
        eventLogService.append(projectDir, "TRANSLATION_JOB_CANCELLED", "翻译任务已取消",
                Map.of("jobId", jobId));
    }

    private TranslationJobResponse readJob(Path projectDir, String jobId) {
        Path jobFile = jobFile(projectDir, jobId);
        if (!Files.isRegularFile(jobFile)) {
            throw new IllegalArgumentException("翻译任务不存在: " + jobId);
        }
        try {
            return objectMapper.readValue(jobFile.toFile(), TranslationJobResponse.class);
        } catch (IOException e) {
            throw new IllegalStateException("读取翻译任务失败: " + e.getMessage(), e);
        }
    }

    private TranslationJobResponse safeReadJob(Path projectDir, String jobId) {
        try {
            return readJob(projectDir, jobId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private TranslationJobResponse safeReadJobFile(Path jobPath, Path projectDir) {
        try {
            TranslationJobResponse job = objectMapper.readValue(jobPath.toFile(), TranslationJobResponse.class);
            job.setJobFilePath(jobPath.toAbsolutePath().normalize().toString());
            if (job.getProgressFilePath() == null || job.getProgressFilePath().isBlank()) {
                job.setProgressFilePath(projectDir.resolve("output").resolve("chunks").toAbsolutePath().normalize().toString());
            }
            return job;
        } catch (IOException e) {
            logger.debug("读取 NovelFlow 翻译任务文件失败: file={}, error={}",
                    jobPath.toAbsolutePath().normalize(), e.getMessage());
            return null;
        }
    }

    private long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private synchronized void updateJob(Path projectDir, String jobId, JobUpdater updater) {
        TranslationJobResponse response = readJob(projectDir, jobId);
        updater.update(response);
        writeJob(projectDir, response);
    }

    private synchronized void writeJob(Path projectDir, TranslationJobResponse response) {
        try {
            Path jobFile = jobFile(projectDir, response.getJobId());
            Files.createDirectories(jobFile.getParent());
            response.setJobFilePath(jobFile.toAbsolutePath().normalize().toString());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(jobFile.toFile(), response);
        } catch (IOException e) {
            throw new IllegalStateException("写入翻译任务失败: " + e.getMessage(), e);
        }
    }

    private Path jobFile(Path projectDir, String jobId) {
        if (jobId == null || !jobId.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("非法翻译任务ID");
        }
        return projectDir.resolve("config").resolve("jobs").resolve(jobId + ".json").normalize();
    }

    private RunnableConfig runnableConfig(String jobId) {
        return RunnableConfig.builder().threadId(jobId).build();
    }

    private String requireCheckpointId(String checkpointId) {
        String safe = checkpointId == null ? "" : checkpointId.strip();
        if (safe.isBlank() || !safe.matches("[A-Za-z0-9._:-]+")) {
            throw new IllegalArgumentException("非法 Graph checkpointId");
        }
        return safe;
    }

    private void hydrateJobFromCheckpoint(TranslationJobResponse response, Map<String, Object> state) {
        response.setJobType(stringState(state, "jobType", response.getJobType()));
        response.setTitle(stringState(state, "title", response.getTitle()));
        response.setSourceFileName(stringState(state, "sourceFileName", response.getSourceFileName()));
        response.setSourceStartOffset(intState(state, "sourceStartOffset", response.getSourceStartOffset()));
        response.setSourceEndOffset(intState(state, "sourceEndOffset", response.getSourceEndOffset()));
        response.setSourceCharLimit(intState(state, "sourceCharLimit", response.getSourceCharLimit()));
        response.setSourceCharCount(intState(state, "sourceCharCount", response.getSourceCharCount()));
        response.setFullSourceCharCount(intState(state, "fullSourceCharCount", response.getFullSourceCharCount()));
        response.setCurrentChunkIndex(intState(state, "currentChunkIndex", response.getCurrentChunkIndex()));
        response.setCompletedChunks(intState(state, "completedChunks", response.getCompletedChunks()));
        response.setTotalChunks(intState(state, "totalChunks", response.getTotalChunks()));
        response.setOutputPath(stringState(state, "outputPath", response.getOutputPath()));
    }

    private NovelGraphSnapshotResponse toSnapshotResponse(String jobId, StateSnapshot snapshot) {
        NovelGraphSnapshotResponse response = new NovelGraphSnapshotResponse();
        response.setJobId(jobId);
        response.setNode(snapshot.node());
        response.setNextNode(snapshot.next());
        response.setCheckpointId(snapshot.config().checkPointId().orElse(null));
        response.setCheckpointStore(checkpointStore);
        response.setCheckpointLocation(checkpointLocation(jobId));
        response.setCheckpointFilePath("file".equals(checkpointStore)
                ? checkpointDirectory.resolve("thread-" + jobId + ".saver").toAbsolutePath().normalize().toString()
                : null);
        response.setState(sanitizeState(snapshot.state().data()));
        return response;
    }

    private String checkpointLocation(String jobId) {
        if ("redis".equals(checkpointStore)) {
            return checkpointLocation + " threadId=" + jobId;
        }
        return checkpointDirectory.resolve("thread-" + jobId + ".saver")
                .toAbsolutePath().normalize().toString();
    }

    private Map<String, Object> sanitizeState(Map<String, Object> state) {
        Map<String, Object> result = new LinkedHashMap<>();
        state.forEach((key, value) -> result.put(key, sanitizeValue(key, value)));
        return result;
    }

    private Object sanitizeValue(String key, Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof NovelTranslationService.RuntimeChapterPlan plan) {
            return summarizeChapterPlan(plan);
        }
        if ("chapterPlan".equals(key)) {
            return summarizeUnknownChapterPlan(value);
        }
        if (value instanceof String text) {
            if (isLargeTextKey(key) || text.length() > 1000) {
                return summarizeText(text, 300);
            }
            return text;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Collection<?> collection) {
            return Map.of("type", value.getClass().getSimpleName(), "size", collection.size());
        }
        if (value instanceof Map<?, ?> map) {
            return Map.of("type", value.getClass().getSimpleName(), "size", map.size());
        }
        String text = value.toString();
        return Map.of(
                "type", value.getClass().getSimpleName(),
                "charCount", text.length(),
                "preview", text.length() <= 300 ? text : text.substring(0, 300) + "..."
        );
    }

    private Map<String, Object> summarizeChapterPlan(NovelTranslationService.RuntimeChapterPlan plan) {
        return Map.of(
                "type", "RuntimeChapterPlan",
                "projectId", plan.projectId(),
                "chapterIndex", plan.chapterIndex(),
                "title", plan.title(),
                "sourceFileName", plan.sourceFileName(),
                "sourceCharCount", plan.sourceCharCount(),
                "outputPath", plan.outputPath(),
                "chunkDirectory", plan.chunkDirectory(),
                "totalChunks", plan.totalChunks()
        );
    }

    private Map<String, Object> summarizeUnknownChapterPlan(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", value.getClass().getSimpleName());
        putReflectiveValue(result, value, "projectId");
        putReflectiveValue(result, value, "chapterIndex");
        putReflectiveValue(result, value, "title");
        putReflectiveValue(result, value, "sourceFileName");
        putReflectiveValue(result, value, "sourceCharCount");
        putReflectiveValue(result, value, "outputPath");
        putReflectiveValue(result, value, "chunkDirectory");
        putReflectiveValue(result, value, "totalChunks");
        String text = value.toString();
        result.put("preview", text.length() <= 300 ? text : text.substring(0, 300) + "...");
        return result;
    }

    private void putReflectiveValue(Map<String, Object> result, Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            Object value = method.invoke(target);
            if (value instanceof Collection<?> collection) {
                result.put(methodName, Map.of("type", value.getClass().getSimpleName(), "size", collection.size()));
            } else {
                result.put(methodName, value);
            }
        } catch (ReflectiveOperationException ignored) {
            // Some checkpoint values may come back through a different classloader after devtools restarts.
        }
    }

    private boolean isLargeTextKey(String key) {
        return List.of(
                "translatedText",
                "previousSourceText",
                "previousTranslatedText",
                "chunkSourceText",
                "chunkGlossaryPrompt",
                "rawTranslatedText",
                "postProcessedTranslatedText",
                "latestSourceText",
                "latestTranslatedText",
                "chapterSourceText",
                "chapterPolishedText"
        ).contains(key);
    }

    private Map<String, Object> summarizeText(String text, int previewLength) {
        String safe = text == null ? "" : text.strip();
        String preview = safe.length() <= previewLength ? safe : safe.substring(0, previewLength) + "...";
        return Map.of(
                "type", "text",
                "charCount", safe.length(),
                "preview", preview
        );
    }

    private JobInput readInput(OverAllState state) {
        return new JobInput(
                state.value("jobId", ""),
                state.value("projectId", ""),
                state.value("chapterIndex", 1),
                Math.max(0, state.value("sourceStartOffset", 0)),
                Math.max(0, state.value("sourceCharLimit", 0))
        );
    }

    private NovelTranslationService.RuntimeChapterPlan chapterPlan(OverAllState state) {
        Optional<NovelTranslationService.RuntimeChapterPlan> typed =
                state.value("chapterPlan", NovelTranslationService.RuntimeChapterPlan.class);
        if (typed.isPresent()) {
            return typed.get();
        }
        Object raw = state.value("chapterPlan").orElse(null);
        NovelTranslationService.RuntimeChapterPlan restored = restoreChapterPlan(raw);
        if (restored != null) {
            return restored;
        }
        throw new IllegalStateException("章节翻译计划不存在");
    }

    private NovelTranslationService.RuntimeChunkInput chunkInput(OverAllState state) {
        Optional<NovelTranslationService.RuntimeChunkInput> typed =
                state.value("chunkInput", NovelTranslationService.RuntimeChunkInput.class);
        if (typed.isPresent()) {
            return typed.get();
        }
        Object raw = state.value("chunkInput").orElse(null);
        NovelTranslationService.RuntimeChunkInput restored = restoreChunkInput(raw);
        if (restored != null) {
            return restored;
        }
        NovelTranslationService.RuntimeChapterPlan plan = chapterPlan(state);
        int chunkIndex = Math.max(1, state.value("currentChunkIndex", 1));
        return novelTranslationService.buildRuntimeChunkInput(
                plan,
                chunkIndex,
                state.value("previousSourceText", ""),
                state.value("previousTranslatedText", ""));
    }

    private NovelTranslationService.RuntimeChapterPlan restoreChapterPlan(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            List<String> chunks = stringList(rawValue(raw, "chunks"));
            if (chunks.isEmpty()) {
                return null;
            }
            return new NovelTranslationService.RuntimeChapterPlan(
                    stringRawValue(raw, "projectId"),
                    intRawValue(raw, "chapterIndex", 0),
                    stringRawValue(raw, "title"),
                    stringRawValue(raw, "sourceFileName"),
                    intRawValue(raw, "sourceCharCount", 0),
                    stringRawValue(raw, "outputPath"),
                    stringRawValue(raw, "chunkDirectory"),
                    chunks);
        } catch (RuntimeException e) {
            logger.debug("还原 RuntimeChapterPlan 失败: type={}, error={}",
                    raw.getClass().getName(), e.getMessage());
            return null;
        }
    }

    private NovelTranslationService.RuntimeChunkInput restoreChunkInput(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            String sourceText = stringRawValue(raw, "sourceText");
            if (sourceText.isBlank()) {
                return null;
            }
            return new NovelTranslationService.RuntimeChunkInput(
                    stringRawValue(raw, "projectId"),
                    intRawValue(raw, "chapterIndex", 0),
                    stringRawValue(raw, "title"),
                    intRawValue(raw, "chunkIndex", 1),
                    intRawValue(raw, "totalChunks", 1),
                    sourceText,
                    stringRawValue(raw, "previousSourceText"),
                    stringRawValue(raw, "previousTranslatedText"),
                    stringRawValue(raw, "glossaryPrompt"));
        } catch (RuntimeException e) {
            logger.debug("还原 RuntimeChunkInput 失败: type={}, error={}",
                    raw.getClass().getName(), e.getMessage());
            return null;
        }
    }

    private Object rawValue(Object raw, String key) {
        if (raw instanceof Map<?, ?> map) {
            return map.get(key);
        }
        try {
            Method method = raw.getClass().getMethod(key);
            return method.invoke(raw);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private String stringRawValue(Object raw, String key) {
        Object value = rawValue(raw, key);
        return value == null ? "" : String.valueOf(value);
    }

    private int intRawValue(Object raw, String key, int fallback) {
        Object value = rawValue(raw, key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof Collection<?> collection)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : collection) {
            result.add(item == null ? "" : String.valueOf(item));
        }
        return result;
    }

    private boolean isChunkReviewEnabled() {
        if (!translationProperties.isReviewEnabled()) {
            return false;
        }
        String mode = normalizedReviewMode();
        return "chunk".equals(mode) || "both".equals(mode);
    }

    private boolean isChapterReviewEnabled() {
        if (!translationProperties.isReviewEnabled()) {
            return false;
        }
        String mode = normalizedReviewMode();
        return "chapter".equals(mode) || "both".equals(mode) || mode.isBlank();
    }

    private String normalizedReviewMode() {
        String mode = translationProperties.getReviewMode();
        return mode == null ? "chapter" : mode.strip().toLowerCase();
    }

    private boolean isCancelRequested(String jobId) {
        AtomicBoolean cancelFlag = cancelFlags.get(jobId);
        return cancelFlag != null && cancelFlag.get();
    }

    private void throwIfCancelled(String jobId) {
        if (isCancelRequested(jobId)) {
            throw new NovelTranslationCancelledException("翻译任务已取消: " + jobId);
        }
    }

    private boolean isTerminal(String status) {
        return "COMPLETED".equals(status)
                || "FAILED".equals(status)
                || "CANCELLED".equals(status)
                || "INTERRUPTED".equals(status);
    }

    private boolean isRecoverableStaleStatus(String status) {
        return "PENDING".equals(status)
                || "RUNNING".equals(status)
                || "CANCEL_REQUESTED".equals(status);
    }

    private String reviewFilePath(Path projectDir, String sourceFileName, int chunkIndex) {
        return projectDir.resolve("review")
                .resolve("chunks")
                .resolve(stripExtension(sourceFileName))
                .resolve("part-%03d.review.json".formatted(chunkIndex))
                .toAbsolutePath()
                .normalize()
                .toString();
    }

    private String tail(String value, int maxChars) {
        if (value == null || value.isBlank() || maxChars <= 0) {
            return "";
        }
        String stripped = value.strip();
        if (stripped.length() <= maxChars) {
            return stripped;
        }
        return stripped.substring(stripped.length() - maxChars);
    }

    private String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String baseName = dot > 0 ? fileName.substring(0, dot) : fileName;
        return baseName.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private String now() {
        return LocalDateTime.now().format(TIME_FORMATTER);
    }

    private record JobInput(
            String jobId,
            String projectId,
            int chapterIndex,
            int sourceStartOffset,
            int sourceCharLimit) {

        boolean excerpt() {
            return sourceCharLimit > 0;
        }

        String jobType() {
            return excerpt() ? "EXCERPT" : "CHAPTER";
        }
    }

    private record RunningJob(String projectId, int chapterIndex, Future<?> future, AtomicBoolean cancelFlag) {
    }

    private record CheckpointRuntime(
            BaseCheckpointSaver saver,
            String store,
            String location,
            RedissonClient redissonClient) {
    }

    private interface JobUpdater {
        void update(TranslationJobResponse response);
    }
}

