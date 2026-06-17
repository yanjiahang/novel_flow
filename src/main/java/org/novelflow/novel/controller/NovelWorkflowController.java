package org.novelflow.novel.controller;

import org.novelflow.novel.dto.ApiResponse;
import org.novelflow.novel.dto.CreateNovelProjectRequest;
import org.novelflow.novel.dto.NovelProjectResponse;
import org.novelflow.novel.dto.NovelProjectStatusResponse;
import org.novelflow.novel.dto.NovelGraphDiagramResponse;
import org.novelflow.novel.dto.NovelGraphHistoryResponse;
import org.novelflow.novel.dto.NovelGraphSnapshotResponse;
import org.novelflow.novel.dto.NovelRuntimeStateResponse;
import org.novelflow.novel.dto.SplitNovelRequest;
import org.novelflow.novel.dto.SplitNovelResponse;
import org.novelflow.novel.dto.StartTranslationJobRequest;
import org.novelflow.novel.dto.TranslateChapterRequest;
import org.novelflow.novel.dto.TranslateChapterResponse;
import org.novelflow.novel.dto.TranslationJobResponse;
import org.novelflow.novel.dto.TranslationProgressResponse;
import org.novelflow.novel.dto.TranslationReadResponse;
import org.novelflow.novel.dto.UploadSourceResponse;
import org.novelflow.novel.service.ChapterSplitService;
import org.novelflow.novel.service.NovelProjectService;
import org.novelflow.novel.service.NovelTermMemoryService;
import org.novelflow.novel.service.NovelRuntimeStateService;
import org.novelflow.novel.service.NovelTranslationJobService;
import org.novelflow.novel.service.NovelTranslationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/novels")
public class NovelWorkflowController {

    private static final Logger logger = LoggerFactory.getLogger(NovelWorkflowController.class);

    private final NovelProjectService novelProjectService;
    private final ChapterSplitService chapterSplitService;
    private final NovelTranslationService novelTranslationService;
    private final NovelTranslationJobService novelTranslationJobService;
    private final NovelRuntimeStateService novelRuntimeStateService;
    private final NovelTermMemoryService novelTermMemoryService;

    public NovelWorkflowController(
            NovelProjectService novelProjectService,
            ChapterSplitService chapterSplitService,
            NovelTranslationService novelTranslationService,
            NovelTranslationJobService novelTranslationJobService,
            NovelRuntimeStateService novelRuntimeStateService,
            NovelTermMemoryService novelTermMemoryService) {
        this.novelProjectService = novelProjectService;
        this.chapterSplitService = chapterSplitService;
        this.novelTranslationService = novelTranslationService;
        this.novelTranslationJobService = novelTranslationJobService;
        this.novelRuntimeStateService = novelRuntimeStateService;
        this.novelTermMemoryService = novelTermMemoryService;
    }

