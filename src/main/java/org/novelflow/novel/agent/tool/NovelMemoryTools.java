package org.novelflow.novel.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.novelflow.novel.agent.memory.NovelAgentMemoryRecord;
import org.novelflow.novel.agent.memory.NovelAgentMemoryService;
import org.novelflow.novel.agent.session.NovelAgentProgressService;
import org.novelflow.novel.agent.session.NovelAgentSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class NovelMemoryTools {

    private static final Logger logger = LoggerFactory.getLogger(NovelMemoryTools.class);

    private final NovelAgentMemoryService memoryService;
    private final NovelAgentSessionService sessionService;
    private final NovelAgentProgressService progressService;
    private final ObjectMapper objectMapper;

    public NovelMemoryTools(
            NovelAgentMemoryService memoryService,
            NovelAgentSessionService sessionService,
            NovelAgentProgressService progressService,
            ObjectMapper objectMapper) {
        this.memoryService = memoryService;
        this.sessionService = sessionService;
        this.progressService = progressService;
        this.objectMapper = objectMapper;
    }

    @Tool(description = "Save a durable NovelFlow long-term memory into PostgreSQL. Use when the user says to remember a preference, fixed translation term, correction rule, or workflow habit across sessions.")
    public String rememberNovelFlowMemory(
            @ToolParam(description = "Memory category: preference, term, correction, workflow, project, or skill.") String category,
            @ToolParam(description = "The exact durable fact/rule/preference to remember. Do not store secrets.") String content,
            @ToolParam(description = "Short optional summary, max 500 chars.") String summary,
            @ToolParam(description = "Scope: user, project, session, or global. Use project for project-specific terminology.") String scopeType,
            @ToolParam(description = "Optional stable memory key. Empty lets the system generate one and merge duplicates.") String memoryKey,
            @ToolParam(description = "Importance 1-5. Use 5 for explicit user preferences or required terminology.") int importance) {
        return invoke("rememberNovelFlowMemory", Map.of(
                "category", safe(category),
                "scopeType", safe(scopeType),
                "memoryKey", safe(memoryKey),
                "importance", importance
        ), () -> {
            NovelAgentSessionService.NovelAgentSessionState state = sessionService.current();
            String projectId = "project".equalsIgnoreCase(safe(scopeType)) ? state.getCurrentProjectId() : "";
            NovelAgentMemoryRecord memory = memoryService.remember(
                    state.getSessionId(),
                    projectId,
                    category,
                    content,
                    summary,
                    scopeType,
                    memoryKey,
                    importance,
                    Map.of("source", "agent_tool"));
            return Map.of(
                    "saved", true,
                    "memory", memoryService.toPayload(memory),
                    "message", "长期记忆已保存到 PostgreSQL"
            );
        });
    }

    @Tool(description = "Search NovelFlow long-term memories from PostgreSQL by semantic vector and trigram text matching. Use when user asks about remembered preferences, terms, past corrections, or cross-session habits.")
    public String recallNovelFlowMemory(
            @ToolParam(description = "Search query. Leave empty to list recent memories.") String query,
            @ToolParam(description = "Optional category filter: preference, term, correction, workflow, project, skill.") String category,
            @ToolParam(description = "Max results, usually 5-8.") int limit) {
        return invoke("recallNovelFlowMemory", Map.of(
                "query", safe(query),
                "category", safe(category),
                "limit", limit
        ), () -> {
            NovelAgentSessionService.NovelAgentSessionState state = sessionService.current();
            List<Map<String, Object>> memories = memoryService.search(
                            memoryService.userId(),
                            state.getSessionId(),
                            state.getCurrentProjectId(),
                            query,
                            category,
                            limit)
                    .stream()
                    .map(memoryService::toPayload)
                    .toList();
            return Map.of(
                    "count", memories.size(),
                    "memories", memories
            );
        });
    }

    @Tool(description = "Disable a NovelFlow long-term memory by memoryUuid or key. Use only when the user asks to forget or remove a stored memory.")
    public String forgetNovelFlowMemory(
            @ToolParam(description = "memoryUuid or memory key returned by recallNovelFlowMemory.") String memoryUuidOrKey) {
        return invoke("forgetNovelFlowMemory", Map.of("memoryUuidOrKey", safe(memoryUuidOrKey)), () -> {
            int updated = memoryService.forget(memoryUuidOrKey);
            return Map.of(
                    "disabled", updated,
                    "message", updated > 0 ? "长期记忆已停用" : "未找到匹配的长期记忆"
            );
        });
    }

    private String invoke(String toolName, Map<String, Object> arguments, ToolOperation operation) {
        progressService.emit("tool:start", "调用工具: " + toolName, Map.of(
                "tool", toolName,
                "arguments", arguments == null ? Map.of() : arguments
        ));
        long startedAt = System.currentTimeMillis();
        logger.info("NovelFlow Memory Tool START: tool={}, args={}", toolName, arguments);
        try {
            Object data = operation.get();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", "ok");
            payload.put("tool", toolName);
            payload.put("elapsedMs", System.currentTimeMillis() - startedAt);
            payload.put("data", data);
            progressService.emit("tool:end", "工具完成: " + toolName, Map.of(
                    "tool", toolName,
                    "elapsedMs", payload.get("elapsedMs")
            ));
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", "error");
            payload.put("tool", toolName);
            payload.put("elapsedMs", System.currentTimeMillis() - startedAt);
            payload.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            logger.warn("NovelFlow Memory Tool ERROR: tool={}, error={}", toolName, payload.get("error"), e);
            progressService.emit("tool:error", "工具失败: " + toolName + " - " + payload.get("error"), Map.of(
                    "tool", toolName,
                    "elapsedMs", payload.get("elapsedMs"),
                    "error", payload.get("error")
            ));
            return toJson(payload);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ignored) {
            return "{\"status\":\"error\",\"error\":\"failed to serialize tool result\"}";
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    @FunctionalInterface
    private interface ToolOperation {
        Object get() throws Exception;
    }
}
