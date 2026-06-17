package org.novelflow.novel.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class NovelExportService {

    private final NovelProjectService novelProjectService;
    private final NovelEventLogService eventLogService;
    private final ObjectMapper objectMapper;

    public NovelExportService(
            NovelProjectService novelProjectService,
            NovelEventLogService eventLogService,
            ObjectMapper objectMapper) {
        this.novelProjectService = novelProjectService;
        this.eventLogService = eventLogService;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> exportMarkdownAndText(String projectId) {
        Path projectDir = novelProjectService.resolveProjectDir(projectId);
        Path manifest = projectDir.resolve("config").resolve("chapter_manifest.json");
        if (!Files.isRegularFile(manifest)) {
            throw new IllegalArgumentException("章节清单不存在，请先拆分小说章节");
        }

        Path finalDir = projectDir.resolve("final");
        Path markdownPath = finalDir.resolve("translated-book.md");
        Path textPath = finalDir.resolve("translated-book.txt");
        StringBuilder markdown = new StringBuilder();
        StringBuilder text = new StringBuilder();
        int exportedChapters = 0;

        try {
            Files.createDirectories(finalDir);
            JsonNode chapters = objectMapper.readTree(manifest.toFile()).path("chapters");
            if (!chapters.isArray()) {
                throw new IllegalArgumentException("章节清单格式错误");
            }
            for (JsonNode chapter : chapters) {
                String title = chapter.path("title").asText("Untitled");
                String fileName = chapter.path("fileName").asText("");
                if (fileName.isBlank()) {
                    continue;
                }
                Path translatedChapter = resolveTranslatedChapter(projectDir, fileName);
                if (!Files.isRegularFile(translatedChapter)) {
                    continue;
                }
                String chapterText = Files.readString(translatedChapter, StandardCharsets.UTF_8).strip();
                if (chapterText.isBlank()) {
                    continue;
                }
                markdown.append("# ").append(title).append(System.lineSeparator()).append(System.lineSeparator())
                        .append(chapterText).append(System.lineSeparator()).append(System.lineSeparator());
                text.append(title).append(System.lineSeparator()).append(System.lineSeparator())
                        .append(chapterText).append(System.lineSeparator()).append(System.lineSeparator());
                exportedChapters++;
            }
            Files.writeString(markdownPath, markdown.toString().strip() + System.lineSeparator(), StandardCharsets.UTF_8);
            Files.writeString(textPath, text.toString().strip() + System.lineSeparator(), StandardCharsets.UTF_8);

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("projectId", projectId);
            metadata.put("exportedChapters", exportedChapters);
            metadata.put("markdownPath", markdownPath.toAbsolutePath().normalize().toString());
            metadata.put("textPath", textPath.toAbsolutePath().normalize().toString());
            metadata.put("exportedAt", LocalDateTime.now().toString());
            eventLogService.append(projectDir, "BOOK_EXPORTED", "整本译文已导出", metadata);
            return metadata;
        } catch (IOException e) {
            throw new IllegalStateException("导出译文失败: " + e.getMessage(), e);
        }
    }

    private Path resolveTranslatedChapter(Path projectDir, String fileName) {
        Path polished = projectDir.resolve("polished").resolve(fileName).normalize();
        if (Files.isRegularFile(polished)) {
            return polished;
        }
        return projectDir.resolve("output").resolve(fileName).normalize();
    }
}

