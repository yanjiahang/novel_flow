package org.novelflow.novel.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.novelflow.novel.dto.ChapterInfo;
import org.novelflow.novel.dto.SplitNovelResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
public class ChapterSplitService {

    private static final Logger logger = LoggerFactory.getLogger(ChapterSplitService.class);

    private static final List<Pattern> CHAPTER_PATTERNS = List.of(
            Pattern.compile("^第[0-9０-９一二三四五六七八九十百千零〇]+[卷巻部篇章話话回節节集].*$"),
            Pattern.compile("^(卷|巻|部|篇|章|話|话|回|節|节)[0-9０-９一二三四五六七八九十百千零〇]+.*$"),
            Pattern.compile("^Chapter\\s*[0-9]+.*$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^(序章|終章|终章|楔子|引子|前言|尾声|後記|后记|あとがき|プロローグ|エピローグ|幕間|番外|番外篇|外伝).*$")
    );

    private final NovelProjectService novelProjectService;
    private final NovelEventLogService eventLogService;
    private final NovelRuntimeStateService runtimeStateService;
    private final ObjectMapper objectMapper;
    private final EpubTextExtractor epubTextExtractor;

    public ChapterSplitService(
            NovelProjectService novelProjectService,
            NovelEventLogService eventLogService,
            NovelRuntimeStateService runtimeStateService,
            ObjectMapper objectMapper,
            EpubTextExtractor epubTextExtractor) {
        this.novelProjectService = novelProjectService;
        this.eventLogService = eventLogService;
        this.runtimeStateService = runtimeStateService;
        this.objectMapper = objectMapper;
        this.epubTextExtractor = epubTextExtractor;
    }

    public SplitNovelResponse split(String projectId, String sourceFileName) {
        Path projectDir = novelProjectService.resolveProjectDir(projectId);
        Path sourceFile = resolveSourceFile(projectDir, sourceFileName);
        runtimeStateService.markRunning(projectDir, projectId, NovelRuntimeStateService.NODE_PARSE_CHAPTERS,
                "正在解析小说章节", Map.of("sourceFile", sourceFile.getFileName().toString()));

        try {
            SplitNovelResponse response;
            if (isEpub(sourceFile)) {
                response = splitEpub(projectId, projectDir, sourceFile);
            } else {
                String content = readText(sourceFile);
                List<ChapterDraft> drafts = scanChapters(content, sourceFile);
                List<ChapterInfo> chapters = writeChapters(projectDir, drafts);
                writeManifest(projectDir, sourceFile.getFileName().toString(), chapters);
                writeProgress(projectDir, chapters);

                eventLogService.append(projectDir, "CHAPTERS_SPLIT", "小说原文已拆分为章节",
                        Map.of("sourceFile", sourceFile.getFileName().toString(), "totalChapters", chapters.size()));

                response = new SplitNovelResponse();
                response.setProjectId(projectId);
                response.setSourceFileName(sourceFile.getFileName().toString());
                response.setTotalChapters(chapters.size());
                response.setChapters(chapters);
            }
            runtimeStateService.markCompleted(projectDir, projectId, NovelRuntimeStateService.NODE_PARSE_CHAPTERS,
                    "章节解析完成", Map.of("totalChapters", response.getTotalChapters()));
            runtimeStateService.markCompleted(projectDir, projectId, NovelRuntimeStateService.NODE_CLEAN_TEXT,
                    "正文清洗完成", Map.of("sourceFile", sourceFile.getFileName().toString()));
            return response;
        } catch (RuntimeException e) {
            runtimeStateService.markFailed(projectDir, projectId, NovelRuntimeStateService.NODE_PARSE_CHAPTERS,
                    "章节解析失败", Map.of("error", e.getMessage() == null ? "" : e.getMessage()));
            throw e;
        }
    }

    private SplitNovelResponse splitEpub(String projectId, Path projectDir, Path sourceFile) {
        List<ChapterDraft> drafts = new ArrayList<>();
        for (EpubTextExtractor.EpubChapter chapter : epubTextExtractor.extractChapters(sourceFile)) {
            ChapterDraft draft = new ChapterDraft();
            draft.title = chapter.title();
            draft.startLine = 1;
            draft.lines.add(chapter.title());
            draft.lines.add("");
            draft.lines.addAll(List.of(chapter.text().split("\\R", -1)));
            draft.endLine = draft.lines.size();
            drafts.add(draft);
        }
        if (drafts.isEmpty()) {
            throw new IllegalArgumentException("EPUB 中没有可拆分的正文目录项");
        }

        List<ChapterInfo> chapters = writeChapters(projectDir, drafts);
        writeManifest(projectDir, sourceFile.getFileName().toString(), chapters);
        writeProgress(projectDir, chapters);

        eventLogService.append(projectDir, "CHAPTERS_SPLIT", "EPUB 小说已按目录拆分为章节",
                Map.of("sourceFile", sourceFile.getFileName().toString(), "totalChapters", chapters.size()));

        SplitNovelResponse response = new SplitNovelResponse();
        response.setProjectId(projectId);
        response.setSourceFileName(sourceFile.getFileName().toString());
        response.setTotalChapters(chapters.size());
        response.setChapters(chapters);
        return response;
    }