    @PostMapping("/projects")
    public ResponseEntity<ApiResponse<NovelProjectResponse>> createProject(@RequestBody CreateNovelProjectRequest request) {
        try {
            NovelProjectResponse response = novelProjectService.createProject(request);
            logger.info("小说项目创建完成: {}", response.getProjectId());
            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (Exception e) {
            logger.error("小说项目创建失败", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/projects")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listProjects(
            @RequestParam(required = false, defaultValue = "") String query) {
        try {
            return ResponseEntity.ok(ApiResponse.success(novelProjectService.listProjectSummaries(query)));
        } catch (Exception e) {
            logger.error("查询小说项目列表失败: query={}", query, e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/projects/reuse-candidate")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reuseCandidate(
            @RequestParam(required = false, defaultValue = "") String sourceFileName,
            @RequestParam(required = false, defaultValue = "") String projectName) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    novelProjectService.findReusableProject(sourceFileName, projectName).orElse(Map.of())));
        } catch (Exception e) {
            logger.error("查询可复用小说项目失败: sourceFileName={}, projectName={}", sourceFileName, projectName, e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping(value = "/projects/{projectId}/source", consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<UploadSourceResponse>> uploadSource(
            @PathVariable String projectId,
            @RequestParam("file") MultipartFile file) {
        try {
            UploadSourceResponse response = novelProjectService.uploadSource(projectId, file);
            logger.info("小说原文上传完成: projectId={}, file={}", projectId, response.getFileName());
            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (Exception e) {
            logger.error("小说原文上传失败: projectId={}", projectId, e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/projects/{projectId}/split")
    public ResponseEntity<ApiResponse<SplitNovelResponse>> split(
            @PathVariable String projectId,
            @RequestBody(required = false) SplitNovelRequest request) {
        try {
            String sourceFileName = request == null ? null : request.getSourceFileName();
            SplitNovelResponse response = chapterSplitService.split(projectId, sourceFileName);
            logger.info("小说章节拆分完成: projectId={}, chapters={}", projectId, response.getTotalChapters());
            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (Exception e) {
            logger.error("小说章节拆分失败: projectId={}", projectId, e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/projects/{projectId}")
    public ResponseEntity<ApiResponse<NovelProjectStatusResponse>> status(@PathVariable String projectId) {
        try {
            return ResponseEntity.ok(ApiResponse.success(novelProjectService.getStatus(projectId)));
        } catch (Exception e) {
            logger.error("查询小说项目状态失败: projectId={}", projectId, e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/projects/{projectId}/overview")
    public ResponseEntity<ApiResponse<Map<String, Object>>> overview(@PathVariable String projectId) {
        try {
            return ResponseEntity.ok(ApiResponse.success(novelProjectService.getProjectSummary(projectId)));
        } catch (Exception e) {
            logger.error("查询小说项目概览失败: projectId={}", projectId, e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    @DeleteMapping("/projects/{projectId}/empty")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteEmptyProject(@PathVariable String projectId) {
        try {
            return ResponseEntity.ok(ApiResponse.success(novelProjectService.deleteEmptyProject(projectId)));
        } catch (Exception e) {
            logger.error("删除空小说项目失败: projectId={}", projectId, e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/projects/{projectId}/runtime-state")
    public ResponseEntity<ApiResponse<NovelRuntimeStateResponse>> runtimeState(@PathVariable String projectId) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    novelRuntimeStateService.read(novelProjectService.resolveProjectDir(projectId), projectId)));
        } catch (Exception e) {
            logger.error("查询 NovelFlow runtime 状态失败: projectId={}", projectId, e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/projects/{projectId}/term-memory")
    public ResponseEntity<ApiResponse<NovelTermMemoryService.TermMemory>> termMemory(@PathVariable String projectId) {
        try {
            return ResponseEntity.ok(ApiResponse.success(novelTermMemoryService.load(projectId)));
        } catch (Exception e) {
            logger.error("查询小说术语记忆失败: projectId={}", projectId, e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/projects/{projectId}/translate")
    public ResponseEntity<ApiResponse<TranslateChapterResponse>> translate(
            @PathVariable String projectId,
            @RequestBody(required = false) TranslateChapterRequest request) {
        logger.warn("旧同步翻译接口已禁用: projectId={}", projectId);
        return ResponseEntity.ok(ApiResponse.error("旧同步翻译接口已禁用，请刷新页面后使用后台 translation job"));
    }

    @PostMapping("/projects/{projectId}/translation-jobs")
    public ResponseEntity<ApiResponse<TranslationJobResponse>> startTranslationJob(
            @PathVariable String projectId,
            @RequestBody(required = false) StartTranslationJobRequest request) {
        try {
            TranslationJobResponse response = novelTranslationJobService.startTranslationJob(projectId, request);
            logger.info("小说章节翻译任务已提交: projectId={}, jobId={}, chapterIndex={}",
                    projectId, response.getJobId(), response.getChapterIndex());
            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (Exception e) {
            logger.error("提交小说章节翻译任务失败: projectId={}", projectId, e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/projects/{projectId}/translation-jobs/{jobId}")
    public ResponseEntity<ApiResponse<TranslationJobResponse>> getTranslationJob(
            @PathVariable String projectId,
            @PathVariable String jobId) {
        try {
            TranslationJobResponse response = novelTranslationJobService.getJob(projectId, jobId);
            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (Exception e) {
            logger.error("查询小说章节翻译任务失败: projectId={}, jobId={}", projectId, jobId, e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/projects/{projectId}/translation-jobs/{jobId}/output")
    public ResponseEntity<ApiResponse<TranslationReadResponse>> readTranslationJobOutput(
            @PathVariable String projectId,
            @PathVariable String jobId,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "3000") int limit) {
        try {
            TranslationReadResponse response = novelTranslationJobService.readJobOutput(projectId, jobId, offset, limit);
            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (Exception e) {
            logger.error("读取小说翻译任务输出失败: projectId={}, jobId={}", projectId, jobId, e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/projects/{projectId}/translation-jobs/{jobId}/cancel")
    public ResponseEntity<ApiResponse<TranslationJobResponse>> cancelTranslationJob(
            @PathVariable String projectId,
            @PathVariable String jobId) {
        try {
            TranslationJobResponse response = novelTranslationJobService.cancelJob(projectId, jobId);
            logger.info("小说章节翻译任务取消请求已提交: projectId={}, jobId={}", projectId, jobId);
            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (Exception e) {
            logger.error("取消小说章节翻译任务失败: projectId={}, jobId={}", projectId, jobId, e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/projects/{projectId}/translation-jobs/{jobId}/retry")
    public ResponseEntity<ApiResponse<TranslationJobResponse>> retryTranslationJob(
            @PathVariable String projectId,
            @PathVariable String jobId) {
        try {
            TranslationJobResponse response = novelTranslationJobService.retryJob(projectId, jobId);
            logger.info("小说章节翻译任务已重试: projectId={}, oldJobId={}, newJobId={}",
                    projectId, jobId, response.getJobId());
            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (Exception e) {
            logger.error("重试小说章节翻译任务失败: projectId={}, jobId={}", projectId, jobId, e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/translation-graph")
    public ResponseEntity<ApiResponse<NovelGraphDiagramResponse>> translationGraph() {
        try {
            return ResponseEntity.ok(ApiResponse.success(novelTranslationJobService.getTranslationGraphDiagram()));
        } catch (Exception e) {
            logger.error("查询 NovelFlow Graph 结构失败", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/projects/{projectId}/translation-jobs/{jobId}/graph-state")
    public ResponseEntity<ApiResponse<NovelGraphSnapshotResponse>> translationJobGraphState(
            @PathVariable String projectId,
            @PathVariable String jobId) {
        try {
            return ResponseEntity.ok(ApiResponse.success(novelTranslationJobService.getGraphState(projectId, jobId)));
        } catch (Exception e) {
            logger.error("查询小说章节翻译 Graph 状态失败: projectId={}, jobId={}", projectId, jobId, e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/projects/{projectId}/translation-jobs/{jobId}/graph-history")
    public ResponseEntity<ApiResponse<NovelGraphHistoryResponse>> translationJobGraphHistory(
            @PathVariable String projectId,
            @PathVariable String jobId) {
        try {
            return ResponseEntity.ok(ApiResponse.success(novelTranslationJobService.getGraphHistory(projectId, jobId)));
        } catch (Exception e) {
            logger.error("查询小说章节翻译 Graph 历史失败: projectId={}, jobId={}", projectId, jobId, e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/projects/{projectId}/translation-jobs/{jobId}/graph-checkpoints/{checkpointId}/fork")
    public ResponseEntity<ApiResponse<TranslationJobResponse>> forkTranslationJobFromCheckpoint(
            @PathVariable String projectId,
            @PathVariable String jobId,
            @PathVariable String checkpointId) {
        try {
            TranslationJobResponse response = novelTranslationJobService.forkJobFromCheckpoint(projectId, jobId, checkpointId);
            logger.info("小说章节翻译任务已从 Graph checkpoint 派生: projectId={}, sourceJobId={}, checkpointId={}, newJobId={}",
                    projectId, jobId, checkpointId, response.getJobId());
            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (Exception e) {
            logger.error("从小说章节翻译 Graph checkpoint 派生任务失败: projectId={}, jobId={}, checkpointId={}",
                    projectId, jobId, checkpointId, e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/projects/{projectId}/translations/{chapterIndex}")
    public ResponseEntity<ApiResponse<TranslationReadResponse>> readTranslation(
            @PathVariable String projectId,
            @PathVariable int chapterIndex,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "2000") int limit) {
        try {
            TranslationReadResponse response = novelTranslationService.readTranslation(projectId, chapterIndex, offset, limit);
            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (Exception e) {
            logger.error("读取小说译文失败: projectId={}, chapterIndex={}", projectId, chapterIndex, e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/projects/{projectId}/translations/{chapterIndex}/progress")
    public ResponseEntity<ApiResponse<TranslationProgressResponse>> translationProgress(
            @PathVariable String projectId,
            @PathVariable int chapterIndex) {
        try {
            TranslationProgressResponse response = novelTranslationService.getTranslationProgress(projectId, chapterIndex);
            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (Exception e) {
            logger.error("读取小说翻译进度失败: projectId={}, chapterIndex={}", projectId, chapterIndex, e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }
}

