package org.novelflow.novel.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.novelflow.config.NovelTranslationProperties;
import org.novelflow.novel.service.NovelTranslationService.RuntimeChapterPlan;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.function.BooleanSupplier;

@Service
public class NovelTranslationReviewService {

    private static final Logger logger = LoggerFactory.getLogger(NovelTranslationReviewService.class);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final java.util.regex.Pattern KANA = java.util.regex.Pattern.compile("[\\u3040-\\u30ff]");
    private static final java.util.regex.Pattern PROMPT_MARKER = java.util.regex.Pattern.compile("【(?:上文原文|上文译文|待翻译原文|待翻译正文|原文|译文)】");
    private static final java.util.regex.Pattern REVISED_TEXT_FIELD = java.util.regex.Pattern.compile(
            "\"revised_text\"\\s*:\\s*\"(.*?)\"\\s*,\\s*\"resolved_issue_codes\"",
            java.util.regex.Pattern.DOTALL);
    private static final java.util.regex.Pattern POLISHED_TEXT_FIELD = java.util.regex.Pattern.compile(
            "\"polished_text\"\\s*:\\s*\"(.*?)\"\\s*\\}",
            java.util.regex.Pattern.DOTALL);

    private final NovelProjectService novelProjectService;
    private final NovelTranslationTextPostProcessor postProcessor;
    private final NovelTermMemoryService termMemoryService;
    private final NovelTranslationProperties translationProperties;
    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;
    private final NovelTranslationService novelTranslationService;

    public NovelTranslationReviewService(
            NovelProjectService novelProjectService,
            NovelTranslationTextPostProcessor postProcessor,
            NovelTermMemoryService termMemoryService,
            NovelTranslationProperties translationProperties,
            ChatModel chatModel,
            ObjectMapper objectMapper,
            NovelTranslationService novelTranslationService) {
        this.novelProjectService = novelProjectService;
        this.postProcessor = postProcessor;
        this.termMemoryService = termMemoryService;
        this.translationProperties = translationProperties;
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
        this.novelTranslationService = novelTranslationService;
    }

    public ReviewResult reviewChunk(
            RuntimeChapterPlan plan,
            int chunkIndex,
            String sourceText,
            String translatedText) {
        String safeSource = normalize(sourceText);
        String safeTranslated = normalize(translatedText);
        List<ReviewIssue> termIssues = collectLockedTermIssues(plan.projectId(), safeSource, safeTranslated);
        List<ReviewIssue> issues = mergeIssues(collectRuleIssues(sourceText, translatedText), termIssues);
        String promptSource = limitText(safeSource, translationProperties.getReviewMaxChars());
        String promptTranslated = limitText(safeTranslated, translationProperties.getReviewMaxChars());
        String reviewPath = reviewFile(plan, chunkIndex).toAbsolutePath().normalize().toString();
        String summary = issues.isEmpty() ? "规则审校通过" : "规则审校发现 " + issues.size() + " 个问题";
        String llmStatus = "SKIPPED";
        String rawAnalysis = "";

        if (translationProperties.isReviewEnabled()) {
            try {
                LlmReviewResult llmResult = callReviewModel(plan, chunkIndex, promptSource, promptTranslated, issues);
                if (llmResult != null) {
                    llmStatus = llmResult.status();
                    rawAnalysis = llmResult.rawResponse();
                    FilteredIssues filteredIssues = filterConflictingLlmTermIssues(
                            plan.projectId(), safeSource, safeTranslated, llmResult.issues(), termIssues);
                    if (llmResult.summary() != null && !llmResult.summary().isBlank()) {
                        summary = safeLlmSummary(plan.projectId(), safeSource, safeTranslated, llmResult.summary(),
                                filteredIssues.droppedCount());
                    }
                    issues = mergeIssues(issues, filteredIssues.issues());
                    if (!"PASS".equalsIgnoreCase(llmResult.status())
                            && (llmResult.issues() == null || llmResult.issues().isEmpty())) {
                        issues = mergeIssues(issues, List.of(new ReviewIssue(
                                "LLM_REVIEW_UNCERTAIN",
                                "MEDIUM",
                                summary.isBlank() ? "LLM 审校未给出明确问题列表" : summary)));
                    }
                }
            } catch (Exception e) {
                llmStatus = "ERROR";
                summary = "LLM 审校异常，需要人工确认: " + e.getMessage();
                issues = mergeIssues(issues, List.of(new ReviewIssue(
                        "LLM_REVIEW_ERROR",
                        "HIGH",
                        "LLM 审校异常: " + e.getMessage())));
                logger.warn("LLM 审校失败，退回规则审校: projectId={}, chapterIndex={}, chunkIndex={}, error={}",
                        plan.projectId(), plan.chapterIndex(), chunkIndex, e.getMessage());
            }
        }

        boolean needsFix = translationProperties.isFixEnabled() && !issues.isEmpty() && isAutoFixable(issues);
        String status = issues.isEmpty() ? "PASS" : (needsFix ? "NEED_FIX" : "NEED_REVIEW");
        ReviewResult result = new ReviewResult(
                plan.projectId(),
                plan.chapterIndex(),
                chunkIndex,
                status,
                issues,
                reviewPath,
                now(),
                summary,
                needsFix,
                llmStatus,
                rawAnalysis);
        try {
            writeReview(plan, chunkIndex, result);
        } catch (Exception e) {
            logger.warn("写入分段审校结果失败，但审校已完成: projectId={}, chapterIndex={}, chunkIndex={}, error={}",
                    plan.projectId(), plan.chapterIndex(), chunkIndex, e.getMessage());
        }
        logger.info("分段审校完成: title={}, chunk={}/{}, status={}, issues={}, needsFix={}, reviewPath={}",
                plan.title(), chunkIndex, plan.totalChunks(), result.status(), result.issueCount(), result.needsFix(), reviewPath);
        return result;
    }

