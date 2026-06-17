package org.novelflow.novel.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.novelflow.config.NovelProperties;
import org.novelflow.novel.dto.CreateNovelProjectRequest;
import org.novelflow.novel.dto.NovelProjectResponse;
import org.novelflow.novel.dto.NovelProjectStatusResponse;
import org.novelflow.novel.dto.UploadSourceResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
public class NovelProjectService {

    private static final DateTimeFormatter PROJECT_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final Pattern EXCERPT_MARKDOWN_PATTERN = Pattern.compile("^(\\d+)_.*_excerpt_(\\d+)_(\\d+)\\.md$");
    private static final Pattern EXCERPT_REVIEW_PATTERN = Pattern.compile("^(\\d+)_.*_excerpt_(\\d+)_(\\d+)\\.chapter-review\\.json$");

    private final NovelProperties novelProperties;
    private final NovelEventLogService eventLogService;
    private final NovelRuntimeStateService runtimeStateService;
    private final ObjectMapper objectMapper;

    public NovelProjectService(
            NovelProperties novelProperties,
            NovelEventLogService eventLogService,
            NovelRuntimeStateService runtimeStateService,
            ObjectMapper objectMapper) {
        this.novelProperties = novelProperties;
        this.eventLogService = eventLogService;
        this.runtimeStateService = runtimeStateService;
        this.objectMapper = objectMapper;
    }

    public NovelProjectResponse createProject(CreateNovelProjectRequest request) {
        String projectName = normalizeBlank(request.getProjectName(), "novel-project");
        String sourceLanguage = normalizeBlank(request.getSourceLanguage(), "日语");
        String targetLanguage = normalizeBlank(request.getTargetLanguage(), "中文");
        String projectId = "novel-" + PROJECT_TIME_FORMAT.format(LocalDateTime.now()) + "-"
                + UUID.randomUUID().toString().substring(0, 8);

        Path projectDir = getWorkspaceRoot().resolve(projectId);
        try {
            createDirectories(projectDir);
            writeInitialFiles(projectDir, projectName, sourceLanguage, targetLanguage);
            writeProjectMetadata(projectDir, Map.of(
                    "projectId", projectId,
                    "projectName", projectName,
                    "sourceLanguage", sourceLanguage,
                    "targetLanguage", targetLanguage,
                    "createdAt", LocalDateTime.now().toString(),
                    "updatedAt", LocalDateTime.now().toString()
            ));
            runtimeStateService.initializeProject(projectDir, projectId);
            eventLogService.append(projectDir, "PROJECT_CREATED", "小说项目已创建",
                    Map.of("projectId", projectId, "projectName", projectName));
        } catch (IOException e) {
            throw new IllegalStateException("创建小说项目失败: " + e.getMessage(), e);
        }

        NovelProjectResponse response = new NovelProjectResponse();
        response.setProjectId(projectId);
        response.setProjectName(projectName);
        response.setProjectPath(projectDir.toAbsolutePath().normalize().toString());
        response.setSourceLanguage(sourceLanguage);
        response.setTargetLanguage(targetLanguage);
        return response;
    }

    public UploadSourceResponse uploadSource(String projectId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new IllegalArgumentException("文件名不能为空");
        }

        String safeFileName = Paths.get(originalFilename).getFileName().toString();
        validateExtension(safeFileName);

        Path projectDir = resolveProjectDir(projectId);
        Path rawDir = projectDir.resolve("raw");
        Path target = rawDir.resolve(safeFileName).normalize();
        if (!target.startsWith(rawDir.toAbsolutePath().normalize()) && target.isAbsolute()) {
            throw new IllegalArgumentException("非法文件路径");
        }

        try {
            Files.createDirectories(rawDir);
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
            mergeProjectMetadata(projectDir, Map.of(
                    "sourceFileName", safeFileName,
                    "sourceFileSize", file.getSize(),
                    "updatedAt", LocalDateTime.now().toString()
            ));
            runtimeStateService.markCompleted(projectDir, projectId, NovelRuntimeStateService.NODE_IMPORT_EPUB,
                    "小说原文已导入", Map.of("fileName", safeFileName, "size", file.getSize()));
            eventLogService.append(projectDir, "SOURCE_UPLOADED", "原始小说文件已上传",
                    Map.of("fileName", safeFileName, "size", file.getSize()));
        } catch (IOException e) {
            throw new IllegalStateException("上传小说原文失败: " + e.getMessage(), e);
        }

