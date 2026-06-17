package org.novelflow.controller;

import lombok.Getter;
import lombok.Setter;
import org.novelflow.novel.dto.TranslationJobResponse;
import org.novelflow.novel.agent.session.NovelAgentPersistenceService;
import org.novelflow.novel.agent.session.NovelAgentSessionService;
import org.novelflow.novel.service.NovelTranslationJobService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Session API used by the React workspace to restore persisted conversations.
 * Agent execution itself is handled by /api/agent/stream.
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger logger = LoggerFactory.getLogger(ChatController.class);

    private final NovelAgentPersistenceService novelAgentPersistenceService;
    private final NovelAgentSessionService novelAgentSessionService;
    private final NovelTranslationJobService novelTranslationJobService;

    public ChatController(
            NovelAgentPersistenceService novelAgentPersistenceService,
            NovelAgentSessionService novelAgentSessionService,
            NovelTranslationJobService novelTranslationJobService) {
        this.novelAgentPersistenceService = novelAgentPersistenceService;
        this.novelAgentSessionService = novelAgentSessionService;
        this.novelTranslationJobService = novelTranslationJobService;
    }

    @PostMapping("/clear")
    public ResponseEntity<ApiResponse<String>> clearChatHistory(@RequestBody ClearRequest request) {
        try {
            String sessionId = normalizeSessionId(request == null ? "" : request.getId());
            logger.info("收到清空会话历史请求 - SessionId: {}", sessionId);
            if (!novelAgentPersistenceService.sessionExists(sessionId)) {
                return ResponseEntity.ok(ApiResponse.error("会话不存在"));
            }
            novelAgentPersistenceService.clearSession(sessionId);
            novelAgentSessionService.clearSession(sessionId);
            return ResponseEntity.ok(ApiResponse.success("会话历史已清空"));
        } catch (Exception e) {
            logger.error("清空会话历史失败", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/session/{sessionId}")
    public ResponseEntity<ApiResponse<SessionInfoResponse>> getSessionInfo(@PathVariable String sessionId) {
        try {
            String normalizedSessionId = normalizeSessionId(sessionId);
            logger.info("收到获取会话信息请求 - SessionId: {}", normalizedSessionId);
            if (!novelAgentPersistenceService.sessionExists(normalizedSessionId)) {
                return ResponseEntity.ok(ApiResponse.error("会话不存在"));
            }
            SessionInfoResponse response = new SessionInfoResponse();
            response.setSessionId(normalizedSessionId);
            response.setMessagePairCount(novelAgentPersistenceService.messagePairCount(normalizedSessionId));
            response.setCreateTime(novelAgentPersistenceService.createTime(normalizedSessionId));
            response.setUpdatedAt(novelAgentPersistenceService.updatedAt(normalizedSessionId));
            response.setNovelContext(enrichRecoverableJobContext(novelAgentSessionService.snapshot(normalizedSessionId)));
            response.setMessages(novelAgentPersistenceService.readRecentHistory(normalizedSessionId, 100));
            response.setTitle(deriveSessionTitle(response.getMessages(), response.getNovelContext(), normalizedSessionId));
            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (Exception e) {
            logger.error("获取会话信息失败", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/sessions")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listSessions(
            @RequestParam(defaultValue = "50") int limit) {
        try {
            return ResponseEntity.ok(ApiResponse.success(novelAgentPersistenceService.listSessionSummaries(limit)));
        } catch (Exception e) {
            logger.error("获取会话列表失败", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    private String normalizeSessionId(String sessionId) {
        String value = sessionId == null ? "" : sessionId.trim();
        return value.isBlank() ? UUID.randomUUID().toString() : value;
    }

    private Map<String, Object> enrichRecoverableJobContext(Map<String, Object> context) {
        Map<String, Object> enriched = new LinkedHashMap<>(context == null ? Map.of() : context);
        String projectId = stringValue(enriched.get("currentNovelProjectId"));
        String jobId = stringValue(enriched.get("lastTranslationJobId"));
        if (projectId.isBlank()) {
            return enriched;
        }
        try {
            TranslationJobResponse job = jobId.isBlank()
                    ? novelTranslationJobService.findLatestInterruptedJob(projectId).orElse(null)
                    : novelTranslationJobService.getJob(projectId, jobId);
            if (job == null) {
                return enriched;
            }
            enriched.put("lastJobStatus", job.getStatus());
            if ("INTERRUPTED".equalsIgnoreCase(job.getStatus())) {
                enriched.put("lastTranslationJobId", job.getJobId());
                Map<String, Object> interrupted = new LinkedHashMap<>();
                interrupted.put("projectId", job.getProjectId());
                interrupted.put("jobId", job.getJobId());
                interrupted.put("chapterIndex", job.getChapterIndex());
                interrupted.put("jobType", job.getJobType());
                interrupted.put("title", job.getTitle());
                interrupted.put("status", job.getStatus());
                interrupted.put("currentNode", job.getCurrentNode());
                interrupted.put("completedChunks", job.getCompletedChunks());
                interrupted.put("totalChunks", job.getTotalChunks());
                interrupted.put("message", job.getMessage());
                interrupted.put("recoverHint", "可查看 Graph history 后从失败前 checkpoint 派生恢复任务");
                enriched.put("recoverableInterruptedJob", interrupted);
            }
        } catch (Exception e) {
            logger.debug("刷新会话最近任务状态失败: projectId={}, jobId={}, error={}",
                    projectId, jobId, e.getMessage());
        }
        return enriched;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String deriveSessionTitle(List<Map<String, String>> messages, Map<String, Object> context, String fallback) {
        if (context != null) {
            String sourceFileName = String.valueOf(context.getOrDefault("currentNovelSourceFileName", "")).trim();
            if (!sourceFileName.isBlank()) {
                return abbreviate(sourceFileName.replaceFirst("\\.[^.]+$", ""), 34);
            }
        }
        if (messages != null) {
            for (Map<String, String> message : messages) {
                if ("user".equals(message.get("role"))) {
                    return abbreviate(message.get("content"), 34);
                }
            }
        }
        return abbreviate(fallback, 34);
    }

    private String abbreviate(String value, int maxLength) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized.isBlank() ? "未命名对话" : normalized;
        }
        return normalized.substring(0, Math.max(1, maxLength - 1)) + "...";
    }

    @Setter
    @Getter
    public static class ClearRequest {
        @com.fasterxml.jackson.annotation.JsonProperty(value = "Id")
        @com.fasterxml.jackson.annotation.JsonAlias({"id", "ID"})
        private String Id;
    }

    @Setter
    @Getter
    public static class SessionInfoResponse {
        private String sessionId;
        private String title;
        private int messagePairCount;
        private long createTime;
        private long updatedAt;
        private Map<String, Object> novelContext;
        private List<Map<String, String>> messages;
    }

    @Getter
    @Setter
    public static class ApiResponse<T> {
        private int code;
        private String message;
        private T data;

        public static <T> ApiResponse<T> success(T data) {
            ApiResponse<T> response = new ApiResponse<>();
            response.setCode(200);
            response.setMessage("success");
            response.setData(data);
            return response;
        }

        public static <T> ApiResponse<T> error(String message) {
            ApiResponse<T> response = new ApiResponse<>();
            response.setCode(500);
            response.setMessage(message);
            return response;
        }
    }
}

