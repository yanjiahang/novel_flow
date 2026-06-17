package org.novelflow.novel.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.novelflow.config.NovelTranslationProperties;
import org.novelflow.novel.dto.TranslateChapterResponse;
import org.novelflow.novel.dto.TranslationProgressResponse;
import org.novelflow.novel.dto.TranslationReadResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.Serializable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

@Service
public class NovelTranslationService {

    private static final Logger logger = LoggerFactory.getLogger(NovelTranslationService.class);
    private static final int PREVIEW_CHAR_COUNT = 500;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final NovelProjectService novelProjectService;
    private final NovelEventLogService eventLogService;
    private final ObjectMapper objectMapper;
    private final NovelTranslationProperties translationProperties;
    private final OllamaNovelTranslationClient translationClient;
    private final NovelTranslationTextPostProcessor textPostProcessor;
    private final NovelTermMemoryService termMemoryService;

    public NovelTranslationService(
            NovelProjectService novelProjectService,
            NovelEventLogService eventLogService,
            ObjectMapper objectMapper,
            NovelTranslationProperties translationProperties,
            OllamaNovelTranslationClient translationClient,
            NovelTranslationTextPostProcessor textPostProcessor,
            NovelTermMemoryService termMemoryService) {
        this.novelProjectService = novelProjectService;
        this.eventLogService = eventLogService;
        this.objectMapper = objectMapper;
        this.translationProperties = translationProperties;
        this.translationClient = translationClient;
        this.textPostProcessor = textPostProcessor;
        this.termMemoryService = termMemoryService;
    }

    public TranslateChapterResponse translateChapter(String projectId, int chapterIndex) {
        return translateChapter(projectId, chapterIndex, () -> false);
    }

    public TranslateChapterResponse translateChapter(String projectId, int chapterIndex, BooleanSupplier cancelRequested) {
        if (chapterIndex <= 0) {
            throw new IllegalArgumentException("章节序号必须大于 0");
        }

        Path projectDir = novelProjectService.resolveProjectDir(projectId);
        ChapterRef chapter = findChapter(projectDir, chapterIndex);
        Path sourcePath = projectDir.resolve("source").resolve(chapter.fileName()).normalize();
        if (!Files.isRegularFile(sourcePath)) {
            throw new IllegalArgumentException("章节文件不存在: " + chapter.fileName());
        }

        try {
            String sourceText = Files.readString(sourcePath, StandardCharsets.UTF_8).strip();
            Path outputDir = projectDir.resolve("output");
            Files.createDirectories(outputDir);
            Path outputPath = outputDir.resolve(chapter.fileName()).normalize();
            Path chunkDir = outputDir.resolve("chunks").resolve(stripExtension(chapter.fileName())).normalize();
            String translatedText = translateText(projectId, chapterIndex, chapter, sourceText, outputPath, chunkDir, cancelRequested);

            eventLogService.append(projectDir, "CHAPTER_TRANSLATED", "章节已翻译",
                    Map.of(
                            "chapterIndex", chapterIndex,
                            "title", chapter.title(),
                            "sourceFileName", chapter.fileName(),
                            "outputFileName", outputPath.getFileName().toString(),
                            "chunkDirectory", chunkDir.toAbsolutePath().normalize().toString(),
                            "translationModel", translationClient.modelName(),
                            "translatedAt", LocalDateTime.now().toString()
                    ));

            TranslateChapterResponse response = new TranslateChapterResponse();
            response.setProjectId(projectId);
            response.setChapterIndex(chapterIndex);
            response.setTitle(chapter.title());
            response.setSourceFileName(chapter.fileName());
            response.setOutputFileName(outputPath.getFileName().toString());
            response.setOutputPath(outputPath.toAbsolutePath().normalize().toString());
            response.setSourceCharCount(sourceText.length());
            response.setTranslatedCharCount(translatedText.length());
            response.setPreview(buildPreview(translatedText));
            response.setPreviewCharCount(Math.min(translatedText.strip().length(), PREVIEW_CHAR_COUNT));
            response.setHasMore(translatedText.strip().length() > PREVIEW_CHAR_COUNT);
            return response;
        } catch (IOException e) {
            throw new IllegalStateException("翻译章节失败: " + e.getMessage(), e);
        }
    }

