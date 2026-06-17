package org.novelflow.novel.agent.session;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import jakarta.annotation.PreDestroy;
import org.novelflow.config.NovelGraphCheckpointProperties;
import org.novelflow.novel.service.NovelProjectService;
import org.redisson.Redisson;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

@Service
public class NovelFlowStorageMaintenanceService {

    private static final String AGENT_THREAD_PREFIX = "novelflow-agent-";
    private static final String TRANSLATION_THREAD_PREFIX = "translation-";
    private static final String GRAPH_THREAD_META_PREFIX = "graph:thread:meta:";
    private static final String GRAPH_THREAD_REVERSE_PREFIX = "graph:thread:reverse:";
    private static final String GRAPH_CONTENT_PREFIX = "graph:checkpoint:content:";
    private static final String GRAPH_LOCK_PREFIX = "graph:checkpoint:lock:";

    private final NovelAgentPersistenceService persistenceService;
    private final NovelAgentSessionService sessionService;
    private final NovelProjectService projectService;
    private final boolean redisEnabled;
    private final RedissonClient redissonClient;

    public NovelFlowStorageMaintenanceService(
            NovelAgentPersistenceService persistenceService,
            NovelAgentSessionService sessionService,
            NovelProjectService projectService,
            NovelGraphCheckpointProperties checkpointProperties) {
        this.persistenceService = persistenceService;
        this.sessionService = sessionService;
        this.projectService = projectService;
        this.redisEnabled = "redis".equalsIgnoreCase(checkpointProperties.getSaver());
        this.redissonClient = redisEnabled ? createRedissonClient(checkpointProperties.getRedis()) : null;
    }

    public Map<String, Object> maintain(String mode, boolean dryRun) {
        String safeMode = normalizeMode(mode);
        MaintenanceSnapshot snapshot = snapshot();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", safeMode);
        result.put("dryRun", dryRun);
        result.put("redisEnabled", redisEnabled);
        result.put("workspace", projectService.getWorkspaceRoot().toString());
        result.put("inspect", snapshot.toMap());

        if ("inspect".equals(safeMode)) {
            result.put("actions", List.of());
            return result;
        }

        List<Map<String, Object>> actions = new ArrayList<>();
        if ("repair".equals(safeMode) || "cleanup".equals(safeMode)) {
            actions.addAll(repairSessionProjectContexts(snapshot, dryRun));
        }
        if ("cleanup".equals(safeMode)) {
            actions.addAll(cleanupEmptyProjects(snapshot, dryRun));
            actions.addAll(cleanupOrphanGraphCheckpoints(snapshot, dryRun));
            actions.addAll(cleanupBrokenSessions(snapshot, dryRun));
        }

        result.put("actions", actions);
        result.put("actionCount", actions.size());
        result.put("postInspect", dryRun ? Map.of("skipped", "dryRun=true") : snapshot().toMap());
        return result;
    }

