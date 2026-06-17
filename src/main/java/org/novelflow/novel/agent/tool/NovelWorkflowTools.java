package org.novelflow.novel.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.novelflow.novel.agent.memory.NovelAgentMemoryRecord;
import org.novelflow.novel.agent.memory.NovelAgentMemoryService;
import org.novelflow.novel.agent.session.NovelAgentProgressService;
import org.novelflow.novel.agent.session.NovelFlowStorageMaintenanceService;
import org.novelflow.novel.dto.CreateNovelProjectRequest;
import org.novelflow.novel.dto.SplitNovelResponse;
import org.novelflow.novel.dto.StartTranslationJobRequest;
import org.novelflow.novel.dto.TranslationJobResponse;
import org.novelflow.novel.dto.TranslationProgressResponse;
import org.novelflow.novel.dto.TranslationReadResponse;
import org.novelflow.novel.agent.session.NovelAgentSessionService;
import org.novelflow.novel.service.ChapterSplitService;
import org.novelflow.novel.service.NovelExportService;
import org.novelflow.novel.service.NovelProjectService;
import org.novelflow.novel.service.NovelRuntimeStateService;
import org.novelflow.novel.service.NovelTermMemoryService;
import org.novelflow.novel.service.NovelTranslationJobService;
import org.novelflow.novel.service.NovelTranslationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class NovelWorkflowTools {

    private static final Logger logger = LoggerFactory.getLogger(NovelWorkflowTools.class);

    private final NovelProjectService novelProjectService;
    private final ChapterSplitService chapterSplitService;
    private final NovelTranslationJobService novelTranslationJobService;
    private final NovelTranslationService novelTranslationService;
    private final NovelRuntimeStateService novelRuntimeStateService;
    private final NovelTermMemoryService novelTermMemoryService;
    private final NovelAgentMemoryService novelAgentMemoryService;
    private final NovelExportService novelExportService;
    private final NovelFlowStorageMaintenanceService storageMaintenanceService;
    private final NovelAgentSessionService novelAgentSessionService;
    private final NovelAgentProgressService novelAgentProgressService;
    private final ObjectMapper objectMapper;

    public NovelWorkflowTools(
            NovelProjectService novelProjectService,
            ChapterSplitService chapterSplitService,
            NovelTranslationJobService novelTranslationJobService,
            NovelTranslationService novelTranslationService,
            NovelRuntimeStateService novelRuntimeStateService,
            NovelTermMemoryService novelTermMemoryService,
            NovelAgentMemoryService novelAgentMemoryService,
            NovelExportService novelExportService,
            NovelFlowStorageMaintenanceService storageMaintenanceService,
            NovelAgentSessionService novelAgentSessionService,
            NovelAgentProgressService novelAgentProgressService,
            ObjectMapper objectMapper) {
        this.novelProjectService = novelProjectService;
        this.chapterSplitService = chapterSplitService;
        this.novelTranslationJobService = novelTranslationJobService;
        this.novelTranslationService = novelTranslationService;
        this.novelRuntimeStateService = novelRuntimeStateService;
        this.novelTermMemoryService = novelTermMemoryService;
        this.novelAgentMemoryService = novelAgentMemoryService;
        this.novelExportService = novelExportService;
        this.storageMaintenanceService = storageMaintenanceService;
        this.novelAgentSessionService = novelAgentSessionService;
        this.novelAgentProgressService = novelAgentProgressService;
        this.objectMapper = objectMapper;
    }

    @Tool(description = "List NovelFlow projects in the local workspace. Use before operating when the user did not provide a projectId.")
    public String listNovelProjects() {
        return invoke("listNovelProjects", Map.of(), () -> {
            List<Map<String, Object>> projects = novelProjectService.listProjectSummaries("");
            return Map.of("workspace", novelProjectService.getWorkspaceRoot().toString(),
                    "count", projects.size(), "projects", projects);
        });
    }

    @Tool(description = "Find existing NovelFlow projects by book title, source filename, or chapter title. Use this when the user references a novel name but no projectId is available.")
    public String findNovelProject(
            @ToolParam(description = "Book title, source filename, or keyword, for example かがみの孤城") String query) {
        String safeQuery = query == null ? "" : query.trim();
        return invoke("findNovelProject", Map.of("query", safe(safeQuery)), () -> {
            List<Map<String, Object>> projects = novelProjectService.listProjectSummaries(safeQuery)
                    .stream()
                    .sorted((left, right) -> Long.compare(projectUsefulness(right), projectUsefulness(left)))
                    .toList();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("query", safeQuery);
            payload.put("count", projects.size());
            payload.put("best", projects.isEmpty() ? Map.of() : projects.get(0));
            payload.put("projects", projects.stream().limit(8).toList());
            return payload;
        });
    }

    @Tool(description = "Create a NovelFlow translation project. The user must upload the source file through the UI or API after this tool returns the projectId.")
    public String createNovelProject(
            @ToolParam(description = "Human-readable project name") String projectName,
            @ToolParam(description = "Source language, default Japanese") String sourceLanguage,
            @ToolParam(description = "Target language, default Chinese") String targetLanguage) {
        return invoke("createNovelProject", Map.of(
                "projectName", safe(projectName),
                "sourceLanguage", safe(sourceLanguage),
                "targetLanguage", safe(targetLanguage)
        ), () -> {
            CreateNovelProjectRequest request = new CreateNovelProjectRequest();
            request.setProjectName(projectName);
            request.setSourceLanguage(sourceLanguage == null || sourceLanguage.isBlank() ? "日语" : sourceLanguage);
            request.setTargetLanguage(targetLanguage == null || targetLanguage.isBlank() ? "中文" : targetLanguage);
            Object response = novelProjectService.createProject(request);
            if (response instanceof org.novelflow.novel.dto.NovelProjectResponse projectResponse) {
                novelAgentSessionService.recordProject(projectResponse.getProjectId());
            }
            return response;
        });
    }

    @Tool(description = "Split an uploaded TXT/MD/EPUB source file into chapter markdown files for a NovelFlow project.")
    public String splitNovelProject(
            @ToolParam(description = "NovelFlow projectId, for example novel-20260604204932-8464d68b") String projectId,
            @ToolParam(description = "Optional raw source file name. Pass an empty string to use the first uploaded source file.") String sourceFileName) {
        String resolvedProjectId = resolveProjectId(projectId);
        String resolvedSourceFileName = sourceFileName == null || sourceFileName.isBlank()
                ? novelAgentSessionService.current().getCurrentSourceFileName()
                : sourceFileName;
        return invoke("splitNovelProject", Map.of("projectId", safe(resolvedProjectId), "sourceFileName", safe(resolvedSourceFileName)),
                () -> {
                    Object response = chapterSplitService.split(resolvedProjectId, blankToNull(resolvedSourceFileName));
                    if (response instanceof SplitNovelResponse splitResponse) {
                        novelAgentSessionService.recordSource(resolvedProjectId, splitResponse.getSourceFileName());
                    } else {
                        novelAgentSessionService.recordSource(resolvedProjectId, resolvedSourceFileName);
                    }
                    return response;
                });
    }

    @Tool(description = "Start an asynchronous Graph translation job for one chapter. Use this only when the user wants to start a background job without waiting. For 'translate chapter and show me the result', prefer translateChapterAndPreview.")
    public String startChapterTranslation(
            @ToolParam(description = "NovelFlow projectId") String projectId,
            @ToolParam(description = "1-based chapter index") int chapterIndex) {
        String resolvedProjectId = resolveProjectId(projectId);
        return invoke("startChapterTranslation", Map.of("projectId", safe(resolvedProjectId), "chapterIndex", chapterIndex), () -> {
            StartTranslationJobRequest request = new StartTranslationJobRequest();
            request.setChapterIndex(chapterIndex <= 0 ? 1 : chapterIndex);
            TranslationJobResponse response = novelTranslationJobService.startTranslationJob(resolvedProjectId, request);
            novelAgentSessionService.recordTranslationJob(response);
            return response;
        });
    }

    @Tool(description = "Start a chapter translation job, wait for completion, then read the first translated preview. Use when the user asks to translate a chapter and expects content back in the chat.")
    public String translateChapterAndPreview(
            @ToolParam(description = "NovelFlow projectId. Pass empty string to use current session project.") String projectId,
            @ToolParam(description = "1-based chapter index") int chapterIndex,
            @ToolParam(description = "Preview offset, usually 0") int offset,
            @ToolParam(description = "Preview char limit, usually 1200. The user can say continue for more.") int limit,
            @ToolParam(description = "Max seconds to wait for job completion, usually 900") int waitSeconds) {
        String resolvedProjectId = resolveProjectId(projectId);
        int safeChapterIndex = chapterIndex <= 0
                ? Math.max(1, novelAgentSessionService.current().getCurrentChapterIndex())
                : chapterIndex;
        int safeOffset = Math.max(0, offset);
        int safeLimit = normalizePreviewLimit(limit);
        int safeWaitSeconds = Math.max(30, Math.min(waitSeconds <= 0 ? 900 : waitSeconds, 1200));
        return invoke("translateChapterAndPreview", Map.of(
                "projectId", safe(resolvedProjectId),
                "chapterIndex", safeChapterIndex,
                "offset", safeOffset,
                "limit", safeLimit,
                "waitSeconds", safeWaitSeconds
        ), () -> {
            StartTranslationJobRequest request = new StartTranslationJobRequest();
            request.setChapterIndex(safeChapterIndex);
            long startedAt = System.currentTimeMillis();
            TranslationJobResponse job = novelTranslationJobService.startTranslationJob(resolvedProjectId, request);
            novelAgentSessionService.recordTranslationJob(job);
            emitTranslationProgress("translation:started",
                    "已启动第 " + safeChapterIndex + " 章翻译任务，等待 Graph Runtime 执行",
                    job, null, startedAt);

            TranslationProgressResponse progress = null;
            long deadline = System.currentTimeMillis() + safeWaitSeconds * 1000L;
            while (!isTerminal(job.getStatus()) && System.currentTimeMillis() < deadline) {
                sleep(3000);
                job = novelTranslationJobService.getJob(resolvedProjectId, job.getJobId());
                novelAgentSessionService.recordTranslationJob(job);
                try {
                    progress = novelTranslationService.getTranslationProgress(resolvedProjectId, safeChapterIndex);
                } catch (Exception ignored) {
                    progress = null;
                }
                emitTranslationProgress("translation:progress",
                        buildTranslationProgressMessage(job, progress),
                        job, progress, startedAt);
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("job", job);
            payload.put("progress", progress);
            payload.put("completed", "COMPLETED".equalsIgnoreCase(job.getStatus()));
            if ("COMPLETED".equalsIgnoreCase(job.getStatus())) {
                emitTranslationProgress("translation:reading",
                        "章节翻译与审校完成，正在读取首段预览",
                        job, progress, startedAt);
                TranslationReadResponse read = novelTranslationService.readTranslation(
                        resolvedProjectId, safeChapterIndex, safeOffset, safeLimit);
                novelAgentSessionService.recordTranslationRead(read);
                payload.put("translation", read);
                payload.put("nextAction", read.isHasMore() ? "用户说“继续”即可读取下一段译文" : "当前章节译文已全部显示");
            } else {
                payload.put("message", "翻译任务尚未完成，请稍后查询 jobId=" + job.getJobId());
                emitTranslationProgress("translation:waiting",
                        "等待时间到达上限，任务仍在运行，可继续查询进度",
                        job, progress, startedAt);
            }
            return payload;
        });
    }

    @Tool(description = "Translate only a source-text excerpt from a chapter, such as 'translate the first 3000 characters' or '试译前N字'. This does NOT translate or overwrite the full chapter.")
    public String translateChapterExcerpt(
            @ToolParam(description = "NovelFlow projectId. Pass empty string to use current session project.") String projectId,
            @ToolParam(description = "1-based chapter index") int chapterIndex,
            @ToolParam(description = "0-based source character offset. Use 0 for the beginning of the chapter.") int startSourceOffset,
            @ToolParam(description = "Maximum source characters to translate, for example 3000. This is source length, not output preview length.") int sourceCharLimit) {
        String resolvedProjectId = resolveProjectId(projectId);
        int safeChapterIndex = chapterIndex <= 0
                ? Math.max(1, novelAgentSessionService.current().getCurrentChapterIndex())
                : chapterIndex;
        int safeStart = Math.max(0, startSourceOffset);
        int safeLimit = normalizeSourceExcerptLimit(sourceCharLimit);
        return invoke("translateChapterExcerpt", Map.of(
                "projectId", safe(resolvedProjectId),
                "chapterIndex", safeChapterIndex,
                "startSourceOffset", safeStart,
                "sourceCharLimit", safeLimit
        ), () -> translateChapterExcerptInternal(resolvedProjectId, safeChapterIndex, safeStart, safeLimit));
    }

    @Tool(description = "Continue translating the next source-text excerpt from the chapter range stored in session. Use when the previous request was an excerpt translation and the user says continue/继续.")
    public String continueChapterExcerptTranslation(
            @ToolParam(description = "Maximum source characters for the next excerpt. Pass 0 to reuse the previous excerpt size.") int sourceCharLimit) {
        NovelAgentSessionService.NovelAgentSessionState state = novelAgentSessionService.current();
        String projectId = resolveProjectId(state.getCurrentProjectId());
        int chapterIndex = state.getCurrentExcerptChapterIndex();
        int nextSourceOffset = state.getCurrentExcerptNextSourceOffset();
        int safeLimit = sourceCharLimit <= 0
                ? normalizeSourceExcerptLimit(state.getCurrentExcerptSourceCharLimit())
                : normalizeSourceExcerptLimit(sourceCharLimit);
        return invoke("continueChapterExcerptTranslation", Map.of(
                "projectId", safe(projectId),
                "chapterIndex", chapterIndex,
                "startSourceOffset", nextSourceOffset,
                "sourceCharLimit", safeLimit
        ), () -> {
            if (chapterIndex <= 0) {
                throw new IllegalArgumentException("当前会话没有可继续的源文片段，请先请求翻译某章的前 N 字");
            }
            if (!state.isCurrentExcerptHasMoreSource()) {
                return Map.of(
                        "message", "当前章节源文片段已经翻译到末尾",
                        "projectId", projectId,
                        "chapterIndex", chapterIndex,
                        "nextSourceOffset", nextSourceOffset
                );
            }
            return translateChapterExcerptInternal(projectId, chapterIndex, nextSourceOffset, safeLimit);
        });
    }

    @Tool(description = "Get the latest persisted status for a NovelFlow translation job.")
    public String getTranslationJob(
            @ToolParam(description = "NovelFlow projectId") String projectId,
            @ToolParam(description = "Translation jobId") String jobId) {
        String resolvedProjectId = resolveProjectId(projectId);
        return invoke("getTranslationJob", Map.of("projectId", safe(resolvedProjectId), "jobId", safe(jobId)),
                () -> {
                    TranslationJobResponse response = novelTranslationJobService.getJob(resolvedProjectId, jobId);
                    novelAgentSessionService.recordTranslationJob(response);
                    return response;
                });
    }

    @Tool(description = "Cancel a running NovelFlow translation job.")
    public String cancelTranslationJob(
            @ToolParam(description = "NovelFlow projectId") String projectId,
            @ToolParam(description = "Translation jobId") String jobId) {
        return invoke("cancelTranslationJob", Map.of("projectId", safe(projectId), "jobId", safe(jobId)),
                () -> novelTranslationJobService.cancelJob(projectId, jobId));
    }

    @Tool(description = "Retry a failed or cancelled NovelFlow translation job by creating a new job for the same chapter.")
    public String retryTranslationJob(
            @ToolParam(description = "NovelFlow projectId") String projectId,
            @ToolParam(description = "Previous translation jobId") String jobId) {
        return invoke("retryTranslationJob", Map.of("projectId", safe(projectId), "jobId", safe(jobId)),
                () -> novelTranslationJobService.retryJob(projectId, jobId));
    }

    @Tool(description = "Get high-level file and chapter counts for a NovelFlow project.")
    public String getNovelProjectStatus(@ToolParam(description = "NovelFlow projectId") String projectId) {
        String resolvedProjectId = resolveProjectId(projectId);
        return invoke("getNovelProjectStatus", Map.of("projectId", safe(resolvedProjectId)),
                () -> novelProjectService.getStatus(resolvedProjectId));
    }

    @Tool(description = "Get detailed NovelFlow project overview: source file, all chapter char counts, translated chapters, polished chapters, excerpt attempts, and recent project events.")
    public String getNovelProjectOverview(@ToolParam(description = "NovelFlow projectId. Pass empty string to use current session project.") String projectId) {
        String resolvedProjectId = resolveProjectId(projectId);
        return invoke("getNovelProjectOverview", Map.of("projectId", safe(resolvedProjectId)),
                () -> novelProjectService.getProjectSummary(resolvedProjectId));
    }

    @Tool(description = "Get human-readable NovelFlow runtime state projection, including current node and review status.")
    public String getNovelRuntimeState(@ToolParam(description = "NovelFlow projectId") String projectId) {
        return invoke("getNovelRuntimeState", Map.of("projectId", safe(projectId)), () -> {
            Path projectDir = novelProjectService.resolveProjectDir(projectId);
            return novelRuntimeStateService.read(projectDir, projectId);
        });
    }

    @Tool(description = "Read project term memory and locked glossary terms used by translation, review, and fixing.")
    public String getNovelTermMemory(@ToolParam(description = "NovelFlow projectId") String projectId) {
        return invoke("getNovelTermMemory", Map.of("projectId", safe(projectId)),
                () -> novelTermMemoryService.load(projectId));
    }

    @Tool(description = "Add or update a locked manual term in NovelFlow project term memory and PostgreSQL long-term memory. Use this before retrying translation when the user corrects a name or term.")
    public String updateNovelTerm(
            @ToolParam(description = "NovelFlow projectId") String projectId,
            @ToolParam(description = "Source term in the original text") String source,
            @ToolParam(description = "Canonical translated term") String target,
            @ToolParam(description = "Optional note such as role, alias, or reason") String notes) {
        String resolvedProjectId = resolveProjectId(projectId);
        return invoke("updateNovelTerm", Map.of(
                "projectId", safe(resolvedProjectId),
                "source", safe(source),
                "target", safe(target),
                "notes", safe(notes)
        ), () -> {
            NovelTermMemoryService.TermMemory termMemory =
                    novelTermMemoryService.upsertManualTerm(resolvedProjectId, source, target, notes);
            NovelAgentMemoryRecord longTermMemory = novelAgentMemoryService.remember(
                    novelAgentSessionService.current().getSessionId(),
                    resolvedProjectId,
                    "term",
                    "项目术语固定译名：" + safe(source).strip() + " => " + safe(target).strip()
                            + (safe(notes).isBlank() ? "" : "；备注：" + safe(notes).strip()),
                    safe(source).strip() + " 固定译为 " + safe(target).strip(),
                    "project",
                    "term:" + resolvedProjectId + ":" + safe(source).strip(),
                    5,
                    Map.of(
                            "source", safe(source).strip(),
                            "target", safe(target).strip(),
                            "notes", safe(notes).strip(),
                            "projectId", resolvedProjectId,
                            "sourceTool", "updateNovelTerm"
                    ));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("termMemory", termMemory);
            payload.put("longTermMemory", novelAgentMemoryService.toPayload(longTermMemory));
            payload.put("message", "项目术语已写入 term-memory.json，并同步保存到 PostgreSQL 长期记忆");
            return payload;
        });
    }

    @Tool(description = "Get the current Spring AI Alibaba Graph checkpoint state for a translation job.")
    public String getTranslationGraphState(
            @ToolParam(description = "NovelFlow projectId") String projectId,
            @ToolParam(description = "Translation jobId") String jobId) {
        return invoke("getTranslationGraphState", Map.of("projectId", safe(projectId), "jobId", safe(jobId)),
                () -> novelTranslationJobService.getGraphState(projectId, jobId));
    }

    @Tool(description = "Get Spring AI Alibaba Graph checkpoint history for a translation job. Use for debugging, recovery planning, or time-travel inspection.")
    public String getTranslationGraphHistory(
            @ToolParam(description = "NovelFlow projectId") String projectId,
            @ToolParam(description = "Translation jobId") String jobId) {
        return invoke("getTranslationGraphHistory", Map.of("projectId", safe(projectId), "jobId", safe(jobId)),
                () -> novelTranslationJobService.getGraphHistory(projectId, jobId));
    }

    @Tool(description = "Fork a new translation job from a previous Spring AI Alibaba Graph checkpoint. Use for time-travel recovery or rerunning from a historical state without overwriting the original job.")
    public String forkTranslationFromCheckpoint(
            @ToolParam(description = "NovelFlow projectId") String projectId,
            @ToolParam(description = "Source translation jobId that owns the checkpoint history") String jobId,
            @ToolParam(description = "Checkpoint id from getTranslationGraphHistory") String checkpointId) {
        return invoke("forkTranslationFromCheckpoint", Map.of(
                "projectId", safe(projectId),
                "jobId", safe(jobId),
                "checkpointId", safe(checkpointId)
        ), () -> {
            TranslationJobResponse response = novelTranslationJobService.forkJobFromCheckpoint(projectId, jobId, checkpointId);
            novelAgentSessionService.recordTranslationJob(response);
            return response;
        });
    }

    @Tool(description = "Read the translated output produced by a specific translation job without starting a new job. Use this for excerpt jobs, recovered checkpoint jobs, or when the user says 'read this task result/译文'.")
    public String readTranslationJobOutput(
            @ToolParam(description = "NovelFlow projectId. Pass empty string to use current session project.") String projectId,
            @ToolParam(description = "Translation jobId. Pass empty string to use the latest job in current session.") String jobId,
            @ToolParam(description = "Character offset, usually 0.") int offset,
            @ToolParam(description = "Character limit, usually 3000-8000.") int limit) {
        NovelAgentSessionService.NovelAgentSessionState state = novelAgentSessionService.current();
        String resolvedProjectId = resolveProjectId(projectId);
        String resolvedJobId = safe(jobId).isBlank() ? state.getLastJobId() : safe(jobId).trim();
        if (resolvedJobId.isBlank()) {
            resolvedJobId = novelTranslationJobService.findLatestCompletedReadableJob(resolvedProjectId)
                    .map(TranslationJobResponse::getJobId)
                    .orElse("");
        } else if (safe(jobId).isBlank()) {
            TranslationJobResponse lastJob = novelTranslationJobService.getJob(resolvedProjectId, resolvedJobId);
            if (!"COMPLETED".equalsIgnoreCase(lastJob.getStatus())) {
                resolvedJobId = novelTranslationJobService.findLatestCompletedReadableJob(resolvedProjectId)
                        .map(TranslationJobResponse::getJobId)
                        .orElse(resolvedJobId);
            }
        }
        if (resolvedJobId.isBlank()) {
            throw new IllegalArgumentException("当前项目没有可读取的已完成翻译任务，请提供 jobId");
        }
        String finalJobId = resolvedJobId;
        return invoke("readTranslationJobOutput", Map.of(
                "projectId", safe(resolvedProjectId),
                "jobId", safe(finalJobId),
                "offset", Math.max(0, offset),
                "limit", Math.max(0, limit)
        ), () -> {
            TranslationReadResponse response = novelTranslationJobService.readJobOutput(
                    resolvedProjectId, finalJobId, Math.max(0, offset), normalizeLimit(limit));
            if (response.getSourceCharLimit() > 0) {
                novelAgentSessionService.recordExcerptTranslation(
                        response.getProjectId(),
                        response.getChapterIndex(),
                        response.getSourceStartOffset(),
                        response.getSourceEndOffset(),
                        response.getFullSourceCharCount(),
                        response.getSourceCharLimit(),
                        response.getTitle(),
                        response.getOutputPath());
            }
            return response;
        });
    }

    @Tool(description = "Read translated chapter text with offset and limit for previewing output.")
    public String readChapterTranslation(
            @ToolParam(description = "NovelFlow projectId") String projectId,
            @ToolParam(description = "1-based chapter index") int chapterIndex,
            @ToolParam(description = "Character offset, usually 0") int offset,
            @ToolParam(description = "Character limit, usually 2000") int limit) {
        String resolvedProjectId = resolveProjectId(projectId);
        int resolvedChapterIndex = chapterIndex <= 0
                ? novelAgentSessionService.current().getCurrentChapterIndex()
                : chapterIndex;
        return invoke("readChapterTranslation", Map.of(
                "projectId", safe(resolvedProjectId),
                "chapterIndex", resolvedChapterIndex,
                "offset", offset,
                "limit", limit
        ), () -> {
            TranslationReadResponse response = novelTranslationService.readTranslation(
                    resolvedProjectId, resolvedChapterIndex, Math.max(0, offset), normalizeLimit(limit));
            novelAgentSessionService.recordTranslationRead(response);
            return response;
        });
    }

    @Tool(description = "Continue reading the current translated chapter from the session offset. Use when the user says continue, next part, 继续, 下一段, 接着, or 往下.")
    public String continueCurrentTranslation(
            @ToolParam(description = "Character limit for the next page, usually 2500") int limit) {
        NovelAgentSessionService.NovelAgentSessionState state = novelAgentSessionService.current();
        String projectId = resolveProjectId(state.getCurrentProjectId());
        int chapterIndex = state.getCurrentChapterIndex();
        int offset = state.getNextOffset();
        int safeLimit = normalizeLimit(limit);
        return invoke("continueCurrentTranslation", Map.of(
                "projectId", safe(projectId),
                "chapterIndex", chapterIndex,
                "offset", offset,
                "limit", safeLimit
        ), () -> {
            if (chapterIndex <= 0) {
                throw new IllegalArgumentException("当前会话没有可继续的章节，请先翻译或读取某一章");
            }
            if (!state.isHasMore()) {
                return Map.of(
                        "message", "当前章节译文已经显示完",
                        "projectId", projectId,
                        "chapterIndex", chapterIndex,
                        "offset", offset
                );
            }
            TranslationReadResponse response = novelTranslationService.readTranslation(projectId, chapterIndex, offset, safeLimit);
            novelAgentSessionService.recordTranslationRead(response);
            return response;
        });
    }

    @Tool(description = "Get chunk-level translation progress for a chapter.")
    public String getTranslationProgress(
            @ToolParam(description = "NovelFlow projectId") String projectId,
            @ToolParam(description = "1-based chapter index") int chapterIndex) {
        String resolvedProjectId = resolveProjectId(projectId);
        int resolvedChapterIndex = chapterIndex <= 0
                ? novelAgentSessionService.current().getCurrentChapterIndex()
                : chapterIndex;
        return invoke("getTranslationProgress", Map.of("projectId", safe(resolvedProjectId), "chapterIndex", resolvedChapterIndex),
                () -> novelTranslationService.getTranslationProgress(resolvedProjectId, resolvedChapterIndex));
    }

    @Tool(description = "Get current NovelFlow agent session context, including current project, chapter, next read offset, and last job.")
    public String getCurrentNovelSession() {
        return invoke("getCurrentNovelSession", Map.of(), () -> novelAgentSessionService.current().toMap());
    }

    @Tool(description = "Inspect, repair, or cleanup NovelFlow Redis/session/checkpoint storage. mode=inspect only reports; mode=repair fixes missing project context; mode=cleanup also removes empty failed-upload projects and orphan NovelFlow graph checkpoints. applyChanges=false is safe preview; only set applyChanges=true after user explicitly asks to apply cleanup.")
    public String maintainNovelFlowStorage(
            @ToolParam(description = "inspect, repair, or cleanup. Default inspect.") String mode,
            @ToolParam(description = "False previews only. True performs writes/deletes and should only be used after explicit user approval.") boolean applyChanges) {
        String safeMode = mode == null || mode.isBlank() ? "inspect" : mode.trim();
        boolean dryRun = !applyChanges;
        return invoke("maintainNovelFlowStorage", Map.of("mode", safe(safeMode), "applyChanges", applyChanges, "dryRun", dryRun),
                () -> storageMaintenanceService.maintain(safeMode, dryRun));
    }

    @Tool(description = "Export all available translated chapters into final/translated-book.md and final/translated-book.txt.")
    public String exportNovel(
            @ToolParam(description = "NovelFlow projectId") String projectId) {
        return invoke("exportNovel", Map.of("projectId", safe(projectId)),
                () -> novelExportService.exportMarkdownAndText(projectId));
    }

    private Map<String, Object> translateChapterExcerptInternal(
            String projectId,
            int chapterIndex,
            int startSourceOffset,
            int sourceCharLimit) {
        long startedAt = System.currentTimeMillis();
        StartTranslationJobRequest request = new StartTranslationJobRequest();
        request.setChapterIndex(chapterIndex);
        request.setSourceStartOffset(startSourceOffset);
        request.setSourceCharLimit(sourceCharLimit);

        TranslationJobResponse job = novelTranslationJobService.startTranslationJob(projectId, request);
        novelAgentSessionService.recordTranslationJob(job);
        emitTranslationProgress("translation:started",
                "已启动第 " + chapterIndex + " 章片段翻译任务，等待 Graph Runtime 执行",
                job, null, startedAt);

        long deadline = System.currentTimeMillis() + 1200_000L;
        while (!isTerminal(job.getStatus()) && System.currentTimeMillis() < deadline) {
            sleep(3000);
            job = novelTranslationJobService.getJob(projectId, job.getJobId());
            novelAgentSessionService.recordTranslationJob(job);
            emitTranslationProgress("translation:progress",
                    buildTranslationProgressMessage(job, null),
                    job, null, startedAt);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("job", job);
        payload.put("completed", "COMPLETED".equalsIgnoreCase(job.getStatus()));
        if ("COMPLETED".equalsIgnoreCase(job.getStatus())) {
            emitTranslationProgress("translation:reading",
                    "片段翻译与审校完成，正在读取译文",
                    job, null, startedAt);
            String outputPath = safe(job.getOutputPath());
            if (outputPath.isBlank()) {
                throw new IllegalStateException("片段翻译已完成，但任务没有输出路径: " + job.getJobId());
            }
            Path translatedPath = Path.of(outputPath);
            if (!Files.isRegularFile(translatedPath)) {
                throw new IllegalStateException("片段译文文件不存在: " + outputPath);
            }
            String finalText;
            try {
                finalText = Files.readString(translatedPath, StandardCharsets.UTF_8).strip();
            } catch (Exception e) {
                throw new IllegalStateException("读取片段译文失败: " + e.getMessage(), e);
            }
            boolean hasMoreSource = job.getSourceEndOffset() < job.getFullSourceCharCount();
            novelAgentSessionService.recordExcerptTranslation(
                    projectId,
                    chapterIndex,
                    job.getSourceStartOffset(),
                    job.getSourceEndOffset(),
                    job.getFullSourceCharCount(),
                    job.getSourceCharLimit(),
                    job.getTitle(),
                    outputPath);

            payload.put("projectId", projectId);
            payload.put("chapterIndex", chapterIndex);
            payload.put("title", safe(job.getTitle()));
            payload.put("sourceStartOffset", job.getSourceStartOffset());
            payload.put("sourceEndOffset", job.getSourceEndOffset());
            payload.put("requestedSourceCharLimit", job.getSourceCharLimit());
            payload.put("actualSourceChars", job.getSourceCharCount());
            payload.put("fullSourceChars", job.getFullSourceCharCount());
            payload.put("hasMoreSource", hasMoreSource);
            payload.put("nextSourceOffset", job.getSourceEndOffset());
            payload.put("totalChunks", job.getTotalChunks());
            payload.put("translatedChars", finalText.length());
            payload.put("outputPath", outputPath);
            payload.put("content", finalText);
            payload.put("nextAction", hasMoreSource
                    ? "用户说“继续翻译下一段”可继续翻译后续源文片段"
                    : "当前章节源文已翻译到末尾");
        } else {
            payload.put("message", isTerminal(job.getStatus())
                    ? "片段翻译任务未完成: " + safe(job.getStatus()) + " " + safe(job.getErrorMessage())
                    : "片段翻译任务尚未完成，请稍后查询 jobId=" + job.getJobId());
            emitTranslationProgress("translation:waiting",
                    "等待时间到达上限，片段翻译任务仍在运行，可继续查询进度",
                    job, null, startedAt);
        }
        return payload;
    }

    private String invoke(String toolName, Map<String, Object> arguments, ToolOperation operation) {
        novelAgentProgressService.emit("tool:start", "调用工具: " + toolName, Map.of(
                "tool", toolName,
                "arguments", arguments == null ? Map.of() : arguments
        ));
        logger.info("NovelFlow Agent Tool START: tool={}, args={}", toolName, arguments);
        long startedAt = System.currentTimeMillis();
        try {
            Object data = operation.get();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", "ok");
            payload.put("tool", toolName);
            payload.put("elapsedMs", System.currentTimeMillis() - startedAt);
            payload.put("data", data);
            String json = objectMapper.writeValueAsString(payload);
            logger.info("NovelFlow Agent Tool END: tool={}, elapsedMs={}", toolName, payload.get("elapsedMs"));
            novelAgentProgressService.emit("tool:end", "工具完成: " + toolName, Map.of(
                    "tool", toolName,
                    "elapsedMs", payload.get("elapsedMs")
            ));
            return json;
        } catch (Exception e) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", "error");
            payload.put("tool", toolName);
            payload.put("elapsedMs", System.currentTimeMillis() - startedAt);
            payload.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            logger.warn("NovelFlow Agent Tool ERROR: tool={}, elapsedMs={}, error={}",
                    toolName, payload.get("elapsedMs"), payload.get("error"), e);
            novelAgentProgressService.emit("tool:error", "工具失败: " + toolName + " - " + payload.get("error"), Map.of(
                    "tool", toolName,
                    "elapsedMs", payload.get("elapsedMs"),
                    "error", payload.get("error")
            ));
            return toJson(payload);
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String resolveProjectId(String projectId) {
        if (projectId != null && !projectId.isBlank()) {
            if (!novelProjectService.projectExists(projectId)) {
                throw new IllegalArgumentException("小说项目不存在: " + projectId);
            }
            return projectId;
        }
        String current = novelAgentSessionService.current().getCurrentProjectId();
        if (current == null || current.isBlank()) {
            throw new IllegalArgumentException("当前会话没有 projectId，请先上传小说或指定 projectId");
        }
        if (!novelProjectService.projectExists(current)) {
            throw new IllegalArgumentException("当前会话记录的小说项目不存在: " + current
                    + "。请提供书名让 Agent 使用 findNovelProject 查找已有项目，或重新上传小说。");
        }
        return current;
    }

    private int normalizeLimit(int limit) {
        return Math.max(500, Math.min(limit <= 0 ? 2500 : limit, 8000));
    }

    private int normalizePreviewLimit(int limit) {
        return Math.max(500, Math.min(limit <= 0 ? 1200 : limit, 1600));
    }

    private int normalizeSourceExcerptLimit(int limit) {
        return Math.max(500, Math.min(limit <= 0 ? 3000 : limit, 20000));
    }

    private void emitTranslationProgress(
            String phase,
            String message,
            TranslationJobResponse job,
            TranslationProgressResponse progress,
            long startedAt) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (job != null) {
            data.put("jobId", safe(job.getJobId()));
            data.put("projectId", safe(job.getProjectId()));
            data.put("chapterIndex", job.getChapterIndex());
            data.put("jobType", safe(job.getJobType()));
            data.put("title", safe(job.getTitle()));
            data.put("sourceFileName", safe(job.getSourceFileName()));
            data.put("sourceStartOffset", job.getSourceStartOffset());
            data.put("sourceEndOffset", job.getSourceEndOffset());
            data.put("sourceCharLimit", job.getSourceCharLimit());
            data.put("sourceCharCount", job.getSourceCharCount());
            data.put("fullSourceCharCount", job.getFullSourceCharCount());
            data.put("status", safe(job.getStatus()));
            data.put("currentNode", safe(job.getCurrentNode()));
            data.put("jobMessage", safe(job.getMessage()));
            data.put("currentChunkIndex", job.getCurrentChunkIndex());
            data.put("completedChunks", job.getCompletedChunks());
            data.put("totalChunks", job.getTotalChunks());
            data.put("outputPath", safe(job.getOutputPath()));
        }
        if (progress != null) {
            data.put("title", safe(progress.getTitle()));
            data.put("progressStatus", safe(progress.getStatus()));
            data.put("completedChunks", progress.getCompletedChunks());
            data.put("totalChunks", progress.getTotalChunks());
            data.put("translatedCharCount", progress.getTranslatedCharCount());
            data.put("sourceCharCount", progress.getSourceCharCount());
            data.put("latestChunkFileName", safe(progress.getLatestChunkFileName()));
            data.put("updatedAt", safe(progress.getUpdatedAt()));
        }
        int completed = intValue(data.get("completedChunks"));
        int total = intValue(data.get("totalChunks"));
        if (total > 0) {
            data.put("percent", Math.round(completed * 1000.0 / total) / 10.0);
        }
        data.put("elapsedSeconds", Math.max(0, (System.currentTimeMillis() - startedAt) / 1000));
        novelAgentProgressService.emit(phase, message, data);
    }

    private String buildTranslationProgressMessage(TranslationJobResponse job, TranslationProgressResponse progress) {
        if (job == null) {
            return "Graph Runtime 正在执行翻译任务";
        }
        if ("FAILED".equalsIgnoreCase(job.getStatus())) {
            return "翻译任务失败: " + safe(job.getErrorMessage());
        }
        if ("CANCELLED".equalsIgnoreCase(job.getStatus())) {
            return "翻译任务已取消";
        }
        if ("INTERRUPTED".equalsIgnoreCase(job.getStatus())) {
            return "翻译任务因服务重启或进程停止而中断，可查看 Graph history 后从 checkpoint 恢复";
        }
        if ("COMPLETED".equalsIgnoreCase(job.getStatus())) {
            return isExcerptJob(job) ? "片段翻译和总审校已完成" : "章节翻译和总审校已完成";
        }
        String node = safe(job.getCurrentNode());
        int completed = progress == null ? job.getCompletedChunks() : progress.getCompletedChunks();
        int total = progress == null ? job.getTotalChunks() : progress.getTotalChunks();
        if ("REVIEW_CHAPTER".equals(node)) {
            return isExcerptJob(job)
                    ? "片段分段翻译完成，qwen3.7-max 正在进行片段总审校"
                    : "分段翻译完成，qwen3.7-max 正在进行章节总审校";
        }
        if (total > 0) {
            return "Graph Runtime 运行中: 分段 " + completed + "/" + total + "，当前节点 " + node;
        }
        return "Graph Runtime 运行中: 当前节点 " + node;
    }

    private boolean isExcerptJob(TranslationJobResponse job) {
        return job != null && ("EXCERPT".equalsIgnoreCase(job.getJobType()) || job.getSourceCharLimit() > 0);
    }

    private int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }

    private long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return 0L;
    }

    private long projectUsefulness(Map<String, Object> summary) {
        if (summary == null) {
            return 0L;
        }
        int polishedRank = Math.max(intValue(summary.get("fullPolishedChapters")), intValue(summary.get("fullPolishedCount")));
        int translatedRank = Math.max(intValue(summary.get("fullTranslatedChapters")), intValue(summary.get("fullTranslatedCount")));
        return polishedRank * 1_000_000_000L
                + translatedRank * 10_000_000L
                + intValue(summary.get("chapterCount")) * 10_000L
                + longValue(summary.get("lastModified")) / 1_000_000L;
    }

    private boolean isTerminal(String status) {
        return "COMPLETED".equalsIgnoreCase(status)
                || "FAILED".equalsIgnoreCase(status)
                || "CANCELLED".equalsIgnoreCase(status)
                || "INTERRUPTED".equalsIgnoreCase(status);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待翻译任务时被中断", e);
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ignored) {
            return "{\"status\":\"error\",\"error\":\"failed to serialize tool result\"}";
        }
    }

    @FunctionalInterface
    private interface ToolOperation {
        Object get() throws Exception;
    }
}

