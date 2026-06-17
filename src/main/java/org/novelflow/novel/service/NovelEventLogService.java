package org.novelflow.novel.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class NovelEventLogService {

    private final ObjectMapper objectMapper;

    public NovelEventLogService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void append(Path projectDir, String eventType, String message, Map<String, Object> metadata) {
        try {
            Path logDir = projectDir.resolve("logs");
            Files.createDirectories(logDir);
            Path logFile = logDir.resolve("events.jsonl");

            Map<String, Object> event = new LinkedHashMap<>();
            event.put("eventId", UUID.randomUUID().toString());
            event.put("eventType", eventType);
            event.put("message", message);
            event.put("createdAt", Instant.now().toString());
            event.put("metadata", metadata == null ? Map.of() : metadata);

            String line = objectMapper.writeValueAsString(event) + System.lineSeparator();
            Files.writeString(logFile, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("写入小说工作流事件日志失败: " + e.getMessage(), e);
        }
    }
}