    private MaintenanceSnapshot snapshot() {
        List<Map<String, Object>> sessions = persistenceService.listSessionSummaries(200);
        List<Map<String, Object>> projects = projectService.listProjectSummaries("");
        Set<String> sessionIds = new HashSet<>();
        Set<String> activeAgentThreads = new HashSet<>();
        for (Map<String, Object> session : sessions) {
            String sessionId = stringValue(session.get("sessionId"));
            if (!sessionId.isBlank()) {
                sessionIds.add(sessionId);
                activeAgentThreads.add(persistenceService.agentThreadId(sessionId));
            }
        }

        Set<String> projectIds = new HashSet<>();
        Set<String> activeJobIds = new HashSet<>();
        List<Map<String, Object>> emptyProjects = new ArrayList<>();
        for (Map<String, Object> project : projects) {
            String projectId = stringValue(project.get("projectId"));
            if (!projectId.isBlank()) {
                projectIds.add(projectId);
                activeJobIds.addAll(listProjectJobIds(projectId));
            }
            if (isEmptyProjectSummary(project)) {
                emptyProjects.add(Map.of(
                        "projectId", projectId,
                        "projectName", stringValue(project.get("projectName")),
                        "lastModified", longValue(project.get("lastModified"))
                ));
            }
        }

        List<Map<String, Object>> sessionProblems = new ArrayList<>();
        for (Map<String, Object> session : sessions) {
            String sessionId = stringValue(session.get("sessionId"));
            Map<String, Object> context = mapValue(session.get("novelContext"));
            String projectId = stringValue(context.get("currentNovelProjectId"));
            if (!projectId.isBlank() && !projectService.projectExists(projectId)) {
                sessionProblems.add(Map.of(
                        "sessionId", sessionId,
                        "problem", "MISSING_PROJECT",
                        "projectId", projectId,
                        "sourceFileName", stringValue(context.get("currentNovelSourceFileName"))
                ));
            }
        }

        RedisSnapshot redis = inspectRedis(activeAgentThreads, activeJobIds);
        return new MaintenanceSnapshot(sessions, projects, emptyProjects, sessionProblems, activeAgentThreads, activeJobIds, redis);
    }

    private RedisSnapshot inspectRedis(Set<String> activeAgentThreads, Set<String> activeJobIds) {
        if (!redisEnabled || redissonClient == null) {
            return RedisSnapshot.disabled();
        }
        List<String> novelKeys = sortedKeys("novelflow:*");
        List<String> graphKeys = sortedKeys("graph:*");
        List<Map<String, Object>> graphThreads = new ArrayList<>();
        List<Map<String, Object>> orphanGraphThreads = new ArrayList<>();
        Set<String> seenThreadNames = new HashSet<>();
        for (String key : sortedKeys(GRAPH_THREAD_META_PREFIX + "*")) {
            String threadName = key.substring(GRAPH_THREAD_META_PREFIX.length());
            RMap<String, String> meta = redissonClient.getMap(key, StringCodec.INSTANCE);
            String threadId = stringValue(meta.get("thread_id"));
            String released = stringValue(meta.get("is_released"));
            Map<String, Object> thread = graphThreadInfo(threadName, threadId, released, true, activeAgentThreads, activeJobIds);
            seenThreadNames.add(threadName);
            graphThreads.add(thread);
            if (isNovelFlowGraphThread(threadName) && !booleanValue(thread.get("active"))) {
                orphanGraphThreads.add(thread);
            }
        }
        for (String key : sortedKeys(GRAPH_THREAD_REVERSE_PREFIX + "*")) {
            String threadId = key.substring(GRAPH_THREAD_REVERSE_PREFIX.length());
            RMap<String, String> reverse = redissonClient.getMap(key, StringCodec.INSTANCE);
            String threadName = stringValue(reverse.get("thread_name"));
            if (threadName.isBlank() || seenThreadNames.contains(threadName)) {
                continue;
            }
            String released = stringValue(reverse.get("is_released"));
            Map<String, Object> thread = graphThreadInfo(threadName, threadId, released, false, activeAgentThreads, activeJobIds);
            graphThreads.add(thread);
            if (isNovelFlowGraphThread(threadName) && !booleanValue(thread.get("active"))) {
                orphanGraphThreads.add(thread);
            }
        }
        return new RedisSnapshot(true, novelKeys, graphKeys, graphThreads, orphanGraphThreads);
    }

    private Map<String, Object> graphThreadInfo(
            String threadName,
            String threadId,
            String released,
            boolean hasMeta,
            Set<String> activeAgentThreads,
            Set<String> activeJobIds) {
        String type = classifyThread(threadName);
        boolean active = ("agent".equals(type) && activeAgentThreads.contains(threadName))
                || ("translation".equals(type) && activeJobIds.contains(threadName));
        Map<String, Object> thread = new LinkedHashMap<>();
        thread.put("threadName", threadName);
        thread.put("threadId", threadId);
        thread.put("type", type);
        thread.put("released", released);
        thread.put("active", active);
        thread.put("hasMeta", hasMeta);
        thread.put("keyCount", graphKeysForThread(threadName, threadId).size());
        return thread;
    }