    public ReviewResult readReviewResult(RuntimeChapterPlan plan, int chunkIndex) {
        Path reviewFile = reviewFile(plan, chunkIndex);
        if (!Files.isRegularFile(reviewFile)) {
            return null;
        }
        try {
            return objectMapper.readValue(reviewFile.toFile(), ReviewResult.class);
        } catch (IOException e) {
            throw new IllegalStateException("读取分段审校结果失败: " + e.getMessage(), e);
        }
    }

    public FixResult fixChunk(
            RuntimeChapterPlan plan,
            int chunkIndex,
            String sourceText,
            String translatedText,
            ReviewResult reviewResult,
            BooleanSupplier cancelRequested) {
        if (plan == null) {
            throw new IllegalArgumentException("章节翻译计划不能为空");
        }
        if (chunkIndex <= 0 || chunkIndex > plan.totalChunks()) {
            throw new IllegalArgumentException("非法分段序号: " + chunkIndex);
        }

        if (isCancelRequested(cancelRequested)) {
            throw new NovelTranslationCancelledException("翻译任务已取消: chapterIndex=" + plan.chapterIndex());
        }

        ReviewResult safeReview = reviewResult;
        if (safeReview == null) {
            safeReview = readReviewResult(plan, chunkIndex);
        }
        if (safeReview == null) {
            safeReview = new ReviewResult(
                    plan.projectId(),
                    plan.chapterIndex(),
                    chunkIndex,
                    "NEED_REVIEW",
                    List.of(),
                    reviewFile(plan, chunkIndex).toAbsolutePath().normalize().toString(),
                    now(),
                    "审校文件缺失",
                    false,
                    "ERROR",
                    "");
        }

        String safeSource = normalize(sourceText);
        String safeTranslated = normalize(translatedText);
        String promptSource = limitText(safeSource, translationProperties.getReviewMaxChars());
        String promptTranslated = limitText(safeTranslated, translationProperties.getReviewMaxChars());
        String termsPrompt = termMemoryService.promptFor(plan.projectId(), safeSource, safeTranslated);
        String revisedText = safeTranslated;
        String summary = "未执行修订";
        String rawResponse = "";
        String fixPath = fixFile(plan, chunkIndex).toAbsolutePath().normalize().toString();

        try {
            LlmFixResult llmResult = callFixModel(plan, chunkIndex, promptSource, promptTranslated, safeReview, termsPrompt);
            if (llmResult != null && llmResult.revisedText() != null && !llmResult.revisedText().isBlank()) {
                revisedText = llmResult.revisedText().strip();
                summary = llmResult.summary() == null || llmResult.summary().isBlank()
                        ? "LLM 修订完成"
                        : llmResult.summary().strip();
                rawResponse = llmResult.rawResponse();
                List<NovelTermMemoryService.TermViolation> termViolations =
                        termMemoryService.validateLockedTerms(plan.projectId(), safeSource, revisedText);
                if (!termViolations.isEmpty()) {
                    summary = "修订结果违反锁定术语，已阻止写入: " + formatTermViolations(termViolations);
                    FixResult blocked = new FixResult(
                            plan.projectId(),
                            plan.chapterIndex(),
                            chunkIndex,
                            "TERM_GUARD_BLOCKED",
                            false,
                            safeTranslated,
                            safeTranslated,
                            summary,
                            fixPath,
                            now(),
                            rawResponse);
                    writeFix(plan, chunkIndex, blocked);
                    logger.warn("分段修订被术语 guard 阻止: title={}, chunk={}/{}, violations={}, fixPath={}",
                            plan.title(), chunkIndex, plan.totalChunks(), termViolations.size(), fixPath);
                    return blocked;
                }
                String cumulativeText = novelTranslationService.rewriteRuntimeChunkResult(plan, chunkIndex, revisedText);
                FixResult result = new FixResult(
                        plan.projectId(),
                        plan.chapterIndex(),
                        chunkIndex,
                        "APPLIED",
                        true,
                        revisedText,
                        cumulativeText,
                        summary,
                        fixPath,
                        now(),
                        rawResponse);
                try {
                    termMemoryService.recordChunkTerms(plan.projectId(), plan.chapterIndex(), chunkIndex, safeSource, revisedText);
                } catch (Exception e) {
                    logger.warn("写入术语记忆失败，但修订已完成: projectId={}, chapterIndex={}, chunkIndex={}, error={}",
                            plan.projectId(), plan.chapterIndex(), chunkIndex, e.getMessage());
                }
                try {
                    writeFix(plan, chunkIndex, result);
                } catch (Exception e) {
                    logger.warn("写入分段修订结果失败，但修订已完成: projectId={}, chapterIndex={}, chunkIndex={}, error={}",
                            plan.projectId(), plan.chapterIndex(), chunkIndex, e.getMessage());
                }
                logger.info("分段修订完成: title={}, chunk={}/{}, applied=true, fixPath={}",
                        plan.title(), chunkIndex, plan.totalChunks(), fixPath);
                return result;
            }
            if (llmResult != null) {
                rawResponse = llmResult.rawResponse();
            }
            summary = "模型未返回有效修订内容";
        } catch (Exception e) {
            summary = "修订失败: " + e.getMessage();
            logger.warn("LLM 修订失败，保留原译文: projectId={}, chapterIndex={}, chunkIndex={}, error={}",
                    plan.projectId(), plan.chapterIndex(), chunkIndex, e.getMessage());
        }

        FixResult fallback = new FixResult(
                plan.projectId(),
                plan.chapterIndex(),
                chunkIndex,
                "FALLBACK",
                false,
                revisedText,
                safeTranslated,
                summary,
                fixPath,
                now(),
                rawResponse);
        writeFix(plan, chunkIndex, fallback);
        return fallback;
    }