    private Path resolveSourceFile(Path projectDir, String sourceFileName) {
        if (sourceFileName == null || sourceFileName.isBlank()) {
            return novelProjectService.findDefaultRawFile(projectDir);
        }
        Path rawDir = projectDir.resolve("raw").toAbsolutePath().normalize();
        String safeFileName = Path.of(sourceFileName).getFileName().toString();
        Path sourceFile = rawDir.resolve(safeFileName).normalize();
        if (!sourceFile.startsWith(rawDir)) {
            throw new IllegalArgumentException("非法原文文件名");
        }
        if (Files.isRegularFile(sourceFile)) {
            return sourceFile;
        }

        Path normalizedMatch = findRawFileByNormalizedName(rawDir, safeFileName);
        if (normalizedMatch != null) {
            logger.info("小说原文文件名通过 Unicode 规范化匹配: requested={}, actual={}",
                    safeFileName, normalizedMatch.getFileName());
            return normalizedMatch;
        }

        List<Path> rawFiles = listRawFiles(rawDir);
        if (rawFiles.size() == 1) {
            logger.warn("小说原文文件名未精确匹配，但 raw 目录只有一个可用文件，自动使用该文件: requested={}, actual={}",
                    safeFileName, rawFiles.get(0).getFileName());
            return rawFiles.get(0);
        }
        throw new IllegalArgumentException("原文文件不存在: " + sourceFileName
                + "，raw 目录可用文件: " + rawFiles.stream().map(path -> path.getFileName().toString()).toList());
    }

    private Path findRawFileByNormalizedName(Path rawDir, String sourceFileName) {
        String expectedNfc = Normalizer.normalize(sourceFileName, Normalizer.Form.NFC);
        String expectedNfd = Normalizer.normalize(sourceFileName, Normalizer.Form.NFD);
        for (Path file : listRawFiles(rawDir)) {
            String actual = file.getFileName().toString();
            if (expectedNfc.equals(Normalizer.normalize(actual, Normalizer.Form.NFC))
                    || expectedNfd.equals(Normalizer.normalize(actual, Normalizer.Form.NFD))) {
                return file;
            }
        }
        return null;
    }

