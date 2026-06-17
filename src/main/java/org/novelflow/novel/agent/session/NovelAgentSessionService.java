package org.novelflow.novel.agent.session;

import lombok.Getter;
import lombok.Setter;
import org.novelflow.novel.dto.TranslationJobResponse;
import org.novelflow.novel.dto.TranslationReadResponse;
import org.novelflow.novel.service.NovelProjectService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class NovelAgentSessionService {

    private static final ThreadLocal<String> CURRENT_SESSION_ID = new ThreadLocal<>();

    private final NovelAgentPersistenceService persistenceService;
    private final NovelProjectService novelProjectService;
    private final Map<String, NovelAgentSessionState> sessions = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> activeSessions = new ConcurrentHashMap<>();

    public NovelAgentSessionService(NovelAgentPersistenceService persistenceService,
            NovelProjectService novelProjectService) {
        this.persistenceService = persistenceService;
        this.novelProjectService = novelProjectService;
    }

    public void bind(String sessionId) {
        String normalized = normalizeSessionId(sessionId);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("NovelFlow Agent sessionId must not be blank");
        }
        CURRENT_SESSION_ID.set(normalized);
        activeSessions.computeIfAbsent(normalized, ignored -> new AtomicInteger()).incrementAndGet();
    }

    public void clear() {
        String sessionId = CURRENT_SESSION_ID.get();
        CURRENT_SESSION_ID.remove();
        if (sessionId != null && !sessionId.isBlank()) {
            activeSessions.computeIfPresent(sessionId, (ignored, counter) ->
                    counter.decrementAndGet() <= 0 ? null : counter);
        }
    }

    public NovelAgentSessionState current() {
        String sessionId = CURRENT_SESSION_ID.get();
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = singleActiveSessionId();
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalStateException("当前线程没有绑定 NovelFlow Agent 会话，无法读写会话上下文");
        }
        return state(sessionId);
    }

    public NovelAgentSessionState state(String sessionId) {
        String normalized = normalizeSessionId(sessionId);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("NovelFlow Agent sessionId must not be blank");
        }
        return sessions.computeIfAbsent(normalized, id -> {
            NovelAgentSessionState state = new NovelAgentSessionState(id);
            applyContext(state, persistenceService.readNovelContext(id));
            return state;
        });
    }

    public void clearSession(String sessionId) {
        sessions.remove(normalizeSessionId(sessionId));
    }

    public void mergeFrontendContext(String sessionId, Map<String, Object> context) {
        if (context == null || context.isEmpty()) {
            return;
        }
        NovelAgentSessionState state = state(sessionId);
        applyContext(state, context);
        persist(state);
    }

    public void persistSession(String sessionId) {
        persist(state(sessionId));
    }

    private void applyContext(NovelAgentSessionState state, Map<String, Object> context) {
        if (state == null || context == null || context.isEmpty()) {
            return;
        }
        String projectId = stringValue(context.get("currentNovelProjectId"));
        if (!projectId.isBlank()) {
            if (!novelProjectService.projectExists(projectId)) {
                resetProjectContext(state);
                return;
            }
            state.setCurrentProjectId(projectId);
        }
        String sourceFileName = stringValue(context.get("currentNovelSourceFileName"));
        if (!sourceFileName.isBlank()) {
            state.setCurrentSourceFileName(sourceFileName);
        }
        Object translation = context.get("currentNovelTranslation");
        if (translation instanceof Map<?, ?> map) {
            int chapterIndex = intValue(map.get("chapterIndex"), 0);
            if (chapterIndex > 0) {
                state.setCurrentChapterIndex(chapterIndex);
            }
            state.setNextOffset(intValue(map.get("nextOffset"), state.getNextOffset()));
            state.setHasMore(booleanValue(map.get("hasMore"), state.isHasMore()));
            state.setLastReadTitle(stringValue(map.get("title")));
            state.setLastReadTotalChars(intValue(map.get("totalChars"), state.getLastReadTotalChars()));
        }
        Object excerpt = context.get("currentNovelExcerpt");
        if (excerpt instanceof Map<?, ?> map) {
            int chapterIndex = intValue(map.get("chapterIndex"), 0);
            if (chapterIndex > 0) {
                state.setCurrentExcerptChapterIndex(chapterIndex);
            }
            state.setCurrentExcerptStartSourceOffset(intValue(map.get("startSourceOffset"), state.getCurrentExcerptStartSourceOffset()));
            state.setCurrentExcerptNextSourceOffset(intValue(map.get("nextSourceOffset"), state.getCurrentExcerptNextSourceOffset()));
            state.setCurrentExcerptSourceCharLimit(intValue(map.get("sourceCharLimit"), state.getCurrentExcerptSourceCharLimit()));
            state.setCurrentExcerptHasMoreSource(booleanValue(map.get("hasMoreSource"), state.isCurrentExcerptHasMoreSource()));
            state.setCurrentExcerptTitle(stringValue(map.get("title")));
            state.setCurrentExcerptOutputPath(stringValue(map.get("outputPath")));
            state.setCurrentExcerptTotalSourceChars(intValue(map.get("totalSourceChars"), state.getCurrentExcerptTotalSourceChars()));
        }
        String lastJobId = stringValue(context.get("lastTranslationJobId"));
        if (!lastJobId.isBlank()) {
            state.setLastJobId(lastJobId);
        }
        String lastJobStatus = stringValue(context.get("lastJobStatus"));
        if (!lastJobStatus.isBlank()) {
            state.setLastJobStatus(lastJobStatus);
        }
    }

    private void resetProjectContext(NovelAgentSessionState state) {
        state.setCurrentProjectId("");
        state.setCurrentSourceFileName("");
        state.setCurrentChapterIndex(0);
        state.setNextOffset(0);
        state.setHasMore(false);
        state.setLastJobId("");
        state.setLastJobStatus("");
        state.setLastReadTitle("");
        state.setLastReadTotalChars(0);
        state.setCurrentExcerptChapterIndex(0);
        state.setCurrentExcerptStartSourceOffset(0);
        state.setCurrentExcerptNextSourceOffset(0);
        state.setCurrentExcerptTotalSourceChars(0);
        state.setCurrentExcerptSourceCharLimit(3000);
        state.setCurrentExcerptHasMoreSource(false);
        state.setCurrentExcerptTitle("");
        state.setCurrentExcerptOutputPath("");
    }

    public void recordProject(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return;
        }
        NovelAgentSessionState state = current();
        state.setCurrentProjectId(projectId);
        persist(state);
    }

    public void recordSource(String projectId, String sourceFileName) {
        NovelAgentSessionState state = current();
        if (projectId != null && !projectId.isBlank()) {
            state.setCurrentProjectId(projectId);
        }
        if (sourceFileName != null && !sourceFileName.isBlank()) {
            state.setCurrentSourceFileName(sourceFileName);
        }
        persist(state);
    }

    public void recordTranslationJob(TranslationJobResponse job) {
        if (job == null) {
            return;
        }
        NovelAgentSessionState state = current();
        state.setCurrentProjectId(job.getProjectId());
        state.setCurrentChapterIndex(job.getChapterIndex());
        state.setLastJobId(job.getJobId());
        state.setLastJobStatus(job.getStatus());
        persist(state);
    }

    public void recordTranslationRead(TranslationReadResponse read) {
        if (read == null) {
            return;
        }
        NovelAgentSessionState state = current();
        state.setCurrentProjectId(read.getProjectId());
        state.setCurrentChapterIndex(read.getChapterIndex());
        state.setNextOffset(read.getNextOffset());
        state.setHasMore(read.isHasMore());
        state.setLastReadTitle(read.getTitle());
        state.setLastReadTotalChars(read.getTotalChars());
        persist(state);
    }

    public void recordExcerptTranslation(
            String projectId,
            int chapterIndex,
            int startSourceOffset,
            int nextSourceOffset,
            int totalSourceChars,
            int sourceCharLimit,
            String title,
            String outputPath) {
        NovelAgentSessionState state = current();
        if (projectId != null && !projectId.isBlank()) {
            state.setCurrentProjectId(projectId);
        }
        state.setCurrentExcerptChapterIndex(Math.max(0, chapterIndex));
        state.setCurrentExcerptStartSourceOffset(Math.max(0, startSourceOffset));
        state.setCurrentExcerptNextSourceOffset(Math.max(0, nextSourceOffset));
        state.setCurrentExcerptTotalSourceChars(Math.max(0, totalSourceChars));
        state.setCurrentExcerptSourceCharLimit(Math.max(0, sourceCharLimit));
        state.setCurrentExcerptHasMoreSource(nextSourceOffset < totalSourceChars);
        state.setCurrentExcerptTitle(title == null ? "" : title);
        state.setCurrentExcerptOutputPath(outputPath == null ? "" : outputPath);
        persist(state);
    }

    public Map<String, Object> snapshot(String sessionId) {
        return state(sessionId).toMap();
    }

    private void persist(NovelAgentSessionState state) {
        if (state != null) {
            persistenceService.saveNovelContext(state.getSessionId(), state.toMap());
        }
    }

    private String normalizeSessionId(String sessionId) {
        return sessionId == null ? "" : sessionId.trim();
    }

    private String singleActiveSessionId() {
        activeSessions.entrySet().removeIf(entry -> entry.getValue().get() <= 0);
        return activeSessions.size() == 1
                ? activeSessions.keySet().iterator().next()
                : "";
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text && !text.isBlank()) {
            return Boolean.parseBoolean(text.trim());
        }
        return fallback;
    }

    @Getter
    @Setter
    public static class NovelAgentSessionState {
        private final String sessionId;
        private String currentProjectId = "";
        private String currentSourceFileName = "";
        private int currentChapterIndex = 0;
        private int nextOffset = 0;
        private boolean hasMore = false;
        private String lastJobId = "";
        private String lastJobStatus = "";
        private String lastReadTitle = "";
        private int lastReadTotalChars = 0;
        private int currentExcerptChapterIndex = 0;
        private int currentExcerptStartSourceOffset = 0;
        private int currentExcerptNextSourceOffset = 0;
        private int currentExcerptTotalSourceChars = 0;
        private int currentExcerptSourceCharLimit = 3000;
        private boolean currentExcerptHasMoreSource = false;
        private String currentExcerptTitle = "";
        private String currentExcerptOutputPath = "";

        public NovelAgentSessionState(String sessionId) {
            this.sessionId = sessionId;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("sessionId", sessionId);
            result.put("currentNovelProjectId", safe(currentProjectId));
            result.put("currentNovelSourceFileName", safe(currentSourceFileName));
            result.put("lastTranslationJobId", safe(lastJobId));
            result.put("lastJobStatus", safe(lastJobStatus));
            result.put("currentNovelTranslation", Map.of(
                    "chapterIndex", currentChapterIndex,
                    "nextOffset", nextOffset,
                    "hasMore", hasMore,
                    "title", safe(lastReadTitle),
                    "totalChars", lastReadTotalChars
            ));
            result.put("currentNovelExcerpt", Map.of(
                    "chapterIndex", currentExcerptChapterIndex,
                    "startSourceOffset", currentExcerptStartSourceOffset,
                    "nextSourceOffset", currentExcerptNextSourceOffset,
                    "totalSourceChars", currentExcerptTotalSourceChars,
                    "sourceCharLimit", currentExcerptSourceCharLimit,
                    "hasMoreSource", currentExcerptHasMoreSource,
                    "title", safe(currentExcerptTitle),
                    "outputPath", safe(currentExcerptOutputPath)
            ));
            return result;
        }

        private String safe(String value) {
            return value == null ? "" : value;
        }
    }
}