    private List<Map<String, Object>> repairSessionProjectContexts(MaintenanceSnapshot snapshot, boolean dryRun) {
        List<Map<String, Object>> actions = new ArrayList<>();
        for (Map<String, Object> problem : snapshot.sessionProblems()) {
            String sessionId = stringValue(problem.get("sessionId"));
            Map<String, Object> context = persistenceService.readNovelContext(sessionId);
            String oldProjectId = stringValue(context.get("currentNovelProjectId"));
            String sourceFileName = stringValue(context.get("currentNovelSourceFileName"));
            Map<String, Object> replacement = sourceFileName.isBlank()
                    ? Map.of()
                    : projectService.findReusableProject(sourceFileName, sourceFileName).orElse(Map.of());
            Map<String, Object> action = new LinkedHashMap<>();
            action.put("type", "repairSessionProjectContext");
            action.put("sessionId", sessionId);
            action.put("oldProjectId", oldProjectId);
            action.put("sourceFileName", sourceFileName);
            if (!replacement.isEmpty()) {
                String newProjectId = stringValue(replacement.get("projectId"));
                action.put("newProjectId", newProjectId);
                action.put("operation", "switchToReusableProject");
                if (!dryRun) {
                    Map<String, Object> repaired = mutableContext(context);
                    repaired.put("currentNovelProjectId", newProjectId);
                    repaired.put("currentNovelSourceFileName", stringValue(replacement.get("sourceFileName")));
                    persistenceService.saveNovelContext(sessionId, repaired);
                    sessionService.clearSession(sessionId);
                }
            } else {
                action.put("operation", "clearMissingProjectContext");
                if (!dryRun) {
                    persistenceService.saveNovelContext(sessionId, clearedProjectContext(context));
                    sessionService.clearSession(sessionId);
                }
            }
            action.put("dryRun", dryRun);
            actions.add(action);
        }
        return actions;
    }

    private List<Map<String, Object>> cleanupEmptyProjects(MaintenanceSnapshot snapshot, boolean dryRun) {
        List<Map<String, Object>> actions = new ArrayList<>();
        for (Map<String, Object> project : snapshot.emptyProjects()) {
            String projectId = stringValue(project.get("projectId"));
            Map<String, Object> action = new LinkedHashMap<>();
            action.put("type", "cleanupEmptyProject");
            action.put("projectId", projectId);
            action.put("projectName", stringValue(project.get("projectName")));
            action.put("dryRun", dryRun);
            if (!dryRun) {
                action.put("result", projectService.deleteEmptyProject(projectId));
            }
            actions.add(action);
        }
        return actions;
    }

    private List<Map<String, Object>> cleanupOrphanGraphCheckpoints(MaintenanceSnapshot snapshot, boolean dryRun) {
        List<Map<String, Object>> actions = new ArrayList<>();
        if (!redisEnabled || redissonClient == null) {
            return actions;
        }
        for (Map<String, Object> thread : snapshot.redis().orphanGraphThreads()) {
            String threadName = stringValue(thread.get("threadName"));
            String threadId = stringValue(thread.get("threadId"));
            List<String> keys = graphKeysForThread(threadName, threadId);
            Map<String, Object> action = new LinkedHashMap<>();
            action.put("type", "cleanupOrphanGraphCheckpoint");
            action.put("threadName", threadName);
            action.put("threadId", threadId);
            action.put("threadType", stringValue(thread.get("type")));
            action.put("keys", keys);
            action.put("keyCount", keys.size());
            action.put("dryRun", dryRun);
            if (!dryRun) {
                releaseCheckpoint(threadName);
                long deleted = 0;
                for (String key : keys) {
                    deleted += redissonClient.getKeys().delete(key);
                }
                action.put("deletedKeyCount", deleted);
            }
            actions.add(action);
        }
        return actions;
    }