    private List<Path> listRawFiles(Path rawDir) {
        if (!Files.isDirectory(rawDir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(rawDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> isSupportedRawFile(path.getFileName().toString()))
                    .sorted((left, right) -> left.getFileName().toString().compareTo(right.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("读取 raw 目录失败: " + e.getMessage(), e);
        }
    }

    private boolean isSupportedRawFile(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".epub");
    }

    private String readText(Path sourceFile) {
        if (isEpub(sourceFile)) {
            return epubTextExtractor.extract(sourceFile);
        }
        try {
            byte[] bytes = Files.readAllBytes(sourceFile);
            try {
                return decode(bytes, StandardCharsets.UTF_8);
            } catch (CharacterCodingException ignored) {
                return decode(bytes, Charset.forName("GB18030"));
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取小说原文失败: " + e.getMessage(), e);
        }
    }

    private boolean isEpub(Path sourceFile) {
        return sourceFile.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".epub");
    }

    private String decode(byte[] bytes, Charset charset) throws CharacterCodingException {
        return charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
                .replace("\uFEFF", "");
    }

    private List<ChapterDraft> scanChapters(String content, Path sourceFile) {
        String[] lines = content.split("\\R", -1);
        List<ChapterDraft> drafts = new ArrayList<>();
        ChapterDraft current = null;
        List<String> prefaceLines = new ArrayList<>();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            boolean heading = isChapterHeading(trimmed);

            if (heading) {
                if (current != null) {
                    current.endLine = i;
                    drafts.add(current);
                } else if (hasText(prefaceLines)) {
                    ChapterDraft preface = new ChapterDraft();
                    preface.title = "开篇";
                    preface.startLine = 1;
                    preface.endLine = i;
                    preface.lines.addAll(prefaceLines);
                    drafts.add(preface);
                    prefaceLines.clear();
                }

                current = new ChapterDraft();
                current.title = trimmed;
                current.startLine = i + 1;
                current.lines.add(line);
            } else if (current != null) {
                current.lines.add(line);
            } else {
                prefaceLines.add(line);
            }
        }

        if (current != null) {
            current.endLine = lines.length;
            drafts.add(current);
        } else if (hasText(prefaceLines)) {
            ChapterDraft single = new ChapterDraft();
            single.title = stripExtension(sourceFile.getFileName().toString());
            single.startLine = 1;
            single.endLine = lines.length;
            single.lines.addAll(prefaceLines);
            drafts.add(single);
        }

        if (drafts.isEmpty()) {
            throw new IllegalArgumentException("原文为空，无法拆分章节");
        }
        return drafts;
    }

    private boolean isChapterHeading(String line) {
        if (line.isBlank() || line.length() > 80) {
            return false;
        }
        return CHAPTER_PATTERNS.stream().anyMatch(pattern -> pattern.matcher(line).matches());
    }

    private List<ChapterInfo> writeChapters(Path projectDir, List<ChapterDraft> drafts) {
        Path sourceDir = projectDir.resolve("source");
        try {
            Files.createDirectories(sourceDir);
            deleteGeneratedChapters(sourceDir);
        } catch (IOException e) {
            throw new IllegalStateException("准备章节目录失败: " + e.getMessage(), e);
        }

        List<ChapterInfo> chapters = new ArrayList<>();
        for (int i = 0; i < drafts.size(); i++) {
            ChapterDraft draft = drafts.get(i);
            int index = i + 1;
            String fileName = "%03d_%s.md".formatted(index, sanitizeTitle(draft.title));
            Path target = sourceDir.resolve(fileName);
            String chapterText = String.join(System.lineSeparator(), draft.lines).stripTrailing() + System.lineSeparator();

            try {
                Files.writeString(target, chapterText, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("写入章节文件失败: " + target.getFileName(), e);
            }

            ChapterInfo info = new ChapterInfo();
            info.setIndex(index);
            info.setTitle(draft.title);
            info.setFileName(fileName);
            info.setStartLine(draft.startLine);
            info.setEndLine(draft.endLine);
            info.setCharCount(chapterText.length());
            chapters.add(info);
        }
        return chapters;
    }

    private void deleteGeneratedChapters(Path sourceDir) throws IOException {
        try (Stream<Path> stream = Files.list(sourceDir)) {
            for (Path file : stream.filter(Files::isRegularFile).filter(path -> path.getFileName().toString().endsWith(".md")).toList()) {
                Files.delete(file);
            }
        }
    }

    private void writeManifest(Path projectDir, String sourceFileName, List<ChapterInfo> chapters) {
        Path manifest = projectDir.resolve("config").resolve("chapter_manifest.json");
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(manifest.toFile(), Map.of(
                    "sourceFileName", sourceFileName,
                    "generatedAt", LocalDateTime.now().toString(),
                    "totalChapters", chapters.size(),
                    "chapters", chapters
            ));
        } catch (IOException e) {
            throw new IllegalStateException("写入章节清单失败: " + e.getMessage(), e);
        }
    }

    private void writeProgress(Path projectDir, List<ChapterInfo> chapters) {
        StringBuilder builder = new StringBuilder();
        builder.append("# NovelFlow 进度追踪\n");
        builder.append("status: \"CHAPTER_SPLIT\"\n");
        builder.append("totalChapters: ").append(chapters.size()).append("\n");
        builder.append("lastCompletedChapter: 0\n");
        builder.append("nextChapter: 1\n");
        builder.append("updatedAt: \"").append(LocalDateTime.now()).append("\"\n");
        builder.append("chapters:\n");
        for (ChapterInfo chapter : chapters) {
            builder.append("  - index: ").append(chapter.getIndex()).append("\n");
            builder.append("    title: \"").append(escapeYaml(chapter.getTitle())).append("\"\n");
            builder.append("    fileName: \"").append(escapeYaml(chapter.getFileName())).append("\"\n");
            builder.append("    status: \"PENDING\"\n");
        }

        try {
            Files.writeString(projectDir.resolve("config").resolve("progress.yaml"), builder.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("更新进度文件失败: " + e.getMessage(), e);
        }
    }

    private boolean hasText(List<String> lines) {
        return lines.stream().anyMatch(line -> !line.isBlank());
    }

    private String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private String sanitizeTitle(String title) {
        String value = title == null ? "chapter" : title.trim();
        value = value.replaceAll("[\\\\/:*?\"<>|]", "_")
                .replaceAll("\\s+", "_")
                .replaceAll("_+", "_");
        if (value.isBlank()) {
            return "chapter";
        }
        if (value.length() > 40) {
            value = value.substring(0, 40);
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private String escapeYaml(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static class ChapterDraft {
        private String title;
        private int startLine;
        private int endLine;
        private final List<String> lines = new ArrayList<>();
    }
}