    public RuntimeChapterPlan prepareRuntimeChapter(String projectId, int chapterIndex) {
        if (chapterIndex <= 0) {
            throw new IllegalArgumentException("章节序号必须大于 0");
        }

        Path projectDir = novelProjectService.resolveProjectDir(projectId);
        ChapterRef chapter = findChapter(projectDir, chapterIndex);
        Path sourcePath = projectDir.resolve("source").resolve(chapter.fileName()).normalize();
        if (!Files.isRegularFile(sourcePath)) {
            throw new IllegalArgumentException("章节文件不存在: " + chapter.fileName());
        }

        try {
            String sourceText = Files.readString(sourcePath, StandardCharsets.UTF_8).strip();
            Path outputDir = projectDir.resolve("output");
            Files.createDirectories(outputDir);
            Path outputPath = outputDir.resolve(chapter.fileName()).normalize();
            Path chunkDir = outputDir.resolve("chunks").resolve(stripExtension(chapter.fileName())).normalize();
            Path reviewChunkDir = projectDir.resolve("review").resolve("chunks").resolve(stripExtension(chapter.fileName())).normalize();
            List<String> chunks = splitSourceText(sourceText, Math.max(500, translationProperties.getChunkSize()));

            prepareChunkOutput(outputPath, chunkDir);
            prepareReviewOutput(reviewChunkDir);
            writeTranslationProgress(projectId, chapterIndex, chapter, outputPath, chunkDir, sourceText.length(),
                    chunks.size(), 0, 0, "RUNNING", null, null);
            logger.info("开始翻译章节: title={}, model={}, sourceChars={}, chunkSize={}, chunks={}",
                    chapter.title(), translationClient.modelName(), sourceText.length(), translationProperties.getChunkSize(), chunks.size());

            return new RuntimeChapterPlan(
                    projectId,
                    chapterIndex,
                    chapter.title(),
                    chapter.fileName(),
                    sourceText.length(),
                    outputPath.toAbsolutePath().normalize().toString(),
                    chunkDir.toAbsolutePath().normalize().toString(),
                    chunks
            );
        } catch (IOException e) {
            throw new IllegalStateException("准备章节翻译失败: " + e.getMessage(), e);
        }
    }

    public RuntimeChapterExcerptPlan prepareRuntimeChapterExcerpt(
            String projectId,
            int chapterIndex,
            int startSourceOffset,
            int sourceCharLimit) {
        if (chapterIndex <= 0) {
            throw new IllegalArgumentException("章节序号必须大于 0");
        }

        Path projectDir = novelProjectService.resolveProjectDir(projectId);
        ChapterRef chapter = findChapter(projectDir, chapterIndex);
        Path sourcePath = projectDir.resolve("source").resolve(chapter.fileName()).normalize();
        if (!Files.isRegularFile(sourcePath)) {
            throw new IllegalArgumentException("章节文件不存在: " + chapter.fileName());
        }

        try {
            String fullSourceText = Files.readString(sourcePath, StandardCharsets.UTF_8).strip();
            if (fullSourceText.isBlank()) {
                throw new IllegalArgumentException("章节原文为空: " + chapter.fileName());
            }
            int safeStart = Math.max(0, Math.min(startSourceOffset, fullSourceText.length()));
            int safeLimit = Math.max(500, Math.min(sourceCharLimit <= 0 ? 3000 : sourceCharLimit, 20000));
            int preferredEnd = Math.min(fullSourceText.length(), safeStart + safeLimit);
            int safeEnd = preferredEnd;
            if (preferredEnd < fullSourceText.length() && preferredEnd - safeStart >= 500) {
                safeEnd = findChunkBoundary(fullSourceText, safeStart, preferredEnd, safeLimit);
            }
            if (safeEnd <= safeStart) {
                safeEnd = preferredEnd;
            }
            String excerptText = fullSourceText.substring(safeStart, safeEnd).strip();
            if (excerptText.isBlank()) {
                throw new IllegalArgumentException("指定范围内没有可翻译的正文");
            }

            String baseName = stripExtension(chapter.fileName());
            String excerptFileName = "%s_excerpt_%06d_%06d.md".formatted(baseName, safeStart, safeEnd);
            String excerptTitle = "%s（原文 %d-%d 字）".formatted(chapter.title(), safeStart, safeEnd);
            ChapterRef excerptChapter = new ChapterRef(excerptTitle, excerptFileName);
            Path outputDir = projectDir.resolve("output").resolve("excerpts");
            Files.createDirectories(outputDir);
            Path outputPath = outputDir.resolve(excerptFileName).normalize();
            Path chunkDir = projectDir.resolve("output")
                    .resolve("chunks")
                    .resolve("excerpts")
                    .resolve(stripExtension(excerptFileName))
                    .normalize();
            List<String> chunks = splitSourceText(excerptText, Math.max(500, translationProperties.getChunkSize()));

            prepareChunkOutput(outputPath, chunkDir);
            writeTranslationProgress(projectId, chapterIndex, excerptChapter, outputPath, chunkDir, excerptText.length(),
                    chunks.size(), 0, 0, "RUNNING", null, null);
            logger.info("开始翻译章节片段: title={}, sourceRange={}-{}, sourceChars={}, chunkSize={}, chunks={}",
                    chapter.title(), safeStart, safeEnd, excerptText.length(), translationProperties.getChunkSize(), chunks.size());

            RuntimeChapterPlan plan = new RuntimeChapterPlan(
                    projectId,
                    chapterIndex,
                    excerptTitle,
                    excerptFileName,
                    excerptText.length(),
                    outputPath.toAbsolutePath().normalize().toString(),
                    chunkDir.toAbsolutePath().normalize().toString(),
                    chunks
            );
            return new RuntimeChapterExcerptPlan(plan, safeStart, safeEnd, fullSourceText.length(), safeLimit);
        } catch (IOException e) {
            throw new IllegalStateException("准备章节片段翻译失败: " + e.getMessage(), e);
        }
    }

