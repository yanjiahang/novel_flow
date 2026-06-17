package org.novelflow.novel.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.novelflow.config.NovelTranslationProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class NovelTranslationTextPostProcessor {

    private static final List<Pattern> PROMPT_MARKER_LINES = List.of(
            Pattern.compile("^\\s*【(?:上文原文|上文译文|待翻译原文|待翻译正文|原文|译文)】\\s*$"),
            Pattern.compile("^\\s*(?:上文原文|上文译文|待翻译原文|待翻译正文)\\s*[:：]\\s*$"),
            Pattern.compile("^\\s*参考上文上下文.*$"),
            Pattern.compile("^\\s*请只(?:翻译|输出).*$")
    );

    private static final List<TermRule> TERM_RULES = List.of(
            new TermRule(Pattern.compile("小霞的孤城|卡加米的孤城|镜子的孤城"), "镜之孤城"),
            new TermRule(Pattern.compile("风歌"), "风香"),
            new TermRule(Pattern.compile("乌列诺"), "乌里诺"),
            new TermRule(Pattern.compile("里恩"), "里昂"),
            new TermRule(Pattern.compile("(?<!大野)狼大人"), "大野狼大人")
    );

    private final NovelTranslationProperties translationProperties;
    private final ObjectMapper objectMapper;

    public NovelTranslationTextPostProcessor(
            NovelTranslationProperties translationProperties,
            ObjectMapper objectMapper) {
        this.translationProperties = translationProperties;
        this.objectMapper = objectMapper;
    }

    public String process(String translatedText) {
        if (translatedText == null || translatedText.isBlank()) {
            return "";
        }
        String cleaned = removePromptMarkers(translatedText);
        cleaned = applyTermRules(cleaned);
        cleaned = repairDanglingOpenQuote(cleaned);
        return cleaned.strip();
    }

    public String glossaryPrompt() {
        StringBuilder builder = new StringBuilder();
        for (GlossaryTerm term : glossaryTerms()) {
            if (term.src() == null || term.src().isBlank() || term.dst() == null || term.dst().isBlank()) {
                continue;
            }
            builder.append(term.src()).append("->").append(term.dst());
            if (term.info() != null && !term.info().isBlank()) {
                builder.append(" #").append(term.info());
            }
            builder.append('\n');
        }
        return builder.toString().strip();
    }

    public Map<String, String> glossary() {
        Map<String, String> terms = new LinkedHashMap<>();
        for (GlossaryTerm term : glossaryTerms()) {
            if (term.src() != null && !term.src().isBlank()
                    && term.dst() != null && !term.dst().isBlank()) {
                terms.put(term.src(), term.dst());
            }
        }
        return terms;
    }

    public Map<String, String> glossaryInfo() {
        Map<String, String> terms = new LinkedHashMap<>();
        for (GlossaryTerm term : glossaryTerms()) {
            if (term.src() != null && !term.src().isBlank()
                    && term.info() != null && !term.info().isBlank()) {
                terms.put(term.src(), term.info());
            }
        }
        return terms;
    }

    public List<String> variantTerms() {
        return List.of("小霞的孤城", "卡加米的孤城", "镜子的孤城", "风歌", "乌列诺", "里恩");
    }

    private String removePromptMarkers(String value) {
        String normalized = value.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder builder = new StringBuilder();
        for (String line : normalized.split("\n", -1)) {
            if (isPromptMarkerLine(line)) {
                continue;
            }
            builder.append(line).append('\n');
        }
        return builder.toString().replaceAll("\\n{4,}", "\n\n\n");
    }

    private boolean isPromptMarkerLine(String line) {
        return PROMPT_MARKER_LINES.stream().anyMatch(pattern -> pattern.matcher(line).matches());
    }

    private String applyTermRules(String value) {
        String result = value;
        for (TermRule rule : TERM_RULES) {
            result = rule.pattern().matcher(result).replaceAll(rule.replacement());
        }
        return result;
    }

    private List<GlossaryTerm> glossaryTerms() {
        Path glossaryPath = resolveGlossaryPath();
        if (!Files.isRegularFile(glossaryPath)) {
            return List.of();
        }
        try (InputStream inputStream = Files.newInputStream(glossaryPath)) {
            JsonNode root = objectMapper.readTree(inputStream);
            if (!root.isArray()) {
                throw new IllegalStateException("术语表必须是 JSON 数组: " + glossaryPath);
            }
            List<GlossaryTerm> terms = new ArrayList<>();
            for (JsonNode node : root) {
                String src = firstText(node, "src", "source");
                String dst = firstText(node, "dst", "target");
                String info = firstText(node, "info", "notes", "note");
                if (src.isBlank() || dst.isBlank()) {
                    continue;
                }
                terms.add(new GlossaryTerm(src, dst, info));
            }
            return terms;
        } catch (IOException e) {
            throw new IllegalStateException("读取术语表失败: " + glossaryPath + ", " + e.getMessage(), e);
        }
    }

    private Path resolveGlossaryPath() {
        String configuredPath = translationProperties.getGlossaryPath();
        if (configuredPath == null || configuredPath.isBlank()) {
            configuredPath = "./config/novel-glossary.json";
        }
        String normalized = configuredPath.strip();
        if (normalized.startsWith("classpath:")) {
            String resourcePath = normalized.substring("classpath:".length());
            try {
                var resource = Thread.currentThread().getContextClassLoader().getResource(resourcePath);
                if (resource == null) {
                    throw new IllegalStateException("classpath 术语表不存在: " + resourcePath);
                }
                return Path.of(resource.toURI()).normalize();
            } catch (Exception e) {
                throw new IllegalStateException("解析 classpath 术语表失败: " + resourcePath + ", " + e.getMessage(), e);
            }
        }
        return Path.of(normalized).toAbsolutePath().normalize();
    }

    private String firstText(JsonNode node, String... fieldNames) {
        if (node == null) {
            return "";
        }
        for (String fieldName : fieldNames) {
            String value = node.path(fieldName).asText("");
            if (!value.isBlank()) {
                return value.strip();
            }
        }
        return "";
    }

    private String repairDanglingOpenQuote(String value) {
        return value.replaceAll("(?m)(」[^\\r\\n]*?)「\\s*$", "$1");
    }

    private record TermRule(Pattern pattern, String replacement) {
    }

    private record GlossaryTerm(String src, String dst, String info) {
    }
}