    public ChapterPolishResult polishChapter(
            RuntimeChapterPlan plan,
            String sourceText,
            String translatedText,
            BooleanSupplier cancelRequested) {
        if (plan == null) {
            throw new IllegalArgumentException("章节翻译计划不能为空");
        }
        if (isCancelRequested(cancelRequested)) {
            throw new NovelTranslationCancelledException("翻译任务已取消: chapterIndex=" + plan.chapterIndex());
        }

        String safeSource = normalize(sourceText);
        if (safeSource.isBlank()) {
            safeSource = novelTranslationService.runtimeChapterSourceText(plan);
        }
        String safeTranslated = normalize(translatedText);
        String reviewPath = chapterReviewFile(plan).toAbsolutePath().normalize().toString();
        List<ReviewIssue> termIssues = collectLockedTermIssues(plan.projectId(), safeSource, safeTranslated);
        List<ReviewIssue> issues = mergeIssues(collectRuleIssues(safeSource, safeTranslated), termIssues);
        String status = issues.isEmpty() ? "PASS" : "NEED_REVIEW";
        String summary = issues.isEmpty() ? "章节规则审校通过" : "章节规则审校发现 " + issues.size() + " 个问题";
        String rawResponse = "";
        String polishedText = safeTranslated;
        boolean applied = false;

        if (!translationProperties.isReviewEnabled() || isReviewMode("none")) {
            ChapterPolishResult skipped = new ChapterPolishResult(
                    plan.projectId(),
                    plan.chapterIndex(),
                    "SKIPPED",
                    false,
                    safeTranslated,
                    safeTranslated,
                    "章节审校已关闭",
                    List.of(),
                    reviewPath,
                    now(),
                    "");
            writeChapterReview(plan, skipped);
            return skipped;
        }

        try {
            LlmChapterPolishResult llmResult = callChapterPolishModel(plan, safeSource, safeTranslated, issues);
            rawResponse = llmResult.rawResponse();
            FilteredIssues filteredIssues = filterConflictingLlmTermIssues(
                    plan.projectId(), safeSource, safeTranslated, llmResult.issues(), termIssues);
            if (llmResult.summary() != null && !llmResult.summary().isBlank()) {
                summary = safeLlmSummary(plan.projectId(), safeSource, safeTranslated, llmResult.summary(),
                        filteredIssues.droppedCount());
            }
            List<ReviewIssue> llmRemainingIssues = filteredIssues.issues();
            String candidate = postProcessor.process(llmResult.polishedText());
            if (candidate == null || candidate.isBlank()) {
                issues = mergeIssues(issues, List.of(new ReviewIssue(
                        "CHAPTER_POLISH_EMPTY",
                        "HIGH",
                        "章节审校模型未返回有效 polished_text")));
                status = "NEED_REVIEW";
            } else if (!isPlausibleChapterPolish(safeTranslated, candidate)) {
                issues = mergeIssues(issues, List.of(new ReviewIssue(
                        "CHAPTER_POLISH_LENGTH_ANOMALY",
                        "HIGH",
                        "章节润色结果长度异常，已阻止写入: before=" + safeTranslated.length()
                                + ", after=" + candidate.length())));
                status = "NEED_REVIEW";
            } else {
                issues = mergeIssues(collectRuleIssues(safeSource, candidate), llmRemainingIssues);
                List<NovelTermMemoryService.TermViolation> violations =
                        termMemoryService.validateLockedTerms(plan.projectId(), safeSource, candidate);
                if (!violations.isEmpty()) {
                    List<ReviewIssue> violationIssues = new ArrayList<>();
                    for (NovelTermMemoryService.TermViolation violation : violations) {
                        violationIssues.add(new ReviewIssue(violation.code(), "HIGH", violation.message()));
                    }
                    issues = mergeIssues(issues, violationIssues);
                    status = "NEED_REVIEW";
                    summary = "章节润色结果违反锁定术语，已阻止写入: " + formatTermViolations(violations);
                } else {
                    polishedText = candidate.strip();
                    applied = !Objects.equals(polishedText, safeTranslated);
                    status = normalizeChapterStatus(llmResult.status(), applied, issues);
                }
            }
        } catch (Exception e) {
            status = "NEED_REVIEW";
            summary = "章节审校异常，需要人工确认: " + e.getMessage();
            issues = mergeIssues(issues, List.of(new ReviewIssue(
                    "CHAPTER_REVIEW_ERROR",
                    "HIGH",
                    "章节审校异常: " + e.getMessage())));
            logger.warn("章节审校失败，保留原译文: projectId={}, chapterIndex={}, error={}",
                    plan.projectId(), plan.chapterIndex(), e.getMessage());
        }

        if (applied && summary.startsWith("LLM 审校包含与锁定术语冲突")) {
            summary = issues.isEmpty()
                    ? "章节总审校已应用润色，无遗留问题"
                    : hasBlockingIssues(issues)
                    ? "章节总审校已应用润色，但仍存在需要人工确认的问题"
                    : "章节总审校已应用润色，剩余问题均为低风险提示";
        }

        ChapterPolishResult result = new ChapterPolishResult(
                plan.projectId(),
                plan.chapterIndex(),
                status,
                applied,
                polishedText,
                safeTranslated,
                summary,
                issues,
                reviewPath,
                now(),
                rawResponse);
        writeChapterReview(plan, result);
        logger.info("章节审校完成: title={}, status={}, applied={}, issues={}, polishedChars={}, reviewPath={}",
                plan.title(), result.status(), result.applied(), result.issueCount(),
                result.polishedText() == null ? 0 : result.polishedText().length(), reviewPath);
        return result;
    }

    private boolean isReviewMode(String expectedMode) {
        String mode = normalize(translationProperties.getReviewMode()).toLowerCase();
        return expectedMode.equals(mode);
    }