    public RuntimeChunkResult translateRuntimeChunk(
            RuntimeChapterPlan plan,
            int chunkIndex,
            String translatedText,
            String previousSourceText,
            String previousTranslatedText,
            BooleanSupplier cancelRequested) {
        RuntimeChunkInput chunkInput = buildRuntimeChunkInput(plan, chunkIndex, previousSourceText, previousTranslatedText);
        String rawTranslatedText = modelTranslateRuntimeChunk(chunkInput, cancelRequested);
        String processedTranslatedText = postProcessRuntimeChunk(rawTranslatedText);
        RuntimeChunkResult result = persistRuntimeChunk(plan, chunkIndex, translatedText, processedTranslatedText, cancelRequested);
        recordRuntimeChunkTerms(plan, chunkIndex, chunkInput.sourceText(), result.latestChunkText());
        return result;
    }

    public RuntimeChunkInput buildRuntimeChunkInput(
            RuntimeChapterPlan plan,
            int chunkIndex,
            String previousSourceText,
            String previousTranslatedText) {
        if (plan == null) {
            throw new IllegalArgumentException("章节翻译计划不能为空");
        }
        if (chunkIndex <= 0 || chunkIndex > plan.totalChunks()) {
            throw new IllegalArgumentException("非法分段序号: " + chunkIndex);
        }

        String chunk = plan.chunks().get(chunkIndex - 1);
        String glossaryPrompt = termMemoryService.promptFor(plan.projectId(), chunk, previousTranslatedText);
        return new RuntimeChunkInput(
                plan.projectId(),
                plan.chapterIndex(),
                plan.title(),
                chunkIndex,
                plan.totalChunks(),
                chunk,
                previousSourceText == null ? "" : previousSourceText,
                previousTranslatedText == null ? "" : previousTranslatedText,
                glossaryPrompt == null ? "" : glossaryPrompt
        );
    }

    public String modelTranslateRuntimeChunk(RuntimeChunkInput input, BooleanSupplier cancelRequested) {
        if (input == null) {
            throw new IllegalArgumentException("分段输入不能为空");
        }
        if (isCancelRequested(cancelRequested)) {
            throw new NovelTranslationCancelledException("翻译任务已取消: chapterIndex=" + input.chapterIndex());
        }

        logger.info("调用 Ollama 翻译分段: title={}, chunk={}/{}, chars={}",
                input.title(), input.chunkIndex(), input.totalChunks(), input.sourceText().length());
        try {
            return translationClient.translate(
                    input.sourceText(),
                    input.previousSourceText(),
                    input.previousTranslatedText(),
                    input.glossaryPrompt()
            );
        } catch (RuntimeException e) {
            if (e instanceof NovelTranslationCancelledException) {
                throw e;
            }
            throw e;
        }
    }

    public String postProcessRuntimeChunk(String rawTranslatedText) {
        return textPostProcessor.process(rawTranslatedText);
    }

    public RuntimeChunkResult persistRuntimeChunk(
            RuntimeChapterPlan plan,
            int chunkIndex,
            String translatedText,
            String processedTranslatedText,
            BooleanSupplier cancelRequested) {
        if (plan == null) {
            throw new IllegalArgumentException("章节翻译计划不能为空");
        }
        if (chunkIndex <= 0 || chunkIndex > plan.totalChunks()) {
            throw new IllegalArgumentException("非法分段序号: " + chunkIndex);
        }

        Path outputPath = Path.of(plan.outputPath()).normalize();
        Path chunkDir = Path.of(plan.chunkDirectory()).normalize();
        ChapterRef chapter = new ChapterRef(plan.title(), plan.sourceFileName());
        String cumulativeText = translatedText == null ? "" : translatedText.strip();
        String sourceText = plan.chunks().get(chunkIndex - 1);
        String result = processedTranslatedText == null ? "" : processedTranslatedText.strip();

        if (isCancelRequested(cancelRequested)) {
            writeRuntimeProgress(plan, chapter, outputPath, chunkDir, Math.max(0, chunkIndex - 1),
                    cumulativeText.length(), "CANCELLED", null, "翻译任务已取消");
            throw new NovelTranslationCancelledException("翻译任务已取消: chapterIndex=" + plan.chapterIndex());
        }

        try {
            if (!result.isBlank()) {
                if (!cumulativeText.isBlank()) {
                    cumulativeText += System.lineSeparator() + System.lineSeparator();
                }
                cumulativeText += result;
                writeChunkResult(outputPath, chunkDir, chunkIndex, result, cumulativeText);
            }
            writeRuntimeProgress(plan, chapter, outputPath, chunkDir, chunkIndex,
                    cumulativeText.length(), "RUNNING", partChunkFileName(chunkIndex), result);
            logger.info("分段译文已落盘: title={}, chunk={}/{}, translatedChars={}, output={}, chunkFile={}",
                    plan.title(), chunkIndex, plan.totalChunks(), result.length(),
                    outputPath.toAbsolutePath().normalize(), chunkDir.resolve(partChunkFileName(chunkIndex)).toAbsolutePath().normalize());

            return new RuntimeChunkResult(
                    cumulativeText,
                    tail(sourceText, translationProperties.getContextSourceChars()),
                    tail(result, translationProperties.getContextTranslationChars()),
                    chunkIndex,
                    cumulativeText.length(),
                    result.length(),
                    result
            );
        } catch (IOException e) {
            writeRuntimeProgress(plan, chapter, outputPath, chunkDir, Math.max(0, chunkIndex - 1),
                    cumulativeText.length(), "FAILED", null, e.getMessage());
            throw new IllegalStateException("写入分段译文失败: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            if (e instanceof NovelTranslationCancelledException) {
                throw e;
            }
            if (isCancelRequested(cancelRequested)) {
                writeRuntimeProgress(plan, chapter, outputPath, chunkDir, Math.max(0, chunkIndex - 1),
                        cumulativeText.length(), "CANCELLED", null, "翻译任务已取消");
                throw new NovelTranslationCancelledException("翻译任务已取消: chapterIndex=" + plan.chapterIndex());
            }
            writeRuntimeProgress(plan, chapter, outputPath, chunkDir, Math.max(0, chunkIndex - 1),
                    cumulativeText.length(), "FAILED", null, e.getMessage());
            throw e;
        }
    }

