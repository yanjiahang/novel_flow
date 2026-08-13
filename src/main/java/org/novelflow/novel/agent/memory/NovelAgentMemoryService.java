package org.novelflow.novel.agent.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.novelflow.config.NovelAgentMemoryProperties;
import org.novelflow.novel.domain.dos.NovelAgentMemoryDO;
import org.novelflow.novel.domain.mapper.NovelAgentMemoryMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class NovelAgentMemoryService {

    private static final Logger logger = LoggerFactory.getLogger(NovelAgentMemoryService.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final DateTimeFormatter KEY_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final double SCORE_ROUNDING_SCALE = 10000.0;

    private final NovelAgentMemoryProperties properties;
    private final NovelAgentMemoryMapper memoryMapper;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final ObjectMapper objectMapper;

    public NovelAgentMemoryService(
            NovelAgentMemoryProperties properties,
            NovelAgentMemoryMapper memoryMapper,
            JdbcTemplate jdbcTemplate,
            ObjectProvider<EmbeddingModel> embeddingModelProvider,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.memoryMapper = memoryMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingModelProvider = embeddingModelProvider;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void initializeSchema() {
        if (!properties.isEnabled()) {
            return;
        }
        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector");
        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS t_novel_agent_memory (
                    id BIGSERIAL PRIMARY KEY,
                    memory_uuid VARCHAR(64) UNIQUE NOT NULL,
                    user_id VARCHAR(80) NOT NULL,
                    session_id VARCHAR(100),
                    project_id VARCHAR(100),
                    namespace VARCHAR(240) NOT NULL,
                    memory_key VARCHAR(120) NOT NULL,
                    category VARCHAR(40) NOT NULL,
                    scope_type VARCHAR(20) NOT NULL DEFAULT 'user',
                    content TEXT NOT NULL,
                    summary VARCHAR(500),
                    search_text TEXT NOT NULL,
                    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
                    embedding vector(%d),
                    importance SMALLINT NOT NULL DEFAULT 3,
                    enabled BOOLEAN NOT NULL DEFAULT TRUE,
                    access_count INTEGER NOT NULL DEFAULT 0,
                    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    last_access_time TIMESTAMP,
                    CONSTRAINT uk_novel_agent_memory_user_namespace_key UNIQUE (user_id, namespace, memory_key)
                )
                """.formatted(properties.getEmbeddingDimensions()));
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_novel_agent_memory_user_update ON t_novel_agent_memory(user_id, update_time DESC)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_novel_agent_memory_project ON t_novel_agent_memory(project_id)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_novel_agent_memory_category ON t_novel_agent_memory(category)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_novel_agent_memory_search_trgm ON t_novel_agent_memory USING GIN (search_text gin_trgm_ops)");
        try {
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_novel_agent_memory_embedding_hnsw ON t_novel_agent_memory USING HNSW (embedding vector_cosine_ops)");
        } catch (Exception e) {
            logger.warn("NovelFlow long-term memory vector index initialization skipped: {}", e.getMessage());
        }
    }

    public NovelAgentMemoryRecord remember(
            String sessionId,
            String projectId,
            String category,
            String content,
            String summary,
            String scopeType,
            String memoryKey,
            int importance,
            Map<String, Object> metadata) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("NovelFlow long-term memory is disabled");
        }
        String safeContent = safe(content).trim();
        if (safeContent.isBlank()) {
            throw new IllegalArgumentException("memory content must not be blank");
        }

        String safeUserId = userId();
        String safeCategory = normalizeCategory(category);
        String safeScopeType = normalizeScope(scopeType);
        String safeProjectId = safe(projectId);
        String namespace = namespace(safeUserId, safeCategory, safeScopeType, safeProjectId);
        String key = safe(memoryKey).isBlank() ? newMemoryKey(safeCategory) : sanitizeKey(memoryKey);
        String searchText = buildSearchText(safeCategory, safeContent, summary, metadata);
        String embedding = embeddingVector(searchText);

        NovelAgentMemoryRecord duplicate = findDuplicate(safeUserId, sessionId, safeProjectId, safeCategory, searchText);
        if (duplicate != null && safe(memoryKey).isBlank()) {
            key = duplicate.getMemoryKey();
        }

        NovelAgentMemoryDO memory = NovelAgentMemoryDO.builder()
                .memoryUuid(duplicate == null ? "mem-" + UUID.randomUUID().toString().replace("-", "") : duplicate.getMemoryUuid())
                .userId(safeUserId)
                .sessionId(blankToNull(sessionId))
                .projectId(blankToNull(safeProjectId))
                .namespace(namespace)
                .memoryKey(key)
                .category(safeCategory)
                .scopeType(safeScopeType)
                .content(safeContent)
                .summary(limit(safe(summary), 500))
                .searchText(searchText)
                .metadataJson(toJson(metadata == null ? Map.of() : metadata))
                .embeddingVector(embedding)
                .importance(Math.max(1, Math.min(importance <= 0 ? 3 : importance, 5)))
                .enabled(true)
                .build();

        if (duplicate == null) {
            memoryMapper.upsertMemory(memory);
        } else {
            memoryMapper.updateByMemoryUuid(memory);
        }

        return searchByKey(safeUserId, key).stream().findFirst()
                .orElseGet(() -> toRecord(memory, 0, 0, 0));
    }

    public List<NovelAgentMemoryRecord> recallForPrompt(
            String sessionId,
            String projectId,
            String query,
            int limit) {
        if (!properties.isEnabled()) {
            return List.of();
        }
        return search(userId(), sessionId, projectId, query, "", limit <= 0 ? properties.getInjectLimit() : limit);
    }

    public List<NovelAgentMemoryRecord> search(
            String userId,
            String sessionId,
            String projectId,
            String query,
            String category,
            int limit) {
        String safeUserId = safe(userId).isBlank() ? userId() : safe(userId);
        String safeQuery = safe(query).trim();
        int safeLimit = Math.max(1, Math.min(limit <= 0 ? properties.getSearchLimit() : limit, 20));
        if (safeQuery.isBlank()) {
            return memoryMapper.listRecent(safeUserId, safeLimit).stream()
                    .map(item -> toRecord(item, 0, 0, 0))
                    .toList();
        }
        String embedding = embeddingVector(safeQuery);
        List<NovelAgentMemoryRecord> results = embedding.isBlank()
                ? textSearch(safeUserId, sessionId, projectId, safeQuery, category, safeLimit)
                : hybridSearch(safeUserId, sessionId, projectId, safeQuery, category, embedding, safeLimit);
        results.forEach(item -> memoryMapper.touch(item.getMemoryUuid()));
        return results;
    }

    public int forget(String memoryUuidOrKey) {
        String key = safe(memoryUuidOrKey).trim();
        if (key.isBlank()) {
            throw new IllegalArgumentException("memoryUuidOrKey must not be blank");
        }
        return memoryMapper.disable(userId(), key);
    }

    public String buildInjectedContext(List<NovelAgentMemoryRecord> memories) {
        if (memories == null || memories.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("闀挎湡璁板繂锛圥ostgreSQL锛屾寜鐩稿叧鎬ф绱紱鐢ㄤ簬淇濇寔璺ㄤ細璇濆亸濂姐€佹湳璇拰宸ヤ綔涔犳儻锛屼笉瑕侀€愬瓧澶嶈堪锛夛細\n");
        int index = 1;
        for (NovelAgentMemoryRecord memory : memories) {
            builder.append(index++)
                    .append(". [").append(memory.getCategory()).append('/')
                    .append(memory.getScopeType()).append("] ")
                    .append(memory.getContent());
            if (!safe(memory.getSummary()).isBlank()) {
                builder.append(" (").append(memory.getSummary()).append(')');
            }
            builder.append('\n');
        }
        return builder.toString();
    }

    public String userId() {
        return safe(properties.getUserId()).isBlank() ? "local-user" : properties.getUserId().trim();
    }

    private NovelAgentMemoryRecord findDuplicate(
            String userId,
            String sessionId,
            String projectId,
            String category,
            String searchText) {
        return search(userId, sessionId, projectId, searchText, category, 3).stream()
                .filter(item -> category.equals(item.getCategory()))
                .filter(item -> item.getScore() >= properties.getDuplicateScoreThreshold()
                        || normalizeText(item.getContent()).equals(normalizeText(searchText)))
                .findFirst()
                .orElse(null);
    }

    private List<NovelAgentMemoryRecord> searchByKey(String userId, String key) {
        return jdbcTemplate.query("""
                SELECT id, memory_uuid, user_id, session_id, project_id, namespace, memory_key,
                       category, scope_type, content, summary, metadata::text AS metadata_json,
                       importance, access_count, create_time, update_time,
                       0.0 AS vector_score, 0.0 AS text_score, 0.0 AS score
                FROM t_novel_agent_memory
                WHERE enabled = true AND user_id = ? AND memory_key = ?
                LIMIT 1
                """, rowMapper(), userId, key);
    }

    private List<NovelAgentMemoryRecord> hybridSearch(
            String userId,
            String sessionId,
            String projectId,
            String query,
            String category,
            String embedding,
            int limit) {
        return jdbcTemplate.query("""
                SELECT id, memory_uuid, user_id, session_id, project_id, namespace, memory_key,
                       category, scope_type, content, summary, metadata::text AS metadata_json,
                       importance, access_count, create_time, update_time,
                       CASE WHEN embedding IS NULL THEN 0.0 ELSE (1.0 - (embedding <=> CAST(? AS vector))) END AS vector_score,
                       similarity(search_text, ?) AS text_score,
                       (
                         CASE WHEN embedding IS NULL THEN 0.0 ELSE (1.0 - (embedding <=> CAST(? AS vector))) END * 0.70
                         + similarity(search_text, ?) * 0.28
                         + importance * 0.004
                       ) AS score
                FROM t_novel_agent_memory
                WHERE enabled = true
                  AND user_id = ?
                  AND (? = '' OR category = ?)
                  AND (scope_type IN ('global', 'user') OR session_id = ? OR (? <> '' AND project_id = ?))
                  AND (search_text % ? OR search_text ILIKE '%' || ? || '%' OR category = ?)
                ORDER BY score DESC, update_time DESC
                LIMIT ?
                """, rowMapper(),
                embedding, query, embedding, query,
                userId,
                safe(category), safe(category),
                safe(sessionId), safe(projectId), safe(projectId),
                query, query, query,
                limit);
    }

    private List<NovelAgentMemoryRecord> textSearch(
            String userId,
            String sessionId,
            String projectId,
            String query,
            String category,
            int limit) {
        return jdbcTemplate.query("""
                SELECT id, memory_uuid, user_id, session_id, project_id, namespace, memory_key,
                       category, scope_type, content, summary, metadata::text AS metadata_json,
                       importance, access_count, create_time, update_time,
                       0.0 AS vector_score,
                       similarity(search_text, ?) AS text_score,
                       (similarity(search_text, ?) * 0.96 + importance * 0.008) AS score
                FROM t_novel_agent_memory
                WHERE enabled = true
                  AND user_id = ?
                  AND (? = '' OR category = ?)
                  AND (scope_type IN ('global', 'user') OR session_id = ? OR (? <> '' AND project_id = ?))
                  AND (search_text % ? OR search_text ILIKE '%' || ? || '%' OR category = ?)
                ORDER BY score DESC, update_time DESC
                LIMIT ?
                """, rowMapper(),
                query, query,
                userId,
                safe(category), safe(category),
                safe(sessionId), safe(projectId), safe(projectId),
                query, query, query,
                limit);
    }

    private RowMapper<NovelAgentMemoryRecord> rowMapper() {
        return (rs, rowNum) -> NovelAgentMemoryRecord.builder()
                .id(rs.getLong("id"))
                .memoryUuid(rs.getString("memory_uuid"))
                .userId(rs.getString("user_id"))
                .sessionId(rs.getString("session_id"))
                .projectId(rs.getString("project_id"))
                .namespace(rs.getString("namespace"))
                .memoryKey(rs.getString("memory_key"))
                .category(rs.getString("category"))
                .scopeType(rs.getString("scope_type"))
                .content(rs.getString("content"))
                .summary(rs.getString("summary"))
                .metadata(parseMetadata(rs.getString("metadata_json")))
                .importance(rs.getInt("importance"))
                .accessCount(rs.getInt("access_count"))
                .vectorScore(getDouble(rs, "vector_score"))
                .textScore(getDouble(rs, "text_score"))
                .score(getDouble(rs, "score"))
                .createTime(toLocalDateTime(rs, "create_time"))
                .updateTime(toLocalDateTime(rs, "update_time"))
                .build();
    }

    private NovelAgentMemoryRecord toRecord(NovelAgentMemoryDO memory, double vectorScore, double textScore, double score) {
        return NovelAgentMemoryRecord.builder()
                .id(memory.getId())
                .memoryUuid(memory.getMemoryUuid())
                .userId(memory.getUserId())
                .sessionId(memory.getSessionId())
                .projectId(memory.getProjectId())
                .namespace(memory.getNamespace())
                .memoryKey(memory.getMemoryKey())
                .category(memory.getCategory())
                .scopeType(memory.getScopeType())
                .content(memory.getContent())
                .summary(memory.getSummary())
                .metadata(parseMetadata(memory.getMetadataJson()))
                .importance(memory.getImportance() == null ? 3 : memory.getImportance())
                .accessCount(memory.getAccessCount() == null ? 0 : memory.getAccessCount())
                .vectorScore(vectorScore)
                .textScore(textScore)
                .score(score)
                .createTime(memory.getCreateTime())
                .updateTime(memory.getUpdateTime())
                .build();
    }

    private String embeddingVector(String text) {
        try {
            EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
            if (embeddingModel == null) {
                return "";
            }
            float[] vector = embeddingModel.embed(limit(text, 6000));
            if (vector == null || vector.length == 0) {
                return "";
            }
            if (vector.length != properties.getEmbeddingDimensions()) {
                logger.warn("Embedding dimension mismatch: actual={}, expected={}", vector.length, properties.getEmbeddingDimensions());
                return "";
            }
            StringBuilder builder = new StringBuilder("[");
            for (int i = 0; i < vector.length; i++) {
                if (i > 0) {
                    builder.append(',');
                }
                builder.append(Float.toString(vector[i]));
            }
            return builder.append(']').toString();
        } catch (Exception e) {
            logger.warn("NovelFlow long-term memory embedding failed, fallback to text search: {}", e.getMessage());
            return "";
        }
    }

    private String namespace(String userId, String category, String scopeType, String projectId) {
        List<String> parts = new ArrayList<>();
        parts.add("novelflow");
        parts.add(userId);
        parts.add(category);
        parts.add(scopeType);
        if ("project".equals(scopeType) && !safe(projectId).isBlank()) {
            parts.add(projectId);
        }
        return String.join("/", parts);
    }

    private String buildSearchText(String category, String content, String summary, Map<String, Object> metadata) {
        StringBuilder builder = new StringBuilder();
        builder.append(category).append('\n').append(content);
        if (!safe(summary).isBlank()) {
            builder.append('\n').append(summary);
        }
        if (metadata != null && !metadata.isEmpty()) {
            builder.append('\n').append(metadata);
        }
        return limit(builder.toString(), 8000);
    }

    private String normalizeCategory(String category) {
        String safeCategory = safe(category).trim().toLowerCase(Locale.ROOT);
        if (safeCategory.isBlank()) {
            return "preference";
        }
        return safeCategory.replaceAll("[^a-z0-9_-]", "_");
    }

    private String normalizeScope(String scopeType) {
        String safeScope = safe(scopeType).trim().toLowerCase(Locale.ROOT);
        return switch (safeScope) {
            case "global", "session", "project" -> safeScope;
            default -> "user";
        };
    }

    private String newMemoryKey(String category) {
        return category + "-" + LocalDateTime.now().format(KEY_TIME_FORMAT) + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String sanitizeKey(String key) {
        String safeKey = safe(key).trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}_.:-]", "-")
                .replaceAll("-+", "-");
        return safeKey.isBlank() ? newMemoryKey("memory") : limit(safeKey, 120);
    }

    private String normalizeText(String text) {
        return safe(text).replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private String toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private Map<String, Object> parseMetadata(String json) {
        try {
            if (safe(json).isBlank()) {
                return Map.of();
            }
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private double getDouble(ResultSet rs, String column) {
        try {
            return rs.getDouble(column);
        } catch (Exception ignored) {
            return 0.0;
        }
    }

    private LocalDateTime toLocalDateTime(ResultSet rs, String column) {
        try {
            Timestamp timestamp = rs.getTimestamp(column);
            return timestamp == null ? null : timestamp.toLocalDateTime();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String blankToNull(String value) {
        return safe(value).isBlank() ? null : value.trim();
    }

    private String limit(String value, int max) {
        String safeValue = safe(value);
        return safeValue.length() <= max ? safeValue : safeValue.substring(0, max);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    public Map<String, Object> toPayload(NovelAgentMemoryRecord memory) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("memoryUuid", memory.getMemoryUuid());
        payload.put("key", memory.getMemoryKey());
        payload.put("category", memory.getCategory());
        payload.put("scopeType", memory.getScopeType());
        payload.put("projectId", safe(memory.getProjectId()));
        payload.put("content", memory.getContent());
        payload.put("summary", safe(memory.getSummary()));
        payload.put("importance", memory.getImportance());
        payload.put("score", roundScore(memory.getScore()));
        payload.put("vectorScore", roundScore(memory.getVectorScore()));
        payload.put("textScore", roundScore(memory.getTextScore()));
        payload.put("metadata", memory.getMetadata());
        payload.put("updatedAt", memory.getUpdateTime() == null ? "" : memory.getUpdateTime().toString());
        return payload;
    }

    private static double roundScore(double score) {
        return Math.round(score * SCORE_ROUNDING_SCALE) / SCORE_ROUNDING_SCALE;
    }
}

