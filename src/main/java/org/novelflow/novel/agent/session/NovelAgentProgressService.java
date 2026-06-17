package org.novelflow.novel.agent.session;

import lombok.Getter;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class NovelAgentProgressService {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ThreadLocal<ProgressSink> CURRENT_SINK = new ThreadLocal<>();
    private final Map<String, ProgressSink> activeSinks = new ConcurrentHashMap<>();

    public void bind(ProgressSink sink) {
        CURRENT_SINK.set(sink);
    }

    public void bind(String runId, ProgressSink sink) {
        CURRENT_SINK.set(sink);
        if (runId != null && !runId.isBlank() && sink != null) {
            activeSinks.put(runId, sink);
        }
    }

    public void clear() {
        CURRENT_SINK.remove();
    }

    public void clear(String runId) {
        CURRENT_SINK.remove();
        if (runId != null && !runId.isBlank()) {
            activeSinks.remove(runId);
        }
    }

    public void emit(String phase, String message) {
        emit(phase, message, Map.of());
    }

    public void emit(String phase, String message, Map<String, Object> data) {
        ProgressSink sink = resolveSink();
        if (sink == null) {
            return;
        }
        sink.emit(new ProgressEvent(
                safe(phase),
                safe(message),
                copySafe(data),
                LocalDateTime.now().format(TIME_FORMATTER)
        ));
    }

    private ProgressSink resolveSink() {
        ProgressSink sink = CURRENT_SINK.get();
        if (sink != null) {
            return sink;
        }
        if (activeSinks.size() == 1) {
            return activeSinks.values().iterator().next();
        }
        return null;
    }

    private Map<String, Object> copySafe(Map<String, Object> data) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (data == null) {
            return result;
        }
        data.forEach((key, value) -> {
            if (key != null && value != null) {
                result.put(key, value);
            }
        });
        return result;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    @FunctionalInterface
    public interface ProgressSink {
        void emit(ProgressEvent event);
    }

    @Getter
    public static class ProgressEvent {
        private final String phase;
        private final String message;
        private final Map<String, Object> data;
        private final String timestamp;

        public ProgressEvent(String phase, String message, Map<String, Object> data, String timestamp) {
            this.phase = phase;
            this.message = message;
            this.data = data;
            this.timestamp = timestamp;
        }
    }
}