    private boolean isPlausibleChapterPolish(String original, String candidate) {
        String safeOriginal = normalize(original);
        String safeCandidate = normalize(candidate);
        if (safeCandidate.isBlank()) {
            return false;
        }
        if (safeOriginal.length() < 500) {
            return true;
        }
        int originalLength = safeOriginal.length();
        int candidateLength = safeCandidate.length();
        return candidateLength >= originalLength * 0.55 && candidateLength <= originalLength * 1.8;
    }

    private String normalizeChapterStatus(String modelStatus, boolean applied, List<ReviewIssue> issues) {
        if (hasBlockingIssues(issues)) {
            return "NEED_REVIEW";
        }
        String normalized = normalize(modelStatus).toUpperCase();
        if ("PASS".equals(normalized) && !applied) {
            return "PASS";
        }
        return "POLISHED";
    }

    private boolean hasBlockingIssues(List<ReviewIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return false;
        }
        for (ReviewIssue issue : issues) {
            if (!isAdvisoryIssue(issue)) {
                return true;
            }
        }
        return false;
    }

    private boolean isAdvisoryIssue(ReviewIssue issue) {
        if (issue == null) {
            return false;
        }
        String code = normalize(issue.code()).toUpperCase();
        String severity = normalize(issue.severity()).toUpperCase();
        return "LOW".equals(severity)
                && (code.equals("TERM_CHECK")
                || code.equals("WORDING_PRECISION")
                || code.equals("STYLE_SUGGESTION"));
    }

    private boolean isAutoFixable(List<ReviewIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return false;
        }
        boolean hasDeterministicIssue = false;
        for (ReviewIssue issue : issues) {
            String code = issue.code() == null ? "" : issue.code();
            if (code.startsWith("LLM_REVIEW_")) {
                return false;
            }
            if (isDeterministicAutoFixCode(code)) {
                hasDeterministicIssue = true;
                continue;
            }
            return false;
        }
        return hasDeterministicIssue;
    }

    private boolean isDeterministicAutoFixCode(String code) {
        String normalized = normalize(code).toUpperCase();
        return normalized.equals("JAPANESE_REMAINS")
                || normalized.equals("PROMPT_MARKER_LEAK")
                || normalized.equals("QUOTE_UNBALANCED")
                || normalized.equals("SUSPICIOUSLY_SHORT")
                || normalized.equals("SUSPICIOUSLY_LONG")
                || normalized.equals("TERM_VARIANT")
                || normalized.equals("CANONICAL_TERM_MISSING")
                || normalized.equals("CANONICAL_TERM_CONFLICT");
    }

    private List<ReviewIssue> collectRuleIssues(String sourceText, String translatedText) {
        List<ReviewIssue> issues = new ArrayList<>();
        String safeSource = normalize(sourceText);
        String safeTranslated = normalize(translatedText);

        int kanaCount = countMatches(KANA, safeTranslated);
        if (kanaCount > 0) {
            issues.add(new ReviewIssue("JAPANESE_REMAINS", "HIGH", "译文中仍有假名残留: " + kanaCount + " 个字符"));
        }

        int promptMarkerCount = countMatches(PROMPT_MARKER, safeTranslated);
        if (promptMarkerCount > 0) {
            issues.add(new ReviewIssue("PROMPT_MARKER_LEAK", "HIGH", "译文中出现 prompt 标签: " + promptMarkerCount + " 处"));
        }

        int openQuoteCount = countChar(safeTranslated, '「');
        int closeQuoteCount = countChar(safeTranslated, '」');
        if (openQuoteCount != closeQuoteCount) {
            issues.add(new ReviewIssue("QUOTE_UNBALANCED", "MEDIUM",
                    "日式引号不平衡: 「=" + openQuoteCount + ", 」=" + closeQuoteCount));
        }

        for (String variant : postProcessor.variantTerms()) {
            int count = countLiteral(safeTranslated, variant);
            if (count > 0) {
                issues.add(new ReviewIssue("TERM_VARIANT", "MEDIUM", "发现非标准译名 `" + variant + "`: " + count + " 处"));
            }
        }

        if (safeSource.length() >= 300 && safeTranslated.length() < safeSource.length() * 0.45) {
            issues.add(new ReviewIssue("SUSPICIOUSLY_SHORT", "MEDIUM",
                    "译文长度明显偏短: source=" + safeSource.length() + ", translated=" + safeTranslated.length()));
        }
        if (safeSource.length() >= 100 && safeTranslated.length() > safeSource.length() * 2.2) {
            issues.add(new ReviewIssue("SUSPICIOUSLY_LONG", "MEDIUM",
                    "译文长度明显偏长，疑似模型扩写或跑题: source=" + safeSource.length() + ", translated=" + safeTranslated.length()));
        }

        return issues;
    }

    private List<ReviewIssue> collectLockedTermIssues(String projectId, String sourceText, String translatedText) {
        List<ReviewIssue> issues = new ArrayList<>();
        for (NovelTermMemoryService.TermViolation violation :
                termMemoryService.validateLockedTerms(projectId, sourceText, translatedText)) {
            String severity = "CANONICAL_TERM_CONFLICT".equals(violation.code()) ? "HIGH" : "MEDIUM";
            issues.add(new ReviewIssue(violation.code(), severity, violation.message()));
        }
        return issues;
    }

    private FilteredIssues filterConflictingLlmTermIssues(
            String projectId,
            String sourceText,
            String translatedText,
            List<ReviewIssue> issues,
            List<ReviewIssue> deterministicTermIssues) {
        if (issues == null || issues.isEmpty()) {
            return new FilteredIssues(List.of(), 0);
        }
        Map<String, String> lockedTerms = termMemoryService.lockedTerms(projectId);
        List<ReviewIssue> filtered = new ArrayList<>();
        int droppedCount = 0;
        for (ReviewIssue issue : issues) {
            if (contradictsLockedTerm(sourceText, translatedText, lockedTerms, issue)
                    || (deterministicTermIssues != null && !deterministicTermIssues.isEmpty()
                    && isLlmTermGuess(issue))
                    || isHallucinatedGlossaryIssue(sourceText, translatedText, lockedTerms, issue)) {
                droppedCount++;
            } else {
                filtered.add(issue);
            }
        }
        return new FilteredIssues(filtered, droppedCount);
    }

    private String safeLlmSummary(
            String projectId,
            String sourceText,
            String translatedText,
            String summary,
            int droppedIssueCount) {
        String safeSummary = normalize(summary);
        if (safeSummary.isBlank()) {
            return safeSummary;
        }
        ReviewIssue summaryIssue = new ReviewIssue("LLM_SUMMARY", "LOW", safeSummary);
        if (droppedIssueCount > 0
                || contradictsLockedTerm(sourceText, translatedText, termMemoryService.lockedTerms(projectId), summaryIssue)
                || isHallucinatedGlossaryIssue(sourceText, translatedText, termMemoryService.lockedTerms(projectId), summaryIssue)) {
            return "LLM 审校包含与锁定术语冲突的判断，已过滤冲突项";
        }
        return safeSummary;
    }

    private boolean contradictsLockedTerm(
            String sourceText,
            String translatedText,
            Map<String, String> lockedTerms,
            ReviewIssue issue) {
        if (issue == null || lockedTerms == null || lockedTerms.isEmpty()) {
            return false;
        }
        String code = normalize(issue.code()).toUpperCase();
        String message = normalize(issue.message());
        if (message.isBlank()) {
            return false;
        }
        if (!isLlmTermGuess(issue) && !code.contains("TERM") && !code.contains("NAME")) {
            return false;
        }
        boolean mentionsLockedTerm = false;
        for (Map.Entry<String, String> term : lockedTerms.entrySet()) {
            if (message.contains(term.getKey()) || message.contains(term.getValue())) {
                mentionsLockedTerm = true;
                break;
            }
        }
        if (!mentionsLockedTerm && !code.contains("TERM") && !code.contains("NAME")) {
            return false;
        }
        for (Map.Entry<String, String> activeTerm : lockedTerms.entrySet()) {
            String source = activeTerm.getKey();
            String target = activeTerm.getValue();
            if (!contains(sourceText, source)) {
                continue;
            }
            if (contains(translatedText, target)
                    && (message.contains(source) || message.contains(target))
                    && isCriticizingExpectedTerm(message)) {
                return true;
            }
            for (Map.Entry<String, String> otherTerm : lockedTerms.entrySet()) {
                if (source.equals(otherTerm.getKey())
                        || contains(sourceText, otherTerm.getKey())) {
                    continue;
                }
                String otherTarget = otherTerm.getValue();
                if (otherTarget != null && otherTarget.length() >= 2 && message.contains(otherTarget)
                        && !Objects.equals(target, otherTarget)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isHallucinatedGlossaryIssue(
            String sourceText,
            String translatedText,
            Map<String, String> lockedTerms,
            ReviewIssue issue) {
        if (!isLlmTermGuess(issue)) {
            return false;
        }
        String message = normalize(issue.message());
        if (!message.contains("术语表") && !message.contains("规定") && !message.contains("统一")) {
            return false;
        }
        if (lockedTerms == null || lockedTerms.isEmpty()) {
            return true;
        }
        for (Map.Entry<String, String> term : lockedTerms.entrySet()) {
            if (contains(sourceText, term.getKey()) || contains(translatedText, term.getValue())) {
                if (message.contains(term.getKey()) || message.contains(term.getValue())) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean isCriticizingExpectedTerm(String message) {
        return message.contains("误译")
                || message.contains("通用词")
                || message.contains("错误")
                || message.contains("冲突")
                || message.contains("不符")
                || message.contains("应该")
                || message.contains("应统一")
                || message.contains("必须")
                || message.contains("指定");
    }

    private boolean isLlmTermGuess(ReviewIssue issue) {
        if (issue == null) {
            return false;
        }
        String code = normalize(issue.code()).toUpperCase();
        String message = normalize(issue.message());
        if (message.isBlank()) {
            return false;
        }
        return code.contains("TERM")
                || code.contains("NAME")
                || message.contains("角色名")
                || message.contains("主角名")
                || message.contains("译名")
                || message.contains("术语表")
                || message.contains("应统一")
                || message.contains("必须统一");
    }

    private List<ReviewIssue> mergeIssues(List<ReviewIssue> left, List<ReviewIssue> right) {
        Map<String, ReviewIssue> merged = new LinkedHashMap<>();
        if (left != null) {
            for (ReviewIssue issue : left) {
                merged.put(issue.code() + "|" + issue.message(), issue);
            }
        }
        if (right != null) {
            for (ReviewIssue issue : right) {
                merged.put(issue.code() + "|" + issue.message(), issue);
            }
        }
        return new ArrayList<>(merged.values());
    }

    private LlmReviewResult callReviewModel(
            RuntimeChapterPlan plan,
            int chunkIndex,
            String sourceText,
            String translatedText,
            List<ReviewIssue> ruleIssues) {
        String termsPrompt = termMemoryService.promptFor(plan.projectId(), sourceText, translatedText);
        String systemPrompt = """
                你是日语轻小说译文审校员，只做审校，不要重写正文。
                你的任务是检查译文是否存在漏译、残留日文、术语冲突、语气失真、指代混乱、段落错位、明显的长度异常。
                项目术语提示是硬约束：如果术语表中已有 source => target，你必须按 target 判断，不能建议改成其他角色名或近似读音。
                如果你认为术语表可能有问题，也只能报告 NEED_REVIEW，不能自行指定新译名。
                只输出严格 JSON，不要输出 markdown、注释、解释或代码块。
                所有字符串字段都必须是合法 JSON 字符串；如果需要换行，请使用 \\n，不要输出原始换行。
                字符串字段不要直接引用大段原文或译文，不要在字符串里使用未转义的双引号。

                JSON 格式：
                {
                  "status": "PASS|NEED_REVIEW",
                  "summary": "简短中文总结",
                  "needs_fix": true,
                  "issues": [
                    {
                      "code": "STRING",
                      "severity": "LOW|MEDIUM|HIGH",
                      "message": "STRING"
                    }
                  ]
                }
                """;
        String userPrompt = """
                请审校下面这一段日语轻小说译文。

                项目术语提示：
                %s

                规则审校已发现的问题：
                %s

                原文：
                %s

                译文：
                %s
                """.formatted(
                termsPrompt.isBlank() ? "（无）" : termsPrompt,
                formatIssues(ruleIssues),
                sourceText,
                translatedText);

        JsonModelResult modelResult = callJsonModel(systemPrompt, userPrompt);
        if (modelResult.root() == null) {
            return new LlmReviewResult(
                    modelResult.status(),
                    "LLM 审校未返回有效结构化结果，需要人工确认",
                    List.of(new ReviewIssue(
                            "LLM_REVIEW_UNSTRUCTURED",
                            "MEDIUM",
                            modelResult.errorMessage().isBlank()
                                    ? "LLM 审校输出为空或无法解析"
                                    : modelResult.errorMessage())),
                    modelResult.rawText());
        }

        JsonNode root = modelResult.root();
        String status = root.path("status").asText("NEED_REVIEW");
        String summary = root.path("summary").asText("");
        List<ReviewIssue> issues = parseIssues(root.path("issues"));
        return new LlmReviewResult(status, summary, issues, modelResult.rawText());
    }

    private LlmFixResult callFixModel(
            RuntimeChapterPlan plan,
            int chunkIndex,
            String sourceText,
            String translatedText,
            ReviewResult reviewResult,
            String termsPrompt) {
        String systemPrompt = """
                你是日语轻小说译文修订员。
                你的任务是根据原文、当前译文、审校问题与术语提示，输出修订后的完整中文译文。
                项目术语提示是硬约束：修订时必须保留 source => target 的固定译名，不能把一个角色名改成另一个角色名。
                对未出现在原文中的角色名，不要因为上下文推测而添加到修订译文中。
                只输出严格 JSON，不要输出 markdown、注释、解释或代码块。
                所有字符串字段都必须是合法 JSON 字符串；如果需要换行，请使用 \\n，不要输出原始换行。
                summary 字段不要直接引用大段原文或译文，不要在字符串里使用未转义的双引号。

                JSON 格式：
                {
                  "summary": "简短中文总结",
                  "revised_text": "修订后的译文正文",
                  "resolved_issue_codes": ["CODE1", "CODE2"]
                }
                """;
        String userPrompt = """
                请修订下面这一段日语轻小说译文。

                项目术语提示：
                %s

                审校结果：
                %s

                原文：
                %s

                当前译文：
                %s
                """.formatted(
                termsPrompt.isBlank() ? "（无）" : termsPrompt,
                reviewResult == null
                        ? "（无）"
                        : """
                                status=%s
                                summary=%s
                                issues=%s
                                reviewPath=%s
                                """.formatted(
                                reviewResult.status(),
                                normalize(reviewResult.summary()),
                                formatIssues(reviewResult.issues()),
                                reviewResult.reviewPath()),
                sourceText,
                translatedText);

        JsonModelResult modelResult = callJsonModel(systemPrompt, userPrompt);
        if (modelResult.root() == null) {
            String fallbackRevisedText = extractRevisedTextFallback(modelResult.rawText());
            return new LlmFixResult(modelResult.errorMessage(), fallbackRevisedText, modelResult.rawText());
        }
        JsonNode root = modelResult.root();
        String summary = root.path("summary").asText("");
        String revisedText = root.path("revised_text").asText("");
        return new LlmFixResult(summary, revisedText, modelResult.rawText());
    }

    private LlmChapterPolishResult callChapterPolishModel(
            RuntimeChapterPlan plan,
            String sourceText,
            String translatedText,
            List<ReviewIssue> ruleIssues) {
        int maxChars = Math.max(translationProperties.getReviewMaxChars(), translationProperties.getChapterReviewMaxChars());
        if (sourceText.length() > maxChars || translatedText.length() > maxChars) {
            throw new IllegalArgumentException("章节文本超过 chapter-review-max-chars: source="
                    + sourceText.length() + ", translated=" + translatedText.length() + ", max=" + maxChars);
        }

        String termsPrompt = termMemoryService.promptFor(plan.projectId(), sourceText, translatedText);
        String systemPrompt = """
                你是日语轻小说章节总审校与润色 Agent。
                你的任务是基于完整日语原文审校 Sakura 初译，并输出可直接发布的完整中文 Markdown 译文。
                必须修正明显错译、漏译、指代混乱、术语冲突、段落错位、重复段落、编号占位符、残留日文和翻译噪声。
                项目术语提示是硬约束：如果术语表中已有 source => target，你必须按 target 输出，不能建议改成其他角色名或近似读音。
                保留章节标题和自然段落，不要添加剧情，不要解释，不要输出原文，不要输出代码块。
                issues 只列出润色后仍需人工确认的问题；已经修好的问题写进 summary，不要放入 issues。
                只输出严格 JSON，不要输出 markdown、注释、解释或代码块。
                所有字符串字段都必须是合法 JSON 字符串；如果需要换行，请使用 \\n，不要输出原始换行。

                JSON 格式：
                {
                  "status": "PASS|POLISHED|NEED_REVIEW",
                  "summary": "简短中文总结，说明主要修订点",
                  "issues": [
                    {
                      "code": "STRING",
                      "severity": "LOW|MEDIUM|HIGH",
                      "message": "STRING"
                    }
                  ],
                  "polished_text": "完整中文 Markdown 译文"
                }
                """;
        String userPrompt = """
                请对下面这一整章译文做总审校与润色。

                项目：%s
                章节：%d %s

                项目术语提示：
                %s

                规则审校已发现的问题：
                %s

                完整原文：
                %s

                Sakura 初译 Markdown：
                %s
                """.formatted(
                plan.projectId(),
                plan.chapterIndex(),
                plan.title(),
                termsPrompt.isBlank() ? "（无）" : termsPrompt,
                formatIssues(ruleIssues),
                sourceText,
                translatedText);

        JsonModelResult modelResult = callJsonModel(systemPrompt, userPrompt);
        if (modelResult.root() == null) {
            String fallbackPolishedText = extractPolishedTextFallback(modelResult.rawText());
            return new LlmChapterPolishResult(
                    "NEED_REVIEW",
                    modelResult.errorMessage().isBlank()
                            ? "章节审校未返回有效结构化结果"
                            : modelResult.errorMessage(),
                    List.of(new ReviewIssue(
                            "CHAPTER_REVIEW_UNSTRUCTURED",
                            "MEDIUM",
                            modelResult.errorMessage().isBlank()
                                    ? "章节审校输出为空或无法解析"
                                    : modelResult.errorMessage())),
                    fallbackPolishedText,
                    modelResult.rawText());
        }

        JsonNode root = modelResult.root();
        String status = root.path("status").asText("NEED_REVIEW");
        String summary = root.path("summary").asText("");
        List<ReviewIssue> issues = parseIssues(root.path("issues"));
        String polishedText = root.path("polished_text").asText("");
        if (polishedText.isBlank()) {
            polishedText = root.path("revised_text").asText("");
        }
        return new LlmChapterPolishResult(status, summary, issues, polishedText, modelResult.rawText());
    }

    private JsonModelResult callJsonModel(String systemPrompt, String userPrompt) {
        Prompt prompt = new Prompt(
                List.of(
                        new SystemMessage(systemPrompt),
                        new UserMessage(userPrompt)));
        ChatResponse response = chatModel.call(prompt);
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null
                || response.getResult().getOutput().getText() == null) {
            logger.warn("LLM JSON 调用无文本输出: response={}", response);
            return new JsonModelResult(null, "NO_TEXT", "", "LLM 未返回文本");
        }
        String rawText = response.getResult().getOutput().getText().trim();
        if (rawText.isBlank()) {
            logger.warn("LLM JSON 调用返回空文本");
            return new JsonModelResult(null, "BLANK_TEXT", "", "LLM 返回空文本");
        }
        String jsonText = extractJson(rawText);
        try {
            return new JsonModelResult(objectMapper.readTree(jsonText), "PARSED", rawText, "");
        } catch (IOException e) {
            logger.warn("解析 LLM JSON 失败: {}, raw={}", e.getMessage(), StringUtils.abbreviate(rawText, 800));
            return new JsonModelResult(null, "INVALID_JSON", rawText, e.getMessage());
        }
    }

    private String extractRevisedTextFallback(String rawText) {
        String cleaned = stripCodeFence(rawText == null ? "" : rawText.trim());
        if (cleaned.isBlank()) {
            return "";
        }
        java.util.regex.Matcher matcher = REVISED_TEXT_FIELD.matcher(cleaned);
        if (matcher.find()) {
            return unescapeJsonLikeText(matcher.group(1)).strip();
        }
        if (!cleaned.contains("\"revised_text\"") && !cleaned.startsWith("{")) {
            return cleaned.strip();
        }
        return "";
    }

    private String extractPolishedTextFallback(String rawText) {
        String cleaned = stripCodeFence(rawText == null ? "" : rawText.trim());
        if (cleaned.isBlank()) {
            return "";
        }
        java.util.regex.Matcher matcher = POLISHED_TEXT_FIELD.matcher(cleaned);
        if (matcher.find()) {
            return unescapeJsonLikeText(matcher.group(1)).strip();
        }
        if (!cleaned.contains("\"polished_text\"") && !cleaned.startsWith("{")) {
            return cleaned.strip();
        }
        return "";
    }

    private String extractJson(String text) {
        String cleaned = stripCodeFence(text);
        int thinkEnd = cleaned.lastIndexOf("</think>");
        if (thinkEnd >= 0 && thinkEnd + "</think>".length() < cleaned.length()) {
            cleaned = cleaned.substring(thinkEnd + "</think>".length()).trim();
        }
        int jsonPrefix = cleaned.indexOf("JSON:");
        if (jsonPrefix >= 0 && jsonPrefix + "JSON:".length() < cleaned.length()) {
            cleaned = cleaned.substring(jsonPrefix + "JSON:".length()).trim();
        }
        int chineseJsonPrefix = cleaned.indexOf("JSON：");
        if (chineseJsonPrefix >= 0 && chineseJsonPrefix + "JSON：".length() < cleaned.length()) {
            cleaned = cleaned.substring(chineseJsonPrefix + "JSON：".length()).trim();
        }
        int first = cleaned.indexOf('{');
        int last = cleaned.lastIndexOf('}');
        if (first >= 0 && last > first) {
            return cleaned.substring(first, last + 1);
        }
        return cleaned;
    }

    private String stripCodeFence(String text) {
        String cleaned = text == null ? "" : text.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceFirst("^```(?:json)?\\s*", "");
            cleaned = cleaned.replaceFirst("\\s*```$", "");
        }
        return cleaned;
    }

    private String unescapeJsonLikeText(String value) {
        return value
                .replace("\\n", System.lineSeparator())
                .replace("\\r", "")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }

    private String formatTermViolations(List<NovelTermMemoryService.TermViolation> violations) {
        if (violations == null || violations.isEmpty()) {
            return "";
        }
        StringJoiner joiner = new StringJoiner("; ");
        for (NovelTermMemoryService.TermViolation violation : violations) {
            joiner.add(violation.message());
        }
        return joiner.toString();
    }

    private void writeReview(RuntimeChapterPlan plan, int chunkIndex, ReviewResult result) {
        try {
            Path reviewFile = reviewFile(plan, chunkIndex);
            Files.createDirectories(reviewFile.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(reviewFile.toFile(), result);
        } catch (IOException e) {
            throw new IllegalStateException("写入分段审校结果失败: " + e.getMessage(), e);
        }
    }

    private void writeFix(RuntimeChapterPlan plan, int chunkIndex, FixResult result) {
        try {
            Path fixFile = fixFile(plan, chunkIndex);
            Files.createDirectories(fixFile.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(fixFile.toFile(), result);
        } catch (IOException e) {
            throw new IllegalStateException("写入分段修订结果失败: " + e.getMessage(), e);
        }
    }

    private void writeChapterReview(RuntimeChapterPlan plan, ChapterPolishResult result) {
        try {
            Path reviewFile = chapterReviewFile(plan);
            Files.createDirectories(reviewFile.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(reviewFile.toFile(), result);
        } catch (IOException e) {
            throw new IllegalStateException("写入章节审校结果失败: " + e.getMessage(), e);
        }
    }

    private Path reviewFile(RuntimeChapterPlan plan, int chunkIndex) {
        Path projectDir = novelProjectService.resolveProjectDir(plan.projectId());
        return projectDir.resolve("review")
                .resolve("chunks")
                .resolve(stripExtension(plan.sourceFileName()))
                .resolve("part-%03d.review.json".formatted(chunkIndex))
                .normalize();
    }

    private Path chapterReviewFile(RuntimeChapterPlan plan) {
        Path projectDir = novelProjectService.resolveProjectDir(plan.projectId());
        return projectDir.resolve("review")
                .resolve("chapters")
                .resolve(stripExtension(plan.sourceFileName()) + ".chapter-review.json")
                .normalize();
    }

    private Path fixFile(RuntimeChapterPlan plan, int chunkIndex) {
        Path projectDir = novelProjectService.resolveProjectDir(plan.projectId());
        return projectDir.resolve("review")
                .resolve("chunks")
                .resolve(stripExtension(plan.sourceFileName()))
                .resolve("part-%03d.fix.json".formatted(chunkIndex))
                .normalize();
    }

    private List<ReviewIssue> parseIssues(JsonNode node) {
        List<ReviewIssue> issues = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return issues;
        }
        for (JsonNode issueNode : node) {
            String code = issueNode.path("code").asText("");
            String severity = issueNode.path("severity").asText("MEDIUM");
            String message = issueNode.path("message").asText("");
            if (!code.isBlank() || !message.isBlank()) {
                issues.add(new ReviewIssue(code, severity, message));
            }
        }
        return issues;
    }

    private String formatIssues(List<ReviewIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return "（无）";
        }
        StringJoiner joiner = new StringJoiner(System.lineSeparator());
        for (ReviewIssue issue : issues) {
            joiner.add("- [" + issue.severity() + "] " + issue.code() + ": " + issue.message());
        }
        return joiner.toString();
    }

    private int countMatches(java.util.regex.Pattern pattern, String value) {
        return (int) pattern.matcher(value).results().count();
    }

    private int countLiteral(String value, String literal) {
        int count = 0;
        int index = value.indexOf(literal);
        while (index >= 0) {
            count++;
            index = value.indexOf(literal, index + literal.length());
        }
        return count;
    }

    private boolean contains(String value, String term) {
        return value != null && term != null && !term.isBlank() && value.contains(term);
    }

    private int countChar(String value, char ch) {
        int count = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == ch) {
                count++;
            }
        }
        return count;
    }

    private String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String baseName = dot > 0 ? fileName.substring(0, dot) : fileName;
        return baseName.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private boolean isCancelRequested(BooleanSupplier cancelRequested) {
        return cancelRequested != null && cancelRequested.getAsBoolean();
    }

    private String normalize(String value) {
        return value == null ? "" : value.strip();
    }

    private String limitText(String value, int maxChars) {
        String safe = normalize(value);
        if (maxChars <= 0 || safe.length() <= maxChars) {
            return safe;
        }
        return safe.substring(0, maxChars);
    }

    private String now() {
        return LocalDateTime.now().format(TIME_FORMATTER);
    }

    public record ReviewResult(
            String projectId,
            int chapterIndex,
            int chunkIndex,
            String status,
            List<ReviewIssue> issues,
            String reviewPath,
            String updatedAt,
            String summary,
            boolean needsFix,
            String llmStatus,
            String rawAnalysis) {

        public int issueCount() {
            return issues == null ? 0 : issues.size();
        }
    }

    public record ReviewIssue(String code, String severity, String message) {
    }

    public record FixResult(
            String projectId,
            int chapterIndex,
            int chunkIndex,
            String status,
            boolean applied,
            String revisedText,
            String translatedText,
            String summary,
            String fixPath,
            String updatedAt,
            String rawResponse) {
    }

    public record ChapterPolishResult(
            String projectId,
            int chapterIndex,
            String status,
            boolean applied,
            String polishedText,
            String originalText,
            String summary,
            List<ReviewIssue> issues,
            String reviewPath,
            String updatedAt,
            String rawResponse) {

        public int issueCount() {
            return issues == null ? 0 : issues.size();
        }
    }

    private record LlmReviewResult(String status, String summary, List<ReviewIssue> issues, String rawResponse) {
    }

    private record LlmFixResult(String summary, String revisedText, String rawResponse) {
    }

    private record LlmChapterPolishResult(
            String status,
            String summary,
            List<ReviewIssue> issues,
            String polishedText,
            String rawResponse) {
    }

    private record FilteredIssues(List<ReviewIssue> issues, int droppedCount) {
    }

    private record JsonModelResult(JsonNode root, String status, String rawText, String errorMessage) {
    }
}