    private List<Map<String, Object>> cleanupBrokenSessions(MaintenanceSnapshot snapshot, boolean dryRun) {
        List<Map<String, Object>> actions = new ArrayList<>();
        for (Map<String, Object> session : snapshot.sessions()) {
            String sessionId = stringValue(session.get("sessionId"));
            if (sessionId.isBlank() || persistenceService.sessionExists(sessionId)) {
                continue;
            }
            Map<String, Object> action = new LinkedHashMap<>();
            action.put("type", "cleanupBrokenSessionIndex");
            action.put("sessionId", sessionId);
            action.put("dryRun", dryRun);
            if (!dryRun) {
                persistenceService.clearSession(sessionId);
                sessionService.clearSession(sessionId);
            }
            actions.add(action);
        }
        return actions;
    }

    private void releaseCheckpoint(String threadName) {
        try {
            persistenceService.agentCheckpointSaver().release(RunnableConfig.builder()
                    .threadId(threadName)
                    .build());
        } catch (Exception ignored) {
            // The thread may already be partially deleted or released; physical key cleanup follows.
        }
    }

    private List<String> graphKeysForThread(String threadName, String threadId) {
        List<String> keys = new ArrayList<>();
        if (!threadName.isBlank()) {
            keys.add(GRAPH_THREAD_META_PREFIX + threadName);
            keys.add(GRAPH_LOCK_PREFIX + threadName);
        }
        if (!threadId.isBlank()) {
            keys.add(GRAPH_THREAD_REVERSE_PREFIX + threadId);
            keys.add(GRAPH_CONTENT_PREFIX + threadId);
        }
        return keys.stream()
                .filter(key -> redissonClient == null || redissonClient.getKeys().countExists(key) > 0)
                .distinct()
                .toList();
    }

    private List<String> listProjectJobIds(String projectId) {
        try {
            Path jobsDir = projectService.resolveProjectDir(projectId).resolve("config").resolve("jobs");
            if (!Files.isDirectory(jobsDir)) {
                return List.of();
            }
            try (Stream<Path> stream = Files.list(jobsDir)) {
                return stream
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".json"))
                        .map(path -> path.getFileName().toString().replaceFirst("\\.json$", ""))
                        .filter(name -> name.startsWith(TRANSLATION_THREAD_PREFIX))
                        .sorted()
                        .toList();
            }
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<String> sortedKeys(String pattern) {
        if (!redisEnabled || redissonClient == null) {
            return List.of();
        }
        List<String> keys = new ArrayList<>();
        for (String key : redissonClient.getKeys().getKeysByPattern(pattern)) {
            keys.add(key);
        }
        keys.sort(String::compareTo);
        return keys;
    }

    private boolean isEmptyProjectSummary(Map<String, Object> project) {
        return intValue(project.get("rawFileCount")) == 0
                && intValue(project.get("chapterCount")) == 0
                && intValue(project.get("translatedCount")) == 0
                && intValue(project.get("reviewCount")) == 0
                && intValue(project.get("polishedCount")) == 0
                && intValue(project.get("excerptCount")) == 0;
    }

    private String classifyThread(String threadName) {
        if (threadName.startsWith(AGENT_THREAD_PREFIX)) {
            return "agent";
        }
        if (threadName.startsWith(TRANSLATION_THREAD_PREFIX)) {
            return "translation";
        }
        return "unknown";
    }

    private boolean isNovelFlowGraphThread(String threadName) {
        return threadName.startsWith(AGENT_THREAD_PREFIX) || threadName.startsWith(TRANSLATION_THREAD_PREFIX);
    }