    public void recordRuntimeChunkTerms(RuntimeChapterPlan plan, int chunkIndex, String sourceText, String translatedText) {
        if (plan == null) {
            throw new IllegalArgumentException("章节翻译计划不能为空");
        }
        termMemoryService.recordChunkTerms(plan.projectId(), plan.chapterIndex(), chunkIndex, sourceText, translatedText);
    }

    public String runtimeChapterSourceText(RuntimeChapterPlan plan) {
        if (plan == null) {
            throw new IllegalArgumentException("章节翻译计划不能为空");
        }
        if (plan.chunks() == null || plan.chunks().isEmpty()) {
            return "";
        }
        return String.join(System.lineSeparator() + System.lineSeparator(), plan.chunks()).strip();
    }

    public String applyRuntimeChapterPolish(RuntimeChapterPlan plan, String polishedText) {
        if (plan == null) {
            throw new IllegalArgumentException("章节翻译计划不能为空");
        }
        String safePolishedText = polishedText == null ? "" : polishedText.strip();
        if (safePolishedText.isBlank()) {
            throw new IllegalArgumentException("章节审校润色结果为空");
        }

        Path projectDir = novelProjectService.resolveProjectDir(plan.projectId());
        Path outputPath = Path.of(plan.outputPath()).normalize();
        Path chunkDir = Path.of(plan.chunkDirectory()).normalize();
        ChapterRef chapter = new ChapterRef(plan.title(), plan.sourceFileName());
        Path polishedPath = projectDir.resolve("polished").resolve(chapter.fileName()).normalize();

        try {
            Files.createDirectories(outputPath.getParent());
            Files.writeString(outputPath, safePolishedText + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.createDirectories(polishedPath.getParent());
            Files.writeString(polishedPath, safePolishedText + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            writeRuntimeProgress(plan, chapter, outputPath, chunkDir, plan.totalChunks(),
                    safePolishedText.length(), "POLISHED", partChunkFileName(plan.totalChunks()),
                    readChunkText(chunkDir.resolve(partChunkFileName(plan.totalChunks()))));
        } catch (IOException e) {
            throw new IllegalStateException("写入章节审校润色结果失败: " + e.getMessage(), e);
        }

        logger.info("章节审校润色结果已落盘: title={}, polishedChars={}, output={}, polished={}",
                plan.title(), safePolishedText.length(), outputPath.toAbsolutePath().normalize(),
                polishedPath.toAbsolutePath().normalize());
        return safePolishedText;
    }

    public TranslateChapterResponse completeRuntimeChapter(RuntimeChapterPlan plan, String translatedText) {
        if (plan == null) {
            throw new IllegalArgumentException("章节翻译计划不能为空");
        }

        Path projectDir = novelProjectService.resolveProjectDir(plan.projectId());
        Path outputPath = Path.of(plan.outputPath()).normalize();
        Path chunkDir = Path.of(plan.chunkDirectory()).normalize();
        ChapterRef chapter = new ChapterRef(plan.title(), plan.sourceFileName());
        String safeTranslatedText = translatedText == null ? "" : translatedText.strip();
        String latestChunkText = readChunkText(chunkDir.resolve(partChunkFileName(plan.totalChunks())));
        Path polishedPath = projectDir.resolve("polished").resolve(chapter.fileName()).normalize();

        writeRuntimeProgress(plan, chapter, outputPath, chunkDir, plan.totalChunks(),
                safeTranslatedText.length(), "COMPLETED", partChunkFileName(plan.totalChunks()), latestChunkText);
        try {
            Files.createDirectories(polishedPath.getParent());
            Files.copy(outputPath, polishedPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("复制最终译文到 polished 目录失败: " + e.getMessage(), e);
        }
        logger.info("章节翻译完成: title={}, translatedChars={}", plan.title(), safeTranslatedText.length());

        eventLogService.append(projectDir, "CHAPTER_TRANSLATED", "章节已翻译",
                Map.of(
                        "chapterIndex", plan.chapterIndex(),
                        "title", plan.title(),
                        "sourceFileName", plan.sourceFileName(),
                        "outputFileName", outputPath.getFileName().toString(),
                        "chunkDirectory", chunkDir.toAbsolutePath().normalize().toString(),
                        "translationModel", translationClient.modelName(),
                        "translatedAt", LocalDateTime.now().toString()
                ));

        TranslateChapterResponse response = new TranslateChapterResponse();
        response.setProjectId(plan.projectId());
        response.setChapterIndex(plan.chapterIndex());
        response.setTitle(plan.title());
        response.setSourceFileName(plan.sourceFileName());
        response.setOutputFileName(outputPath.getFileName().toString());
        response.setOutputPath(outputPath.toAbsolutePath().normalize().toString());
        response.setSourceCharCount(plan.sourceCharCount());
        response.setTranslatedCharCount(safeTranslatedText.length());
        response.setPreview(buildPreview(safeTranslatedText));
        response.setPreviewCharCount(Math.min(safeTranslatedText.length(), PREVIEW_CHAR_COUNT));
        response.setHasMore(safeTranslatedText.length() > PREVIEW_CHAR_COUNT);
        return response;
    }

    public TranslationProgressResponse getTranslationProgress(String projectId, int chapterIndex) {
        if (chapterIndex <= 0) {
            throw new IllegalArgumentException("章节序号必须大于 0");
        }

        Path projectDir = novelProjectService.resolveProjectDir(projectId);
        ChapterRef chapter = findChapter(projectDir, chapterIndex);
        Path outputDir = projectDir.resolve("output");
        Path outputPath = outputDir.resolve(chapter.fileName()).normalize();
        Path chunkDir = outputDir.resolve("chunks").resolve(stripExtension(chapter.fileName())).normalize();
        Path progressPath = chunkDir.resolve("progress.json");

        if (Files.isRegularFile(progressPath)) {
            try {
                return objectMapper.readValue(progressPath.toFile(), TranslationProgressResponse.class);
            } catch (IOException e) {
                throw new IllegalStateException("读取翻译进度失败: " + e.getMessage(), e);
            }
        }

        TranslationProgressResponse response = new TranslationProgressResponse();
        response.setProjectId(projectId);
        response.setChapterIndex(chapterIndex);
        response.setTitle(chapter.title());
        response.setStatus(Files.isRegularFile(outputPath) ? "UNKNOWN" : "NOT_STARTED");
        response.setOutputFileName(outputPath.getFileName().toString());
        response.setOutputPath(outputPath.toAbsolutePath().normalize().toString());
        response.setChunkDirectory(chunkDir.toAbsolutePath().normalize().toString());
        response.setUpdatedAt(LocalDateTime.now().format(TIME_FORMATTER));
        if (Files.isRegularFile(outputPath)) {
            try {
                response.setTranslatedCharCount(Files.readString(outputPath, StandardCharsets.UTF_8).strip().length());
            } catch (IOException e) {
                throw new IllegalStateException("读取译文失败: " + e.getMessage(), e);
            }
        }
        return response;
    }

    public TranslationReadResponse readTranslation(String projectId, int chapterIndex, int offset, int limit) {
        if (chapterIndex <= 0) {
            throw new IllegalArgumentException("章节序号必须大于 0");
        }
        int safeLimit = Math.max(200, Math.min(limit <= 0 ? 2000 : limit, 8000));

        Path projectDir = novelProjectService.resolveProjectDir(projectId);
        ChapterRef chapter = findChapter(projectDir, chapterIndex);
        Path outputPath = projectDir.resolve("output").resolve(chapter.fileName()).normalize();
        if (!Files.isRegularFile(outputPath)) {
            throw new IllegalArgumentException("译文文件不存在，请先翻译第 " + chapterIndex + " 章");
        }

        try {
            String text = Files.readString(outputPath, StandardCharsets.UTF_8).strip();
            int safeOffset = Math.max(0, Math.min(offset, text.length()));
            int nextOffset = Math.min(text.length(), safeOffset + safeLimit);
            TranslationReadResponse response = new TranslationReadResponse();
            response.setProjectId(projectId);
            response.setChapterIndex(chapterIndex);
            response.setTitle(chapter.title());
            response.setOutputFileName(outputPath.getFileName().toString());
            response.setOffset(safeOffset);
            response.setNextOffset(nextOffset);
            response.setTotalChars(text.length());
            response.setHasMore(nextOffset < text.length());
            response.setContent(text.substring(safeOffset, nextOffset));
            return response;
        } catch (IOException e) {
            throw new IllegalStateException("读取译文失败: " + e.getMessage(), e);
        }
    }

    private ChapterRef findChapter(Path projectDir, int chapterIndex) {
        Path manifest = projectDir.resolve("config").resolve("chapter_manifest.json");
        if (!Files.isRegularFile(manifest)) {
            throw new IllegalArgumentException("章节清单不存在，请先拆分小说章节");
        }

        try {
            JsonNode root = objectMapper.readTree(manifest.toFile());
            JsonNode chapters = root.path("chapters");
            if (!chapters.isArray()) {
                throw new IllegalArgumentException("章节清单格式错误");
            }
            for (JsonNode chapter : chapters) {
                if (chapter.path("index").asInt() == chapterIndex) {
                    String title = chapter.path("title").asText("第" + chapterIndex + "章");
                    String fileName = chapter.path("fileName").asText();
                    if (fileName == null || fileName.isBlank()) {
                        throw new IllegalArgumentException("章节文件名为空: " + chapterIndex);
                    }
                    return new ChapterRef(title, fileName);
                }
            }
            throw new IllegalArgumentException("未找到章节: " + chapterIndex);
        } catch (IOException e) {
            throw new IllegalStateException("读取章节清单失败: " + e.getMessage(), e);
        }
    }

    private String translateText(
            String projectId,
            int chapterIndex,
            ChapterRef chapter,
            String sourceText,
            Path outputPath,
            Path chunkDir,
            BooleanSupplier cancelRequested) throws IOException {
        if (sourceText.isBlank()) {
            return "";
        }

        StringBuilder translated = new StringBuilder();
        List<String> chunks = splitSourceText(sourceText, Math.max(500, translationProperties.getChunkSize()));
        int totalParts = chunks.size();
        prepareChunkOutput(outputPath, chunkDir);
        writeTranslationProgress(projectId, chapterIndex, chapter, outputPath, chunkDir, sourceText.length(),
                totalParts, 0, 0, "RUNNING", null, null);
        logger.info("开始翻译章节: title={}, model={}, sourceChars={}, chunkSize={}, chunks={}",
                chapter.title(), translationClient.modelName(), sourceText.length(), translationProperties.getChunkSize(), totalParts);
        int part = 1;
        String previousSourceText = "";
        String previousTranslatedText = "";
        for (String chunk : chunks) {
            if (isCancelRequested(cancelRequested)) {
                writeTranslationProgress(projectId, chapterIndex, chapter, outputPath, chunkDir, sourceText.length(),
                        totalParts, Math.max(0, part - 1), translated.length(), "CANCELLED", null, "翻译任务已取消");
                throw new NovelTranslationCancelledException("翻译任务已取消: chapterIndex=" + chapterIndex);
            }
            logger.info("调用 Ollama 翻译分段: title={}, chunk={}/{}, chars={}", chapter.title(), part, totalParts, chunk.length());
            try {
            String glossaryPrompt = termMemoryService.promptFor(projectId, chunk, previousTranslatedText);
            String result = textPostProcessor.process(
                    translationClient.translate(chunk, previousSourceText, previousTranslatedText, glossaryPrompt));
            if (result != null && !result.isBlank()) {
                if (!translated.isEmpty()) {
                    translated.append(System.lineSeparator()).append(System.lineSeparator());
                }
                translated.append(result.strip());
                writeChunkResult(outputPath, chunkDir, part, result.strip(), translated.toString());
                termMemoryService.recordChunkTerms(projectId, chapterIndex, part, chunk, result.strip());
                previousSourceText = tail(chunk, translationProperties.getContextSourceChars());
                previousTranslatedText = tail(result.strip(), translationProperties.getContextTranslationChars());
            }
                writeTranslationProgress(projectId, chapterIndex, chapter, outputPath, chunkDir, sourceText.length(),
                        totalParts, part, translated.length(), "RUNNING", partChunkFileName(part), result);
                logger.info("分段翻译完成: title={}, chunk={}/{}, translatedChars={}, output={}, chunkFile={}",
                        chapter.title(), part, totalParts, result == null ? 0 : result.length(),
                        outputPath.toAbsolutePath().normalize(), chunkDir.resolve(partChunkFileName(part)).toAbsolutePath().normalize());
                part++;
            } catch (RuntimeException e) {
                if (e instanceof NovelTranslationCancelledException) {
                    throw e;
                }
                writeTranslationProgress(projectId, chapterIndex, chapter, outputPath, chunkDir, sourceText.length(),
                        totalParts, Math.max(0, part - 1), translated.length(), "FAILED", null, e.getMessage());
                throw e;
            }
        }
        writeTranslationProgress(projectId, chapterIndex, chapter, outputPath, chunkDir, sourceText.length(),
                totalParts, totalParts, translated.length(), "COMPLETED", partChunkFileName(totalParts), previousTranslatedText);
        logger.info("章节翻译完成: title={}, translatedChars={}", chapter.title(), translated.length());
        return translated.toString();
    }

    private boolean isCancelRequested(BooleanSupplier cancelRequested) {
        return cancelRequested != null && cancelRequested.getAsBoolean();
    }

    private void writeRuntimeProgress(
            RuntimeChapterPlan plan,
            ChapterRef chapter,
            Path outputPath,
            Path chunkDir,
            int completedChunks,
            int translatedCharCount,
            String status,
            String latestChunkFileName,
            String latestChunkText) {
        try {
            writeTranslationProgress(plan.projectId(), plan.chapterIndex(), chapter, outputPath, chunkDir, plan.sourceCharCount(),
                    plan.totalChunks(), completedChunks, translatedCharCount, status, latestChunkFileName, latestChunkText);
        } catch (IOException e) {
            throw new IllegalStateException("写入翻译进度失败: " + e.getMessage(), e);
        }
    }

    private void prepareChunkOutput(Path outputPath, Path chunkDir) throws IOException {
        Files.createDirectories(outputPath.getParent());
        Files.createDirectories(chunkDir);
        try (var files = Files.list(chunkDir)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> {
                        String fileName = path.getFileName().toString();
                        return fileName.startsWith("part-") || fileName.equals("progress.json");
                    })
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            throw new IllegalStateException("清理旧分段文件失败: " + path, e);
                        }
                    });
        }
        Files.writeString(outputPath, "", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private void prepareReviewOutput(Path reviewChunkDir) throws IOException {
        Files.createDirectories(reviewChunkDir);
        try (var files = Files.list(reviewChunkDir)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".review.json"))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            throw new IllegalStateException("清理旧审校文件失败: " + path, e);
                        }
                    });
        }
    }

    private void writeChunkResult(Path outputPath, Path chunkDir, int part, String result, String cumulativeText) throws IOException {
        Path chunkPath = chunkDir.resolve(partChunkFileName(part));
        Files.writeString(chunkPath, result.strip() + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.writeString(outputPath, cumulativeText.strip() + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    public String rewriteRuntimeChunkResult(RuntimeChapterPlan plan, int chunkIndex, String revisedChunkText) {
        if (plan == null) {
            throw new IllegalArgumentException("章节翻译计划不能为空");
        }
        if (chunkIndex <= 0 || chunkIndex > plan.totalChunks()) {
            throw new IllegalArgumentException("非法分段序号: " + chunkIndex);
        }

        Path outputPath = Path.of(plan.outputPath()).normalize();
        Path chunkDir = Path.of(plan.chunkDirectory()).normalize();
        ChapterRef chapter = new ChapterRef(plan.title(), plan.sourceFileName());
        String safeRevised = revisedChunkText == null ? "" : revisedChunkText.strip();

        try {
            Files.createDirectories(outputPath.getParent());
            Files.createDirectories(chunkDir);
            Files.writeString(chunkDir.resolve(partChunkFileName(chunkIndex)),
                    safeRevised + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);

            String cumulativeText = rebuildTranslatedText(plan, chunkIndex);
            Files.writeString(outputPath, cumulativeText.strip() + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            writeRuntimeProgress(plan, chapter, outputPath, chunkDir, chunkIndex,
                    cumulativeText.length(), "FIXED", partChunkFileName(chunkIndex), safeRevised);
            logger.info("分段修订完成: title={}, chunk={}/{}, revisedChars={}, output={}, chunkFile={}",
                    plan.title(), chunkIndex, plan.totalChunks(), safeRevised.length(),
                    outputPath.toAbsolutePath().normalize(), chunkDir.resolve(partChunkFileName(chunkIndex)).toAbsolutePath().normalize());
            return cumulativeText;
        } catch (IOException e) {
            throw new IllegalStateException("写入修订分段失败: " + e.getMessage(), e);
        }
    }

    public String rebuildTranslatedText(RuntimeChapterPlan plan, int upToChunkIndex) {
        if (plan == null) {
            throw new IllegalArgumentException("章节翻译计划不能为空");
        }
        if (upToChunkIndex <= 0 || upToChunkIndex > plan.totalChunks()) {
            throw new IllegalArgumentException("非法分段序号: " + upToChunkIndex);
        }

        Path chunkDir = Path.of(plan.chunkDirectory()).normalize();
        StringBuilder builder = new StringBuilder();
        for (int index = 1; index <= upToChunkIndex; index++) {
            String text = readChunkText(chunkDir.resolve(partChunkFileName(index)));
            if (text.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(System.lineSeparator()).append(System.lineSeparator());
            }
            builder.append(text.strip());
        }
        return builder.toString();
    }

    private void writeTranslationProgress(
            String projectId,
            int chapterIndex,
            ChapterRef chapter,
            Path outputPath,
            Path chunkDir,
            int sourceCharCount,
            int totalChunks,
            int completedChunks,
            int translatedCharCount,
            String status,
            String latestChunkFileName,
            String latestChunkText) throws IOException {
        TranslationProgressResponse response = new TranslationProgressResponse();
        response.setProjectId(projectId);
        response.setChapterIndex(chapterIndex);
        response.setTitle(chapter.title());
        response.setStatus(status);
        response.setOutputFileName(outputPath.getFileName().toString());
        response.setOutputPath(outputPath.toAbsolutePath().normalize().toString());
        response.setChunkDirectory(chunkDir.toAbsolutePath().normalize().toString());
        response.setSourceCharCount(sourceCharCount);
        response.setTranslatedCharCount(translatedCharCount);
        response.setTotalChunks(totalChunks);
        response.setCompletedChunks(completedChunks);
        response.setLatestChunkFileName(latestChunkFileName);
        if (latestChunkFileName != null) {
            response.setLatestChunkPath(chunkDir.resolve(latestChunkFileName).toAbsolutePath().normalize().toString());
        }
        response.setLatestChunkPreview(buildPreview(latestChunkText == null ? "" : latestChunkText));
        response.setUpdatedAt(LocalDateTime.now().format(TIME_FORMATTER));

        Files.createDirectories(chunkDir);
        Path progressPath = chunkDir.resolve("progress.json");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(progressPath.toFile(), response);
    }

    private String readChunkText(Path chunkPath) {
        if (!Files.isRegularFile(chunkPath)) {
            return "";
        }
        try {
            return Files.readString(chunkPath, StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            return "";
        }
    }

    private List<String> splitSourceText(String sourceText, int chunkSize) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < sourceText.length()) {
            int end = Math.min(sourceText.length(), start + chunkSize);
            if (end < sourceText.length()) {
                end = findChunkBoundary(sourceText, start, end, chunkSize);
            }
            String chunk = sourceText.substring(start, end).strip();
            if (!chunk.isBlank()) {
                chunks.add(chunk);
            }
            start = end;
            while (start < sourceText.length() && Character.isWhitespace(sourceText.charAt(start))) {
                start++;
            }
        }
        return chunks;
    }

    private int findChunkBoundary(String text, int start, int preferredEnd, int chunkSize) {
        int minBoundary = start + Math.max(500, chunkSize / 2);
        int searchFrom = Math.min(preferredEnd, text.length() - 1);

        int paragraphBreak = text.lastIndexOf("\n\n", searchFrom);
        if (paragraphBreak >= minBoundary) {
            return paragraphBreak + 2;
        }

        int lineBreak = text.lastIndexOf('\n', searchFrom);
        if (lineBreak >= minBoundary) {
            return lineBreak + 1;
        }

        for (int i = searchFrom; i >= minBoundary; i--) {
            if (isSentenceBoundary(text.charAt(i))) {
                return i + 1;
            }
        }

        return preferredEnd;
    }

    private boolean isSentenceBoundary(char ch) {
        return ch == '。' || ch == '！' || ch == '？' || ch == '」' || ch == '』'
                || ch == '.' || ch == '!' || ch == '?';
    }

    private String partChunkFileName(int part) {
        return "part-%03d.md".formatted(part);
    }

    private String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String baseName = dot > 0 ? fileName.substring(0, dot) : fileName;
        return baseName.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private String tail(String value, int maxChars) {
        if (value == null || value.isBlank() || maxChars <= 0) {
            return "";
        }
        String stripped = value.strip();
        if (stripped.length() <= maxChars) {
            return stripped;
        }
        return stripped.substring(stripped.length() - maxChars);
    }

    private String buildPreview(String text) {
        String normalized = text == null ? "" : text.strip();
        if (normalized.length() <= PREVIEW_CHAR_COUNT) {
            return normalized;
        }
        return normalized.substring(0, PREVIEW_CHAR_COUNT) + "...";
    }

    private record ChapterRef(String title, String fileName) {
    }

    public record RuntimeChapterPlan(
            String projectId,
            int chapterIndex,
            String title,
            String sourceFileName,
            int sourceCharCount,
            String outputPath,
            String chunkDirectory,
            List<String> chunks) implements Serializable {

        public int totalChunks() {
            return chunks == null ? 0 : chunks.size();
        }
    }

    public record RuntimeChapterExcerptPlan(
            RuntimeChapterPlan plan,
            int startSourceOffset,
            int endSourceOffset,
            int fullSourceCharCount,
            int requestedSourceCharLimit) implements Serializable {
    }

    public record RuntimeChunkInput(
            String projectId,
            int chapterIndex,
            String title,
            int chunkIndex,
            int totalChunks,
            String sourceText,
            String previousSourceText,
            String previousTranslatedText,
            String glossaryPrompt) implements Serializable {
    }

    public record RuntimeChunkResult(
            String translatedText,
            String previousSourceText,
            String previousTranslatedText,
            int completedChunks,
            int translatedCharCount,
            int latestChunkTranslatedCharCount,
            String latestChunkText) implements Serializable {
    }
}