        UploadSourceResponse response = new UploadSourceResponse();
        response.setProjectId(projectId);
        response.setFileName(safeFileName);
        response.setFilePath(target.toAbsolutePath().normalize().toString());
        response.setFileSize(file.getSize());
        return response;
    }

    public List<Map<String, Object>> listProjectSummaries(String query) {
        Path workspace = getWorkspaceRoot();
        String queryKey = searchKey(query);
        try (Stream<Path> stream = Files.list(workspace)) {
            return stream
                    .filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith("novel-"))
                    .map(this::projectSummary)
                    .filter(summary -> queryKey.isBlank() || matchesSummary(summary, queryKey))
                    .sorted((left, right) -> Long.compare(longValue(right.get("lastModified")), longValue(left.get("lastModified"))))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("读取小说项目列表失败: " + e.getMessage(), e);
        }
    }

    public Optional<Map<String, Object>> findReusableProject(String sourceFileName, String projectName) {
        String sourceKey = searchKey(fileBaseName(sourceFileName));
        String projectKey = searchKey(fileBaseName(projectName));
        if (sourceKey.isBlank() && projectKey.isBlank()) {
            return Optional.empty();
        }
        return listProjectSummaries("")
                .stream()
                .filter(summary -> intValue(summary.get("chapterCount")) > 0)
                .filter(summary -> {
                    List<String> names = stringList(summary.get("sourceFileNames"));
                    String summaryProjectName = stringValue(summary.get("projectName"));
                    String manifestSource = stringValue(summary.get("manifestSourceFileName"));
                    List<String> candidates = new ArrayList<>(names);
                    candidates.add(summaryProjectName);
                    candidates.add(manifestSource);
                    return candidates.stream().anyMatch(candidate -> {
                        String candidateKey = searchKey(fileBaseName(candidate));
                        return !candidateKey.isBlank()
                                && ((!sourceKey.isBlank() && candidateKey.equals(sourceKey))
                                || (!projectKey.isBlank() && candidateKey.equals(projectKey)));
                    });
                })
                .sorted((left, right) -> Long.compare(reusableRank(right), reusableRank(left)))
                .findFirst();
    }

    public Map<String, Object> getProjectSummary(String projectId) {
        return projectSummary(resolveProjectDir(projectId));
    }

    public Map<String, Object> deleteEmptyProject(String projectId) {
        Path projectDir = resolveProjectDir(projectId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projectId", projectId);
        if (hasUserProjectFiles(projectDir)) {
            result.put("deleted", false);
            result.put("reason", "项目已包含 raw/source/output/review/polished/final 文件，拒绝自动删除");
            return result;
        }
        try (Stream<Path> walk = Files.walk(projectDir)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
            result.put("deleted", true);
            result.put("reason", "空项目已删除");
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("删除空小说项目失败: " + e.getMessage(), e);
        }
    }

    public NovelProjectStatusResponse getStatus(String projectId) {
        Path projectDir = resolveProjectDir(projectId);

        NovelProjectStatusResponse response = new NovelProjectStatusResponse();
        response.setProjectId(projectId);
        response.setProjectPath(projectDir.toAbsolutePath().normalize().toString());
        response.setExists(Files.exists(projectDir));
        response.setRawFileCount(countFiles(projectDir.resolve("raw"), ".txt", ".md", ".epub"));
        response.setChapterCount(countFiles(projectDir.resolve("source"), ".md"));
        response.setTranslatedCount(countFiles(projectDir.resolve("output"), ".md"));
        int reviewJsonCount = countFiles(projectDir.resolve("review").resolve("chapters"), ".json");
        response.setReviewCount(countFiles(projectDir.resolve("review"), ".md") + reviewJsonCount);
        response.setPolishedCount(countFiles(projectDir.resolve("polished"), ".md"));
        response.setFullTranslatedCount(countFiles(projectDir.resolve("output"), ".md"));
        response.setFullPolishedCount(countFullPolishedChapters(projectDir));
        response.setExcerptCount(countFiles(projectDir.resolve("output").resolve("excerpts"), ".md"));
        response.setExcerptChapterCount(collectExcerptProgress(projectDir).size());
        response.setReviewJsonCount(reviewJsonCount);
        response.setProgressFile(projectDir.resolve("config").resolve("progress.yaml").toString());
        response.setRuntimeStateFile(runtimeStateService.stateFile(projectDir).toString());
        response.setEventLogFile(projectDir.resolve("logs").resolve("events.jsonl").toString());
        return response;
    }

    public Path resolveProjectDir(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("项目ID不能为空");
        }
        Path workspace = getWorkspaceRoot();
        Path projectDir = workspace.resolve(projectId).normalize();
        if (!projectDir.startsWith(workspace)) {
            throw new IllegalArgumentException("非法项目ID");
        }
        if (!Files.exists(projectDir)) {
            throw new IllegalArgumentException("小说项目不存在: " + projectId);
        }
        return projectDir;
    }

    public boolean projectExists(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return false;
        }
        Path workspace = getWorkspaceRoot();
        Path projectDir = workspace.resolve(projectId).normalize();
        return projectDir.startsWith(workspace) && Files.isDirectory(projectDir);
    }

    public Path getWorkspaceRoot() {
        try {
            Path root = Paths.get(novelProperties.getWorkspace()).toAbsolutePath().normalize();
            Files.createDirectories(root);
            return root;
        } catch (IOException e) {
            throw new IllegalStateException("初始化小说工作区失败: " + e.getMessage(), e);
        }
    }

    private void createDirectories(Path projectDir) throws IOException {
        for (String dir : new String[]{"config", "config/parts", "raw", "source", "output", "review", "polished", "logs", "final"}) {
            Files.createDirectories(projectDir.resolve(dir));
        }
    }

    private void writeInitialFiles(Path projectDir, String projectName, String sourceLanguage, String targetLanguage) throws IOException {
        Files.writeString(projectDir.resolve("config").resolve("terms.yaml"), """
                # 术语表
                terms: []
                # === 术语表结束 ===
                """);

        Files.writeString(projectDir.resolve("config").resolve("term_notes.yaml"), """
                # 术语变更记录
                notes: []
                """);

        Files.writeString(projectDir.resolve("config").resolve("summary.yaml"), """
                # 小说上下文摘要
                projectName: "%s"
                styleGuide: ""
                globalSummary: ""
                recentSummary: ""
                """.formatted(escapeYaml(projectName)));

        Files.writeString(projectDir.resolve("config").resolve("progress.yaml"), """
                # NovelFlow 进度追踪
                projectName: "%s"
                sourceLanguage: "%s"
                targetLanguage: "%s"
                status: "INITIALIZED"
                totalChapters: 0
                lastCompletedChapter: 0
                nextChapter: 1
                createdAt: "%s"
                chapters: []
                """.formatted(
                escapeYaml(projectName),
                escapeYaml(sourceLanguage),
                escapeYaml(targetLanguage),
                LocalDateTime.now()));

        Files.writeString(projectDir.resolve("logs").resolve("events.jsonl"), "");
    }

    private void writeProjectMetadata(Path projectDir, Map<String, Object> metadata) throws IOException {
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(projectDir.resolve("config").resolve("project.json").toFile(), metadata);
    }

    private void mergeProjectMetadata(Path projectDir, Map<String, Object> updates) throws IOException {
        Map<String, Object> metadata = new LinkedHashMap<>();
        Path metadataFile = projectDir.resolve("config").resolve("project.json");
        if (Files.isRegularFile(metadataFile)) {
            JsonNode root = objectMapper.readTree(metadataFile.toFile());
            root.fields().forEachRemaining(entry -> metadata.put(entry.getKey(), jsonValue(entry.getValue())));
        }
        metadata.putAll(updates);
        writeProjectMetadata(projectDir, metadata);
    }

    private Map<String, Object> projectSummary(Path projectDir) {
        String projectId = projectDir.getFileName().toString();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("projectId", projectId);
        summary.put("projectPath", projectDir.toAbsolutePath().normalize().toString());
        summary.put("lastModified", lastModified(projectDir));

        Map<String, Object> metadata = readMetadata(projectDir);
        summary.putAll(metadata);

        List<String> rawFileNames = listFileNames(projectDir.resolve("raw"), ".txt", ".md", ".epub");
        summary.put("sourceFileNames", rawFileNames);
        if (stringValue(summary.get("sourceFileName")).isBlank() && !rawFileNames.isEmpty()) {
            summary.put("sourceFileName", rawFileNames.get(0));
        }
        if (stringValue(summary.get("projectName")).isBlank()) {
            summary.put("projectName", deriveProjectName(projectDir, rawFileNames));
        }

        Map<String, Object> manifest = readChapterManifest(projectDir);
        summary.putAll(manifest);
        try {
            NovelProjectStatusResponse status = getStatus(projectId);
            summary.put("rawFileCount", status.getRawFileCount());
            summary.put("chapterCount", status.getChapterCount());
            summary.put("translatedCount", status.getTranslatedCount());
            summary.put("reviewCount", status.getReviewCount());
            summary.put("polishedCount", status.getPolishedCount());
            summary.put("fullTranslatedCount", status.getFullTranslatedCount());
            summary.put("fullPolishedCount", status.getFullPolishedCount());
            summary.put("reviewJsonCount", status.getReviewJsonCount());
            attachTranslationProgress(projectDir, summary);
        } catch (Exception e) {
            summary.put("statusError", e.getMessage());
        }
        return summary;
    }

    private Map<String, Object> readMetadata(Path projectDir) {
        Path metadataFile = projectDir.resolve("config").resolve("project.json");
        if (!Files.isRegularFile(metadataFile)) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> metadata = new LinkedHashMap<>();
            JsonNode root = objectMapper.readTree(metadataFile.toFile());
            root.fields().forEachRemaining(entry -> metadata.put(entry.getKey(), jsonValue(entry.getValue())));
            return metadata;
        } catch (IOException e) {
            return new LinkedHashMap<>();
        }
    }

    private Map<String, Object> readChapterManifest(Path projectDir) {
        Path manifestFile = projectDir.resolve("config").resolve("chapter_manifest.json");
        if (!Files.isRegularFile(manifestFile)) {
            return new LinkedHashMap<>();
        }
        try {
            JsonNode root = objectMapper.readTree(manifestFile.toFile());
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("manifestSourceFileName", root.path("sourceFileName").asText(""));
            manifest.put("manifestGeneratedAt", root.path("generatedAt").asText(""));
            manifest.put("manifestTotalChapters", root.path("totalChapters").asInt(0));
            List<Map<String, Object>> chapters = new ArrayList<>();
            for (JsonNode chapter : root.path("chapters")) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("index", chapter.path("index").asInt(0));
                item.put("title", chapter.path("title").asText(""));
                item.put("fileName", chapter.path("fileName").asText(""));
                item.put("charCount", chapter.path("charCount").asInt(0));
                chapters.add(item);
            }
            manifest.put("chapters", chapters);
            return manifest;
        } catch (IOException e) {
            return new LinkedHashMap<>();
        }
    }

    @SuppressWarnings("unchecked")
    private void attachTranslationProgress(Path projectDir, Map<String, Object> summary) {
        Object chaptersValue = summary.get("chapters");
        int fullTranslatedChapters = 0;
        int fullPolishedChapters = 0;
        List<Map<String, Object>> translatedChapterList = new ArrayList<>();
        List<Map<String, Object>> excerptChapterList = new ArrayList<>();
        Map<Integer, ChapterExcerptProgress> excerptProgress = collectExcerptProgress(projectDir);
        if (chaptersValue instanceof List<?> chapters) {
            for (Object chapterValue : chapters) {
                if (!(chapterValue instanceof Map<?, ?> chapterMap)) {
                    continue;
                }
                Map<String, Object> chapter = (Map<String, Object>) chapterMap;
                int chapterIndex = intValue(chapter.get("index"));
                String fileName = stringValue(chapter.get("fileName"));
                Path output = projectDir.resolve("output").resolve(fileName);
                Path polished = projectDir.resolve("polished").resolve(fileName);
                boolean translated = Files.isRegularFile(output);
                boolean polishedDone = Files.isRegularFile(polished);
                chapter.put("translated", translated);
                chapter.put("polished", polishedDone);
                if (translated) {
                    fullTranslatedChapters++;
                    chapter.put("translatedChars", readCharCount(output));
                    translatedChapterList.add(Map.of(
                            "index", chapter.getOrDefault("index", 0),
                            "title", chapter.getOrDefault("title", ""),
                            "fileName", fileName,
                            "translatedChars", chapter.get("translatedChars")
                    ));
                }
                if (polishedDone) {
                    fullPolishedChapters++;
                    chapter.put("polishedChars", readCharCount(polished));
                }
                ChapterExcerptProgress excerpt = excerptProgress.get(chapterIndex);
                if (excerpt != null) {
                    excerpt.title = stringValue(firstNonBlank(chapter.get("title"), excerpt.title));
                    Map<String, Object> excerptStatus = excerpt.toMap();
                    chapter.put("excerptTranslated", true);
                    chapter.put("excerptStatus", excerptStatus);
                    chapter.put("excerptRanges", excerptStatus.get("ranges"));
                    chapter.put("nextExcerptSourceOffset", excerptStatus.get("nextSourceOffset"));
                    chapter.put("latestExcerptJob", excerptStatus.get("latestJob"));
                    chapter.put("excerptReviewStatus", excerptStatus.get("reviewStatus"));
                    excerptChapterList.add(excerptStatus);
                } else {
                    chapter.put("excerptTranslated", false);
                }
            }
        }
        for (ChapterExcerptProgress excerpt : excerptProgress.values()) {
            if (excerptChapterList.stream().noneMatch(item -> intValue(item.get("chapterIndex")) == excerpt.chapterIndex)) {
                excerptChapterList.add(excerpt.toMap());
            }
        }
        List<Map<String, Object>> latestExcerpts = listLatestMarkdownFiles(projectDir.resolve("output").resolve("excerpts"), 8);
        summary.put("fullTranslatedChapters", fullTranslatedChapters);
        summary.put("fullPolishedChapters", fullPolishedChapters);
        summary.put("translatedChapters", translatedChapterList);
        summary.put("excerptCount", countFiles(projectDir.resolve("output").resolve("excerpts"), ".md"));
        summary.put("excerptChapterCount", excerptChapterList.size());
        summary.put("excerptChapters", excerptChapterList);
        summary.put("latestExcerpts", latestExcerpts);
        summary.put("latestTranslationJobs", listLatestTranslationJobs(projectDir, 10));
        summary.put("latestEvents", listLatestEventMessages(projectDir.resolve("logs").resolve("events.jsonl"), 8));
    }

    private List<Map<String, Object>> listLatestMarkdownFiles(Path dir, int limit) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> hasExtension(path, ".md"))
                    .sorted((left, right) -> Long.compare(lastModified(right), lastModified(left)))
                    .limit(Math.max(1, limit))
                    .map(path -> {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("fileName", path.getFileName().toString());
                        item.put("charCount", readCharCount(path));
                        item.put("lastModified", lastModified(path));
                        return item;
                    })
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private Map<Integer, ChapterExcerptProgress> collectExcerptProgress(Path projectDir) {
        Map<Integer, ChapterExcerptProgress> progress = new TreeMap<>();
        collectExcerptJobs(projectDir.resolve("config").resolve("jobs"), progress);
        collectExcerptMarkdownFiles(projectDir.resolve("output").resolve("excerpts"), "output", progress);
        collectExcerptMarkdownFiles(projectDir.resolve("polished"), "polished", progress);
        collectExcerptReviewFiles(projectDir.resolve("review").resolve("chapters"), progress);
        return progress;
    }

    private void collectExcerptJobs(Path jobsDir, Map<Integer, ChapterExcerptProgress> progress) {
        if (!Files.isDirectory(jobsDir)) {
            return;
        }
        try (Stream<Path> stream = Files.list(jobsDir)) {
            for (Path path : stream
                    .filter(Files::isRegularFile)
                    .filter(file -> hasExtension(file, ".json"))
                    .sorted(Comparator.comparingLong(this::lastModified))
                    .toList()) {
                try {
                    JsonNode root = objectMapper.readTree(path.toFile());
                    if (!isExcerptJob(root)) {
                        continue;
                    }
                    int chapterIndex = root.path("chapterIndex").asInt(0);
                    if (chapterIndex <= 0) {
                        ExcerptFileName parsed = parseExcerptMarkdownName(root.path("sourceFileName").asText(""));
                        chapterIndex = parsed == null ? 0 : parsed.chapterIndex();
                    }
                    if (chapterIndex <= 0) {
                        continue;
                    }
                    progress.computeIfAbsent(chapterIndex, ChapterExcerptProgress::new)
                            .addJob(root, lastModified(path));
                } catch (Exception ignored) {
                    // Ignore malformed legacy job files; filesystem evidence is still collected below.
                }
            }
        } catch (IOException ignored) {
            // Best-effort project overview.
        }
    }

    private void collectExcerptMarkdownFiles(Path dir, String kind, Map<Integer, ChapterExcerptProgress> progress) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            for (Path path : stream
                    .filter(Files::isRegularFile)
                    .filter(file -> hasExtension(file, ".md"))
                    .sorted(Comparator.comparingLong(this::lastModified))
                    .toList()) {
                ExcerptFileName parsed = parseExcerptMarkdownName(path.getFileName().toString());
                if (parsed == null) {
                    continue;
                }
                progress.computeIfAbsent(parsed.chapterIndex(), ChapterExcerptProgress::new)
                        .addMarkdown(kind, path, parsed.startSourceOffset(), parsed.endSourceOffset(),
                                readCharCount(path), lastModified(path));
            }
        } catch (IOException ignored) {
            // Best-effort project overview.
        }
    }

    private void collectExcerptReviewFiles(Path dir, Map<Integer, ChapterExcerptProgress> progress) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            for (Path path : stream
                    .filter(Files::isRegularFile)
                    .filter(file -> hasExtension(file, ".json"))
                    .sorted(Comparator.comparingLong(this::lastModified))
                    .toList()) {
                ExcerptFileName parsed = parseExcerptReviewName(path.getFileName().toString());
                if (parsed == null) {
                    continue;
                }
                try {
                    JsonNode root = objectMapper.readTree(path.toFile());
                    progress.computeIfAbsent(parsed.chapterIndex(), ChapterExcerptProgress::new)
                            .addReview(path, root, parsed.startSourceOffset(), parsed.endSourceOffset(),
                                    lastModified(path));
                } catch (Exception ignored) {
                    progress.computeIfAbsent(parsed.chapterIndex(), ChapterExcerptProgress::new)
                            .addReviewFileOnly(path, parsed.startSourceOffset(), parsed.endSourceOffset(),
                                    lastModified(path));
                }
            }
        } catch (IOException ignored) {
            // Best-effort project overview.
        }
    }

    private List<Map<String, Object>> listLatestTranslationJobs(Path projectDir, int limit) {
        Path jobsDir = projectDir.resolve("config").resolve("jobs");
        if (!Files.isDirectory(jobsDir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(jobsDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> hasExtension(path, ".json"))
                    .sorted((left, right) -> Long.compare(lastModified(right), lastModified(left)))
                    .limit(Math.max(1, limit))
                    .map(path -> {
                        try {
                            JsonNode root = objectMapper.readTree(path.toFile());
                            Map<String, Object> job = jobSummary(root);
                            job.put("jobFileName", path.getFileName().toString());
                            job.put("lastModified", lastModified(path));
                            return job;
                        } catch (Exception e) {
                            return Map.<String, Object>of(
                                    "jobFileName", path.getFileName().toString(),
                                    "lastModified", lastModified(path),
                                    "error", e.getMessage() == null ? "" : e.getMessage()
                            );
                        }
                    })
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private int countFullPolishedChapters(Path projectDir) {
        Map<String, Object> manifest = readChapterManifest(projectDir);
        Object chapters = manifest.get("chapters");
        if (!(chapters instanceof List<?> list)) {
            return Math.max(0, countFiles(projectDir.resolve("polished"), ".md")
                    - countExcerptMarkdownFiles(projectDir.resolve("polished")));
        }
        int count = 0;
        for (Object item : list) {
            if (item instanceof Map<?, ?> chapter) {
                String fileName = stringValue(chapter.get("fileName"));
                if (!fileName.isBlank() && Files.isRegularFile(projectDir.resolve("polished").resolve(fileName))) {
                    count++;
                }
            }
        }
        return count;
    }

    private boolean isExcerptJob(JsonNode root) {
        if (root == null || root.isMissingNode()) {
            return false;
        }
        return "EXCERPT".equalsIgnoreCase(root.path("jobType").asText(""))
                || root.path("sourceCharLimit").asInt(0) > 0
                || root.path("sourceFileName").asText("").contains("_excerpt_")
                || root.path("outputPath").asText("").contains("_excerpt_");
    }

    private int countExcerptMarkdownFiles(Path dir) {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return (int) stream
                    .filter(Files::isRegularFile)
                    .filter(path -> parseExcerptMarkdownName(path.getFileName().toString()) != null)
                    .count();
        } catch (IOException e) {
            return 0;
        }
    }

    private Map<String, Object> jobSummary(JsonNode root) {
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("jobId", root.path("jobId").asText(""));
        job.put("jobType", root.path("jobType").asText(""));
        job.put("chapterIndex", root.path("chapterIndex").asInt(0));
        job.put("status", root.path("status").asText(""));
        job.put("currentNode", root.path("currentNode").asText(""));
        job.put("message", root.path("message").asText(""));
        job.put("errorMessage", root.path("errorMessage").asText(""));
        job.put("title", root.path("title").asText(""));
        job.put("sourceFileName", root.path("sourceFileName").asText(""));
        job.put("sourceStartOffset", root.path("sourceStartOffset").asInt(0));
        job.put("sourceEndOffset", root.path("sourceEndOffset").asInt(0));
        job.put("sourceCharLimit", root.path("sourceCharLimit").asInt(0));
        job.put("sourceCharCount", root.path("sourceCharCount").asInt(0));
        job.put("fullSourceCharCount", root.path("fullSourceCharCount").asInt(0));
        job.put("completedChunks", root.path("completedChunks").asInt(0));
        job.put("totalChunks", root.path("totalChunks").asInt(0));
        job.put("createdAt", root.path("createdAt").asText(""));
        job.put("updatedAt", root.path("updatedAt").asText(""));
        job.put("completedAt", root.path("completedAt").asText(""));
        job.put("outputPath", root.path("outputPath").asText(""));
        return job;
    }

    private ExcerptFileName parseExcerptMarkdownName(String fileName) {
        return parseExcerptName(fileName, EXCERPT_MARKDOWN_PATTERN);
    }

    private ExcerptFileName parseExcerptReviewName(String fileName) {
        return parseExcerptName(fileName, EXCERPT_REVIEW_PATTERN);
    }

    private ExcerptFileName parseExcerptName(String fileName, Pattern pattern) {
        if (fileName == null || fileName.isBlank()) {
            return null;
        }
        Matcher matcher = pattern.matcher(fileName);
        if (!matcher.matches()) {
            return null;
        }
        try {
            return new ExcerptFileName(
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3))
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<Map<String, Object>> listLatestEventMessages(Path eventLogFile, int limit) {
        if (!Files.isRegularFile(eventLogFile)) {
            return List.of();
        }
        try {
            List<String> lines = Files.readAllLines(eventLogFile);
            List<Map<String, Object>> events = new ArrayList<>();
            for (int i = lines.size() - 1; i >= 0 && events.size() < Math.max(1, limit); i--) {
                String line = lines.get(i);
                if (line == null || line.isBlank()) {
                    continue;
                }
                try {
                    JsonNode root = objectMapper.readTree(line);
                    Map<String, Object> event = new LinkedHashMap<>();
                    event.put("time", root.path("time").asText(root.path("timestamp").asText("")));
                    event.put("type", root.path("type").asText(root.path("event").asText("")));
                    event.put("message", root.path("message").asText(""));
                    events.add(event);
                } catch (Exception ignored) {
                    events.add(Map.of("message", line));
                }
            }
            return events;
        } catch (IOException e) {
            return List.of();
        }
    }

    private record ExcerptFileName(int chapterIndex, int startSourceOffset, int endSourceOffset) {
    }

    private final class ChapterExcerptProgress {
        private final int chapterIndex;
        private final Map<String, Map<String, Object>> rangesByKey = new LinkedHashMap<>();
        private Map<String, Object> latestJob = new LinkedHashMap<>();
        private long latestJobModified;
        private long latestReviewModified;
        private String title = "";
        private String reviewStatus = "";
        private String reviewSummary = "";
        private int fullSourceCharCount;
        private int sourceCharLimit;
        private int reviewIssueCount;
        private boolean reviewApplied;

        private ChapterExcerptProgress(int chapterIndex) {
            this.chapterIndex = chapterIndex;
        }

        private void addJob(JsonNode root, long lastModified) {
            String jobTitle = root.path("title").asText("");
            if (!jobTitle.isBlank()) {
                title = jobTitle;
            }
            int start = root.path("sourceStartOffset").asInt(0);
            int end = root.path("sourceEndOffset").asInt(0);
            if (end <= start) {
                ExcerptFileName parsed = parseExcerptMarkdownName(root.path("sourceFileName").asText(""));
                if (parsed != null) {
                    start = parsed.startSourceOffset();
                    end = parsed.endSourceOffset();
                }
            }
            int sourceChars = root.path("sourceCharCount").asInt(Math.max(0, end - start));
            if (end <= start && sourceChars > 0) {
                end = start + sourceChars;
            }
            int fullChars = root.path("fullSourceCharCount").asInt(0);
            if (fullChars > 0) {
                fullSourceCharCount = Math.max(fullSourceCharCount, fullChars);
            }
            int limit = root.path("sourceCharLimit").asInt(0);
            if (limit > 0) {
                sourceCharLimit = limit;
            }
            Map<String, Object> job = jobSummary(root);
            job.put("lastModified", lastModified);
            if (lastModified >= latestJobModified) {
                latestJobModified = lastModified;
                latestJob = job;
            }
            if (end > start) {
                Map<String, Object> range = range(start, end);
                range.put("jobId", job.get("jobId"));
                range.put("jobStatus", job.get("status"));
                range.put("jobCurrentNode", job.get("currentNode"));
                range.put("jobUpdatedAt", job.get("updatedAt"));
                range.put("jobOutputPath", job.get("outputPath"));
                range.put("sourceCharLimit", limit);
                range.put("fullSourceCharCount", fullSourceCharCount);
                range.put("sourceCharCount", sourceChars > 0 ? sourceChars : Math.max(0, end - start));
            }
        }

        private void addMarkdown(String kind, Path path, int start, int end, int charCount, long lastModified) {
            Map<String, Object> range = range(start, end);
            range.put(kind, true);
            range.put(kind + "FileName", path.getFileName().toString());
            range.put(kind + "Path", path.toAbsolutePath().normalize().toString());
            range.put(kind + "Chars", charCount);
            range.put(kind + "LastModified", lastModified);
            range.put("sourceCharCount", Math.max(0, end - start));
        }

        private void addReview(Path path, JsonNode root, int start, int end, long lastModified) {
            Map<String, Object> range = range(start, end);
            String status = root.path("status").asText("");
            int issues = root.path("issues").isArray() ? root.path("issues").size() : 0;
            boolean applied = root.path("applied").asBoolean(false);
            range.put("review", true);
            range.put("reviewFileName", path.getFileName().toString());
            range.put("reviewPath", path.toAbsolutePath().normalize().toString());
            range.put("reviewStatus", status);
            range.put("reviewApplied", applied);
            range.put("reviewIssueCount", issues);
            range.put("reviewUpdatedAt", root.path("updatedAt").asText(""));
            range.put("reviewSummary", root.path("summary").asText(""));
            range.put("reviewLastModified", lastModified);
            reviewIssueCount += issues;
            reviewApplied = reviewApplied || applied;
            if (lastModified >= latestReviewModified) {
                latestReviewModified = lastModified;
                reviewStatus = status;
                reviewSummary = root.path("summary").asText("");
            }
        }

        private void addReviewFileOnly(Path path, int start, int end, long lastModified) {
            Map<String, Object> range = range(start, end);
            range.put("review", true);
            range.put("reviewFileName", path.getFileName().toString());
            range.put("reviewPath", path.toAbsolutePath().normalize().toString());
            range.put("reviewLastModified", lastModified);
            if (lastModified >= latestReviewModified) {
                latestReviewModified = lastModified;
                reviewStatus = "UNKNOWN";
            }
        }

        private Map<String, Object> toMap() {
            List<Map<String, Object>> ranges = rangesByKey.values().stream()
                    .sorted(Comparator.comparingInt(item -> intValue(item.get("sourceStartOffset"))))
                    .<Map<String, Object>>map(item -> new LinkedHashMap<String, Object>(item))
                    .toList();
            int totalSourceChars = 0;
            int maxSourceEnd = 0;
            int contiguousSourceEnd = 0;
            boolean hasZeroBasedRange = false;
            boolean hasPolishedRange = false;
            boolean allRangesPolished = !ranges.isEmpty();
            for (Map<String, Object> range : ranges) {
                int start = intValue(range.get("sourceStartOffset"));
                int end = intValue(range.get("sourceEndOffset"));
                totalSourceChars += Math.max(0, end - start);
                maxSourceEnd = Math.max(maxSourceEnd, end);
                if (start <= contiguousSourceEnd) {
                    hasZeroBasedRange = true;
                    contiguousSourceEnd = Math.max(contiguousSourceEnd, end);
                }
                boolean polished = booleanValue(range.get("polished"));
                hasPolishedRange = hasPolishedRange || polished;
                allRangesPolished = allRangesPolished && polished;
            }
            int nextSourceOffset = hasZeroBasedRange ? contiguousSourceEnd : maxSourceEnd;
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("chapterIndex", chapterIndex);
            result.put("title", title);
            result.put("rangeCount", ranges.size());
            result.put("ranges", ranges);
            result.put("totalExcerptSourceChars", totalSourceChars);
            result.put("latestSourceEnd", maxSourceEnd);
            result.put("contiguousSourceEnd", contiguousSourceEnd);
            result.put("nextSourceOffset", nextSourceOffset);
            result.put("hasSourceGap", hasZeroBasedRange && contiguousSourceEnd < maxSourceEnd);
            result.put("fullSourceCharCount", fullSourceCharCount);
            result.put("hasMoreSource", fullSourceCharCount <= 0 || nextSourceOffset < fullSourceCharCount);
            result.put("sourceCharLimit", sourceCharLimit);
            result.put("hasPolishedExcerpt", hasPolishedRange);
            result.put("allExcerptRangesPolished", allRangesPolished);
            result.put("reviewStatus", reviewStatus);
            result.put("reviewApplied", reviewApplied);
            result.put("reviewIssueCount", reviewIssueCount);
            result.put("reviewSummary", reviewSummary);
            result.put("latestJob", latestJob);
            return result;
        }

        private Map<String, Object> range(int start, int end) {
            String key = start + ":" + end;
            return rangesByKey.computeIfAbsent(key, ignored -> {
                Map<String, Object> range = new LinkedHashMap<>();
                range.put("sourceStartOffset", start);
                range.put("sourceEndOffset", end);
                range.put("sourceCharCount", Math.max(0, end - start));
                range.put("output", false);
                range.put("polished", false);
                range.put("review", false);
                return range;
            });
        }
    }

    private String deriveProjectName(Path projectDir, List<String> rawFileNames) {
        if (!rawFileNames.isEmpty()) {
            return fileBaseName(rawFileNames.get(0));
        }
        return projectDir.getFileName().toString();
    }

    private void validateExtension(String fileName) {
        String extension = "";
        int dot = fileName.lastIndexOf('.');
        if (dot >= 0 && dot < fileName.length() - 1) {
            extension = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        }
        boolean allowed = Arrays.stream(novelProperties.getAllowedExtensions().split(","))
                .map(String::trim)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(extension::equals);
        if (!allowed) {
            throw new IllegalArgumentException("不支持的小说文件格式，仅支持: " + novelProperties.getAllowedExtensions());
        }
    }

    private int countFiles(Path dir, String... extensions) {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return (int) stream
                    .filter(Files::isRegularFile)
                    .filter(path -> hasExtension(path, extensions))
                    .count();
        } catch (IOException e) {
            return 0;
        }
    }

    private int countFilesRecursive(Path dir) {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            return (int) stream.filter(Files::isRegularFile).count();
        } catch (IOException e) {
            return 0;
        }
    }

    private boolean hasUserProjectFiles(Path projectDir) {
        for (String dir : List.of("raw", "source", "output", "review", "polished", "final")) {
            if (countFilesRecursive(projectDir.resolve(dir)) > 0) {
                return true;
            }
        }
        return false;
    }

    private int readCharCount(Path path) {
        try {
            return Files.readString(path).length();
        } catch (IOException e) {
            return 0;
        }
    }

    private List<String> listFileNames(Path dir, String... extensions) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> hasExtension(path, extensions))
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private boolean hasExtension(Path path, String... extensions) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return Arrays.stream(extensions).anyMatch(name::endsWith);
    }

    public Path findDefaultRawFile(Path projectDir) {
        Path rawDir = projectDir.resolve("raw");
        if (!Files.isDirectory(rawDir)) {
            throw new IllegalArgumentException("raw 目录不存在，请先上传小说原文");
        }
        try (Stream<Path> stream = Files.list(rawDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> hasExtension(path, ".txt", ".md", ".epub"))
                    .min(Comparator.comparing(path -> path.getFileName().toString()))
                    .orElseThrow(() -> new IllegalArgumentException("raw 目录中没有可拆分的 txt/md/epub 文件"));
        } catch (IOException e) {
            throw new IllegalStateException("读取 raw 目录失败: " + e.getMessage(), e);
        }
    }

    private String normalizeBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String escapeYaml(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private boolean matchesSummary(Map<String, Object> summary, String queryKey) {
        StringBuilder builder = new StringBuilder();
        builder.append(summary.getOrDefault("projectId", "")).append(' ');
        builder.append(summary.getOrDefault("projectName", "")).append(' ');
        builder.append(summary.getOrDefault("sourceFileName", "")).append(' ');
        builder.append(summary.getOrDefault("manifestSourceFileName", "")).append(' ');
        for (String name : stringList(summary.get("sourceFileNames"))) {
            builder.append(name).append(' ');
        }
        Object chapters = summary.get("chapters");
        if (chapters instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    builder.append(stringValue(map.get("title"))).append(' ');
                }
            }
        }
        return searchKey(builder.toString()).contains(queryKey);
    }

    private String searchKey(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}《》「」『』（）【】\\[\\]・·]+", "");
    }

    private String fileBaseName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "";
        }
        String name = Paths.get(fileName).getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private Object firstNonBlank(Object first, Object second) {
        return stringValue(first).isBlank() ? second : first;
    }

    private long reusableRank(Map<String, Object> summary) {
        int polishedRank = Math.max(intValue(summary.get("fullPolishedChapters")), intValue(summary.get("fullPolishedCount")));
        int translatedRank = Math.max(intValue(summary.get("fullTranslatedChapters")), intValue(summary.get("fullTranslatedCount")));
        return polishedRank * 1_000_000_000L
                + translatedRank * 10_000_000L
                + intValue(summary.get("chapterCount")) * 10_000L
                + longValue(summary.get("lastModified")) / 1_000_000L;
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(this::stringValue).filter(item -> !item.isBlank()).toList();
    }

    private Object jsonValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        return node.asText("");
    }
}