    private Map<String, Object> clearedProjectContext(Map<String, Object> context) {
        Map<String, Object> cleaned = mutableContext(context);
        cleaned.put("currentNovelProjectId", "");
        cleaned.put("currentNovelSourceFileName", "");
        cleaned.put("lastTranslationJobId", "");
        cleaned.put("lastJobStatus", "");
        cleaned.put("currentNovelTranslation", Map.of(
                "chapterIndex", 0,
                "nextOffset", 0,
                "hasMore", false,
                "title", "",
                "totalChars", 0
        ));
        cleaned.put("currentNovelExcerpt", Map.of(
                "chapterIndex", 0,
                "startSourceOffset", 0,
                "nextSourceOffset", 0,
                "totalSourceChars", 0,
                "sourceCharLimit", 3000,
                "hasMoreSource", false,
                "title", "",
                "outputPath", ""
        ));
        return cleaned;
    }

    private Map<String, Object> mutableContext(Map<String, Object> context) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (context != null) {
            result.putAll(context);
        }
        return result;
    }

    private String normalizeMode(String mode) {
        String normalized = mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "cleanup", "clean" -> "cleanup";
            case "repair", "fix" -> "repair";
            default -> "inspect";
        };
    }

    private int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        return Map.of();
    }

    private RedissonClient createRedissonClient(NovelGraphCheckpointProperties.Redis redisProperties) {
        Config config = new Config();
        SingleServerConfig serverConfig = config.useSingleServer()
                .setAddress(redisProperties.getAddress())
                .setDatabase(redisProperties.getDatabase())
                .setConnectionPoolSize(Math.max(2, redisProperties.getConnectionPoolSize()))
                .setConnectionMinimumIdleSize(Math.max(1, redisProperties.getConnectionMinimumIdleSize()))
                .setTimeout(redisProperties.getTimeoutMs());
        if (redisProperties.getPassword() != null && !redisProperties.getPassword().isBlank()) {
            serverConfig.setPassword(redisProperties.getPassword());
        }
        return Redisson.create(config);
    }

    @PreDestroy
    public void shutdown() {
        if (redissonClient != null && !redissonClient.isShutdown()) {
            redissonClient.shutdown();
        }
    }

    private record MaintenanceSnapshot(
            List<Map<String, Object>> sessions,
            List<Map<String, Object>> projects,
            List<Map<String, Object>> emptyProjects,
            List<Map<String, Object>> sessionProblems,
            Set<String> activeAgentThreads,
            Set<String> activeJobIds,
            RedisSnapshot redis) {

        private Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("sessionCount", sessions.size());
            result.put("projectCount", projects.size());
            result.put("emptyProjectCount", emptyProjects.size());
            result.put("sessionProblemCount", sessionProblems.size());
            result.put("activeAgentThreadCount", activeAgentThreads.size());
            result.put("activeTranslationJobCount", activeJobIds.size());
            result.put("sessions", sessions.stream().limit(20).toList());
            result.put("emptyProjects", emptyProjects);
            result.put("sessionProblems", sessionProblems);
            result.put("redis", redis.toMap());
            return result;
        }
    }

    private record RedisSnapshot(
            boolean enabled,
            List<String> novelKeys,
            List<String> graphKeys,
            List<Map<String, Object>> graphThreads,
            List<Map<String, Object>> orphanGraphThreads) {

        private static RedisSnapshot disabled() {
            return new RedisSnapshot(false, List.of(), List.of(), List.of(), List.of());
        }

        private Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("enabled", enabled);
            result.put("novelKeyCount", novelKeys.size());
            result.put("graphKeyCount", graphKeys.size());
            result.put("graphThreadCount", graphThreads.size());
            result.put("orphanGraphThreadCount", orphanGraphThreads.size());
            result.put("novelKeys", novelKeys.stream().limit(30).toList());
            result.put("graphThreads", graphThreads.stream()
                    .sorted(Comparator.comparing(item -> String.valueOf(item.get("threadName"))))
                    .limit(50)
                    .toList());
            result.put("orphanGraphThreads", orphanGraphThreads);
            return result;
        }
    }
}

