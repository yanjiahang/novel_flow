package org.novelflow.novel.agent.session;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.savers.file.FileSystemSaver;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.novelflow.config.NovelGraphCheckpointProperties;
import org.novelflow.novel.service.NovelProjectService;
import org.redisson.Redisson;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class NovelAgentPersistenceService {

    private static final Logger logger = LoggerFactory.getLogger(NovelAgentPersistenceService.class);
    private static final String SESSION_INDEX_KEY = "novelflow:agent:sessions";
    private static final int MAX_STORED_MESSAGES = 200;

    private final ObjectMapper objectMapper;
    private final Path storeDirectory;
    private final RedissonClient redissonClient;
    private final BaseCheckpointSaver agentCheckpointSaver;
    private final boolean redisEnabled;

    public NovelAgentPersistenceService(
            ObjectMapper objectMapper,
            NovelProjectService novelProjectService,
            NovelGraphCheckpointProperties checkpointProperties) {
        this.objectMapper = objectMapper;
        this.storeDirectory = novelProjectService.getWorkspaceRoot().resolve(".agent-sessions").normalize();
        this.redisEnabled = "redis".equalsIgnoreCase(checkpointProperties.getSaver());
        if (redisEnabled) {
            this.redissonClient = createRedissonClient(checkpointProperties.getRedis());
            try {
                this.redissonClient.getKeys().count();
            } catch (RuntimeException e) {
                this.redissonClient.shutdown();
                throw new IllegalStateException("连接 Redis agent memory 失败: " + e.getMessage(), e);
            }
            this.agentCheckpointSaver = RedisSaver.builder().redisson(this.redissonClient).build();
            logger.info("NovelFlow Agent memory initialized: store=redis, sessionKeys=novelflow:agent:*, checkpointKeys=graph:*");
        } else {
            this.redissonClient = null;
            Path checkpointDirectory = this.storeDirectory.resolve("checkpoints").normalize();
            this.agentCheckpointSaver = FileSystemSaver.builder()
                    .targetFolder(checkpointDirectory)
                    .build();
            logger.info("NovelFlow Agent memory initialized: store=file, location={}",
                    this.storeDirectory.toAbsolutePath().normalize());
        }
    }

    public BaseCheckpointSaver agentCheckpointSaver() {
        return agentCheckpointSaver;
    }

    public String agentThreadId(String sessionId) {
        return "novelflow-agent-" + normalizeSessionId(sessionId);
    }

    public boolean sessionExists(String sessionId) {
        String id = normalizeSessionId(sessionId);
        if (redisEnabled) {
            return redissonClient.getSet(SESSION_INDEX_KEY, StringCodec.INSTANCE).contains(id)
                    || redissonClient.getBucket(metaKey(id), StringCodec.INSTANCE).isExists()
                    || redissonClient.getBucket(messagesKey(id), StringCodec.INSTANCE).isExists();
        }
        return Files.isRegularFile(sessionDirectory(id).resolve("meta.json"))
                || Files.isRegularFile(sessionDirectory(id).resolve("messages.json"));
    }

    public List<Map<String, Object>> listSessionSummaries(int limit) {
        int safeLimit = Math.max(1, Math.min(limit <= 0 ? 50 : limit, 200));
        List<String> sessionIds = new ArrayList<>();
        if (redisEnabled) {
            redissonClient.getSet(SESSION_INDEX_KEY, StringCodec.INSTANCE)
                    .forEach(value -> sessionIds.add(String.valueOf(value)));
        } else if (Files.isDirectory(storeDirectory)) {
            try (var stream = Files.list(storeDirectory)) {
                stream.filter(Files::isDirectory)
                        .map(path -> path.getFileName().toString())
                        .forEach(sessionIds::add);
            } catch (IOException e) {
                logger.warn("读取 NovelFlow Agent 会话目录失败: path={}, error={}", storeDirectory, e.getMessage());
            }
        }

        return sessionIds.stream()
                .distinct()
                .filter(id -> !"default".equals(normalizeSessionId(id)))
                .filter(this::sessionExists)
                .map(this::sessionSummary)
                .sorted(Comparator.comparingLong(summary -> -longValue(summary.get("updatedAt"))))
                .limit(safeLimit)
                .toList();
    }

    public void touchSession(String sessionId) {
        String id = normalizeSessionId(sessionId);
        SessionMeta meta = readMeta(id);
        long now = System.currentTimeMillis();
        if (meta.createTime() <= 0) {
            meta = new SessionMeta(id, now, now);
        } else {
            meta = new SessionMeta(id, meta.createTime(), now);
        }
        writeMeta(id, meta);
        if (redisEnabled) {
            redissonClient.getSet(SESSION_INDEX_KEY, StringCodec.INSTANCE).add(id);
        }
    }

    public long createTime(String sessionId) {
        return readMeta(normalizeSessionId(sessionId)).createTime();
    }

    public long updatedAt(String sessionId) {
        return readMeta(normalizeSessionId(sessionId)).updatedAt();
    }

    public int messagePairCount(String sessionId) {
        return readMessages(sessionId).size() / 2;
    }

    public List<Map<String, String>> readRecentHistory(String sessionId, int maxPairs) {
        List<Map<String, String>> messages = readMessages(sessionId);
        int maxMessages = Math.max(0, maxPairs) * 2;
        if (maxMessages <= 0 || messages.size() <= maxMessages) {
            return messages;
        }
        return new ArrayList<>(messages.subList(messages.size() - maxMessages, messages.size()));
    }

    public void replaceHistory(String sessionId, List<Map<String, String>> history) {
        String id = normalizeSessionId(sessionId);
        touchSession(id);
        List<Map<String, String>> normalized = normalizeMessages(history);
        if (normalized.size() > MAX_STORED_MESSAGES) {
            normalized = new ArrayList<>(normalized.subList(normalized.size() - MAX_STORED_MESSAGES, normalized.size()));
        }
        writeMessages(id, normalized);
    }

    public void appendMessagePair(String sessionId, String userQuestion, String assistantAnswer) {
        appendMessagePair(sessionId, "user", userQuestion, assistantAnswer);
    }

    public void appendMessage(String sessionId, String role, String content) {
        String id = normalizeSessionId(sessionId);
        String safeContent = content == null ? "" : content.trim();
        if (safeContent.isBlank()) {
            return;
        }
        touchSession(id);
        List<Map<String, String>> messages = readMessages(id);
        messages.add(Map.of("role", normalizeMessageRole(role), "content", safeContent));
        if (messages.size() > MAX_STORED_MESSAGES) {
            messages = new ArrayList<>(messages.subList(messages.size() - MAX_STORED_MESSAGES, messages.size()));
        }
        writeMessages(id, messages);
    }

    public void appendMessagePair(String sessionId, String displayRole, String userQuestion, String assistantAnswer) {
        String id = normalizeSessionId(sessionId);
        touchSession(id);
        List<Map<String, String>> messages = readMessages(id);
        if (userQuestion != null && !userQuestion.isBlank()) {
            messages.add(Map.of("role", normalizeMessageRole(displayRole), "content", userQuestion));
        }
        if (assistantAnswer != null && !assistantAnswer.isBlank()) {
            messages.add(Map.of("role", "assistant", "content", assistantAnswer));
        }
        if (messages.size() > MAX_STORED_MESSAGES) {
            messages = new ArrayList<>(messages.subList(messages.size() - MAX_STORED_MESSAGES, messages.size()));
        }
        writeMessages(id, messages);
    }

    public Map<String, Object> readNovelContext(String sessionId) {
        String id = normalizeSessionId(sessionId);
        String json = readString(contextKey(id), sessionDirectory(id).resolve("context.json"));
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (IOException e) {
            logger.warn("读取 NovelFlow Agent 会话上下文失败: sessionId={}, error={}", id, e.getMessage());
            return Map.of();
        }
    }

    public void saveNovelContext(String sessionId, Map<String, Object> context) {
        String id = normalizeSessionId(sessionId);
        touchSession(id);
        try {
            writeString(contextKey(id), sessionDirectory(id).resolve("context.json"),
                    objectMapper.writeValueAsString(context == null ? Map.of() : context));
        } catch (IOException e) {
            throw new IllegalStateException("保存 NovelFlow Agent 会话上下文失败: " + e.getMessage(), e);
        }
    }

    public void clearSession(String sessionId) {
        String id = normalizeSessionId(sessionId);
        releaseAgentCheckpoint(id);
        if (redisEnabled) {
            redissonClient.getBucket(metaKey(id), StringCodec.INSTANCE).delete();
            redissonClient.getBucket(contextKey(id), StringCodec.INSTANCE).delete();
            redissonClient.getBucket(messagesKey(id), StringCodec.INSTANCE).delete();
            redissonClient.getSet(SESSION_INDEX_KEY, StringCodec.INSTANCE).remove(id);
            return;
        }
        deleteIfExists(sessionDirectory(id).resolve("meta.json"));
        deleteIfExists(sessionDirectory(id).resolve("messages.json"));
        deleteIfExists(sessionDirectory(id).resolve("context.json"));
    }

    private void releaseAgentCheckpoint(String sessionId) {
        try {
            agentCheckpointSaver.release(RunnableConfig.builder()
                    .threadId(agentThreadId(sessionId))
                    .build());
        } catch (Exception e) {
            logger.debug("释放 NovelFlow Agent checkpoint 跳过: sessionId={}, error={}", sessionId, e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        if (redissonClient != null && !redissonClient.isShutdown()) {
            redissonClient.shutdown();
        }
    }

    private List<Map<String, String>> readMessages(String sessionId) {
        String id = normalizeSessionId(sessionId);
        String json = readString(messagesKey(id), sessionDirectory(id).resolve("messages.json"));
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return normalizeMessages(objectMapper.readValue(json, new TypeReference<List<Map<String, String>>>() {
            }));
        } catch (IOException e) {
            logger.warn("读取 NovelFlow Agent 会话消息失败: sessionId={}, error={}", id, e.getMessage());
            return new ArrayList<>();
        }
    }

    private void writeMessages(String sessionId, List<Map<String, String>> messages) {
        try {
            writeString(messagesKey(sessionId), sessionDirectory(sessionId).resolve("messages.json"),
                    objectMapper.writeValueAsString(normalizeMessages(messages)));
        } catch (IOException e) {
            throw new IllegalStateException("保存 NovelFlow Agent 会话消息失败: " + e.getMessage(), e);
        }
    }

    private SessionMeta readMeta(String sessionId) {
        String json = readString(metaKey(sessionId), sessionDirectory(sessionId).resolve("meta.json"));
        if (json == null || json.isBlank()) {
            return new SessionMeta(sessionId, 0, 0);
        }
        try {
            return objectMapper.readValue(json, SessionMeta.class);
        } catch (IOException e) {
            logger.warn("读取 NovelFlow Agent 会话元数据失败: sessionId={}, error={}", sessionId, e.getMessage());
            return new SessionMeta(sessionId, 0, 0);
        }
    }

    private void writeMeta(String sessionId, SessionMeta meta) {
        try {
            writeString(metaKey(sessionId), sessionDirectory(sessionId).resolve("meta.json"),
                    objectMapper.writeValueAsString(meta));
        } catch (IOException e) {
            throw new IllegalStateException("保存 NovelFlow Agent 会话元数据失败: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> sessionSummary(String sessionId) {
        String id = normalizeSessionId(sessionId);
        SessionMeta meta = readMeta(id);
        List<Map<String, String>> messages = readRecentHistory(id, 3);
        Map<String, Object> context = readNovelContext(id);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("sessionId", id);
        summary.put("title", deriveTitle(messages, context, id));
        summary.put("messagePairCount", messagePairCount(id));
        summary.put("createTime", meta.createTime());
        summary.put("updatedAt", meta.updatedAt());
        summary.put("novelContext", context);
        summary.put("currentNovelProjectId", stringValue(context.get("currentNovelProjectId")));
        summary.put("currentNovelSourceFileName", stringValue(context.get("currentNovelSourceFileName")));
        summary.put("lastTranslationJobId", stringValue(context.get("lastTranslationJobId")));
        return summary;
    }

    private String deriveTitle(List<Map<String, String>> messages, Map<String, Object> context, String fallback) {
        String sourceFileName = stringValue(context.get("currentNovelSourceFileName"));
        if (!sourceFileName.isBlank()) {
            return abbreviate(sourceFileName.replaceFirst("\\.[^.]+$", ""), 34);
        }
        for (Map<String, String> message : messages) {
            if ("user".equals(message.get("role"))) {
                return abbreviate(message.get("content"), 34);
            }
        }
        return abbreviate(fallback, 34);
    }

    private String abbreviate(String value, int maxLength) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized.isBlank() ? "未命名对话" : normalized;
        }
        return normalized.substring(0, Math.max(1, maxLength - 1)) + "…";
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
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

    private List<Map<String, String>> normalizeMessages(List<Map<String, String>> messages) {
        List<Map<String, String>> normalized = new ArrayList<>();
        if (messages == null) {
            return normalized;
        }
        for (Map<String, String> message : messages) {
            if (message == null) {
                continue;
            }
            String role = message.getOrDefault("role", "").trim();
            String content = message.getOrDefault("content", "").trim();
            if (("user".equals(role) || "assistant".equals(role) || "system".equals(role)) && !content.isBlank()) {
                normalized.add(Map.of("role", role, "content", content));
            }
        }
        return repairLikelyCorruptedAssistantRoles(normalized);
    }

    private String normalizeMessageRole(String role) {
        String normalized = role == null ? "" : role.trim().toLowerCase();
        if ("assistant".equals(normalized)) {
            return "assistant";
        }
        if ("system".equals(normalized)) {
            return "system";
        }
        return "user";
    }

    private List<Map<String, String>> repairLikelyCorruptedAssistantRoles(List<Map<String, String>> messages) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }
        List<Map<String, String>> repaired = new ArrayList<>();
        boolean waitingForAssistant = false;
        for (Map<String, String> message : messages) {
            String role = message.getOrDefault("role", "");
            String content = message.getOrDefault("content", "");
            if ("system".equals(role)) {
                repaired.add(message);
                continue;
            }
            if ("user".equals(role) && waitingForAssistant && looksLikeAssistantMessage(content)) {
                repaired.add(Map.of("role", "assistant", "content", content));
                waitingForAssistant = false;
                continue;
            }
            repaired.add(message);
            if ("user".equals(role)) {
                waitingForAssistant = true;
            } else if ("assistant".equals(role)) {
                waitingForAssistant = false;
            }
        }
        return repaired;
    }

    private boolean looksLikeAssistantMessage(String content) {
        String text = content == null ? "" : content.stripLeading();
        if (text.length() >= 120) {
            return true;
        }
        return text.startsWith("已")
                || text.startsWith("✅")
                || text.startsWith("⚠️")
                || text.startsWith("📊")
                || text.startsWith("📋")
                || text.startsWith("章节拆分")
                || text.startsWith("以下是")
                || text.startsWith("当前会话")
                || text.startsWith("任务已")
                || text.startsWith("翻译任务")
                || text.startsWith("取消请求")
                || text.startsWith("你好！我是 NovelFlow");
    }

    private String readString(String redisKey, Path filePath) {
        if (redisEnabled) {
            RBucket<String> bucket = redissonClient.getBucket(redisKey, StringCodec.INSTANCE);
            return bucket.get();
        }
        if (!Files.isRegularFile(filePath)) {
            return "";
        }
        try {
            return Files.readString(filePath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.warn("读取文件失败: path={}, error={}", filePath, e.getMessage());
            return "";
        }
    }

    private void writeString(String redisKey, Path filePath, String value) throws IOException {
        if (redisEnabled) {
            RBucket<String> bucket = redissonClient.getBucket(redisKey, StringCodec.INSTANCE);
            bucket.set(value == null ? "" : value);
            return;
        }
        Files.createDirectories(filePath.getParent());
        Files.writeString(filePath, value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private void deleteIfExists(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            logger.warn("删除会话文件失败: path={}, error={}", path, e.getMessage());
        }
    }

    private Path sessionDirectory(String sessionId) {
        return storeDirectory.resolve(safeFileName(normalizeSessionId(sessionId))).normalize();
    }

    private String metaKey(String sessionId) {
        return "novelflow:agent:session:" + normalizeSessionId(sessionId) + ":meta";
    }

    private String messagesKey(String sessionId) {
        return "novelflow:agent:session:" + normalizeSessionId(sessionId) + ":messages";
    }

    private String contextKey(String sessionId) {
        return "novelflow:agent:session:" + normalizeSessionId(sessionId) + ":context";
    }

    private String normalizeSessionId(String sessionId) {
        return sessionId == null || sessionId.isBlank() ? "default" : sessionId.trim();
    }

    private String safeFileName(String value) {
        return value.replaceAll("[\\\\/:*?\"<>|]", "_");
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

    private record SessionMeta(String sessionId, long createTime, long updatedAt) {
    }
}

