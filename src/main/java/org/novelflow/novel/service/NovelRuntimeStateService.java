package org.novelflow.novel.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.novelflow.novel.dto.NovelRuntimeStateResponse;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class NovelRuntimeStateService {

    public static final String NODE_IMPORT_EPUB = "IMPORT_EPUB";
    public static final String NODE_PARSE_CHAPTERS = "PARSE_CHAPTERS";
    public static final String NODE_CLEAN_TEXT = "CLEAN_TEXT";
    public static final String NODE_BUILD_TERMS = "BUILD_TERMS";
    public static final String NODE_TRANSLATE_CHAPTER = "TRANSLATE_CHAPTER";
    public static final String NODE_TRANSLATE_CHUNK = "TRANSLATE_CHUNK";
    public static final String NODE_REVIEW_CHUNK = "REVIEW_CHUNK";
    public static final String NODE_FIX_CHUNK = "FIX_CHUNK";
    public static final String NODE_ACCEPT_CHUNK = "ACCEPT_CHUNK";
    public static final String NODE_REVIEW_CHAPTER = "REVIEW_CHAPTER";
    public static final String NODE_MERGE_CHAPTER = "MERGE_CHAPTER";
    public static final String NODE_EXPORT_BOOK = "EXPORT_BOOK";

    public static final String STATUS_INITIALIZED = "INITIALIZED";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_SKIPPED = "SKIPPED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String STATUS_NEED_REVIEW = "NEED_REVIEW";

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final List<String> NODE_ORDER = List.of(
            NODE_IMPORT_EPUB,
            NODE_PARSE_CHAPTERS,
            NODE_CLEAN_TEXT,
            NODE_BUILD_TERMS,
            NODE_TRANSLATE_CHAPTER,
            NODE_TRANSLATE_CHUNK,
            NODE_REVIEW_CHUNK,
            NODE_FIX_CHUNK,
            NODE_ACCEPT_CHUNK,
            NODE_REVIEW_CHAPTER,
            NODE_MERGE_CHAPTER,
            NODE_EXPORT_BOOK
    );

    private final ObjectMapper objectMapper;

    public NovelRuntimeStateService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public synchronized NovelRuntimeStateResponse initializeProject(Path projectDir, String projectId) {
        NovelRuntimeStateResponse state = defaultState(projectDir, projectId);
        write(projectDir, state);
        return state;
    }

    public synchronized NovelRuntimeStateResponse read(Path projectDir, String projectId) {
        Path stateFile = stateFile(projectDir);
        if (!Files.isRegularFile(stateFile)) {
            return initializeProject(projectDir, projectId);
        }
        try {
            NovelRuntimeStateResponse state = objectMapper.readValue(stateFile.toFile(), NovelRuntimeStateResponse.class);
            ensureDefaults(projectDir, projectId, state);
            write(projectDir, state);
            return state;
        } catch (IOException e) {
            throw new IllegalStateException("读取 NovelFlow runtime 状态失败: " + e.getMessage(), e);
        }
    }

    public synchronized void markRunning(Path projectDir, String projectId, String nodeName, String message, Map<String, Object> metadata) {
        markNode(projectDir, projectId, nodeName, STATUS_RUNNING, message, metadata);
    }

    public synchronized void markCompleted(Path projectDir, String projectId, String nodeName, String message, Map<String, Object> metadata) {
        markNode(projectDir, projectId, nodeName, STATUS_COMPLETED, message, metadata);
    }

    public synchronized void markSkipped(Path projectDir, String projectId, String nodeName, String message, Map<String, Object> metadata) {
        markNode(projectDir, projectId, nodeName, STATUS_SKIPPED, message, metadata);
    }

    public synchronized void markFailed(Path projectDir, String projectId, String nodeName, String message, Map<String, Object> metadata) {
        markNode(projectDir, projectId, nodeName, STATUS_FAILED, message, metadata);
    }

    public synchronized void markCancelled(Path projectDir, String projectId, String nodeName, String message, Map<String, Object> metadata) {
        markNode(projectDir, projectId, nodeName, STATUS_CANCELLED, message, metadata);
    }

    public synchronized void markNeedReview(Path projectDir, String projectId, String nodeName, String message, Map<String, Object> metadata) {
        markNode(projectDir, projectId, nodeName, STATUS_NEED_REVIEW, message, metadata);
    }

    public Path stateFile(Path projectDir) {
        return projectDir.resolve("config").resolve("runtime-state.json").normalize();
    }

    private void markNode(Path projectDir, String projectId, String nodeName, String status, String message, Map<String, Object> metadata) {
        NovelRuntimeStateResponse state = read(projectDir, projectId);
        String now = now();
        NovelRuntimeStateResponse.RuntimeNodeState node = state.getNodes().computeIfAbsent(nodeName, ignored -> newNode(nodeName));
        if (node.getStartedAt() == null && STATUS_RUNNING.equals(status)) {
            node.setStartedAt(now);
        }
        if (isTerminal(status)) {
            if (node.getStartedAt() == null) {
                node.setStartedAt(now);
            }
            node.setCompletedAt(now);
        }
        node.setStatus(status);
        node.setMessage(message);
        node.setUpdatedAt(now);
        node.setMetadata(metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata));

        state.setCurrentNode(nodeName);
        state.setUpdatedAt(now);
        state.setStatus(overallStatus(state, nodeName, status));
        if (metadata != null) {
            state.getMetadata().putAll(metadata);
        }
        write(projectDir, state);
    }

    private NovelRuntimeStateResponse defaultState(Path projectDir, String projectId) {
        NovelRuntimeStateResponse state = new NovelRuntimeStateResponse();
        String now = now();
        state.setProjectId(projectId);
        state.setStatus(STATUS_INITIALIZED);
        state.setCurrentNode(NODE_IMPORT_EPUB);
        state.setCreatedAt(now);
        state.setUpdatedAt(now);
        state.setStateFilePath(stateFile(projectDir).toAbsolutePath().normalize().toString());
        Map<String, NovelRuntimeStateResponse.RuntimeNodeState> nodes = new LinkedHashMap<>();
        for (String nodeName : NODE_ORDER) {
            nodes.put(nodeName, newNode(nodeName));
        }
        state.setNodes(nodes);
        state.setMetadata(new LinkedHashMap<>());
        return state;
    }

    private void ensureDefaults(Path projectDir, String projectId, NovelRuntimeStateResponse state) {
        if (state.getProjectId() == null || state.getProjectId().isBlank()) {
            state.setProjectId(projectId);
        }
        if (state.getStatus() == null || state.getStatus().isBlank()) {
            state.setStatus(STATUS_INITIALIZED);
        }
        if (state.getCurrentNode() == null || state.getCurrentNode().isBlank()) {
            state.setCurrentNode(NODE_IMPORT_EPUB);
        }
        if (state.getCreatedAt() == null || state.getCreatedAt().isBlank()) {
            state.setCreatedAt(now());
        }
        state.setUpdatedAt(now());
        state.setStateFilePath(stateFile(projectDir).toAbsolutePath().normalize().toString());
        if (state.getNodes() == null) {
            state.setNodes(new LinkedHashMap<>());
        }
        for (String nodeName : NODE_ORDER) {
            state.getNodes().computeIfAbsent(nodeName, ignored -> newNode(nodeName));
        }
        if (state.getMetadata() == null) {
            state.setMetadata(new LinkedHashMap<>());
        }
    }

    private NovelRuntimeStateResponse.RuntimeNodeState newNode(String nodeName) {
        NovelRuntimeStateResponse.RuntimeNodeState node = new NovelRuntimeStateResponse.RuntimeNodeState();
        node.setNodeName(nodeName);
        node.setStatus(STATUS_PENDING);
        node.setMessage("");
        node.setMetadata(new LinkedHashMap<>());
        return node;
    }

    private String overallStatus(NovelRuntimeStateResponse state, String nodeName, String nodeStatus) {
        if (STATUS_FAILED.equals(nodeStatus)) {
            return STATUS_FAILED;
        }
        if (STATUS_CANCELLED.equals(nodeStatus)) {
            return STATUS_CANCELLED;
        }
        if (STATUS_NEED_REVIEW.equals(nodeStatus)) {
            return STATUS_NEED_REVIEW;
        }
        boolean hasNeedReviewNode = state.getNodes().values().stream()
                .anyMatch(node -> STATUS_NEED_REVIEW.equals(node.getStatus()));
        if (hasNeedReviewNode) {
            return STATUS_NEED_REVIEW;
        }
        if (NODE_EXPORT_BOOK.equals(nodeName) && STATUS_COMPLETED.equals(nodeStatus)) {
            return STATUS_COMPLETED;
        }
        if (STATUS_RUNNING.equals(nodeStatus)) {
            return STATUS_RUNNING;
        }
        boolean hasRunningNode = state.getNodes().values().stream()
                .anyMatch(node -> STATUS_RUNNING.equals(node.getStatus()));
        if (!hasRunningNode && STATUS_RUNNING.equals(state.getStatus())) {
            return STATUS_PENDING;
        }
        return state.getStatus();
    }

    private boolean isTerminal(String status) {
        return STATUS_COMPLETED.equals(status)
                || STATUS_FAILED.equals(status)
                || STATUS_SKIPPED.equals(status)
                || STATUS_CANCELLED.equals(status)
                || STATUS_NEED_REVIEW.equals(status);
    }

    private void write(Path projectDir, NovelRuntimeStateResponse state) {
        try {
            Path stateFile = stateFile(projectDir);
            Files.createDirectories(stateFile.getParent());
            state.setStateFilePath(stateFile.toAbsolutePath().normalize().toString());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(stateFile.toFile(), state);
        } catch (IOException e) {
            throw new IllegalStateException("写入 NovelFlow runtime 状态失败: " + e.getMessage(), e);
        }
    }

    private String now() {
        return LocalDateTime.now().format(TIME_FORMATTER);
    }
}

