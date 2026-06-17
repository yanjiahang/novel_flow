package org.novelflow.novel.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.novelflow.config.NovelTranslationProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Service
public class OllamaNovelTranslationClient {

    private static final String SYSTEM_PROMPT = "你是一个轻小说翻译模型，可以流畅通顺地以日本轻小说的风格将日文翻译成简体中文，并联系上下文正确使用人称代词，不擅自添加原文中没有的代词。只输出译文正文，不输出标签、说明或分析。";

    private final NovelTranslationProperties properties;
    private final ObjectMapper objectMapper;
    private final NovelTranslationTextPostProcessor textPostProcessor;
    private final HttpClient httpClient;

    public OllamaNovelTranslationClient(
            NovelTranslationProperties properties,
            ObjectMapper objectMapper,
            NovelTranslationTextPostProcessor textPostProcessor) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.textPostProcessor = textPostProcessor;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getTimeoutMs()))
                .build();
    }

    public String translate(String sourceText) {
        return translate(sourceText, "", "", "");
    }

    public String translate(String sourceText, String previousSourceText, String previousTranslatedText) {
        return translate(sourceText, previousSourceText, previousTranslatedText, "");
    }

    public String translate(String sourceText, String previousSourceText, String previousTranslatedText, String additionalGlossaryPrompt) {
        if (sourceText == null || sourceText.isBlank()) {
            return "";
        }

        try {
            String body = objectMapper.writeValueAsString(buildRequest(
                    sourceText,
                    previousSourceText,
                    previousTranslatedText,
                    additionalGlossaryPrompt));
            HttpRequest request = HttpRequest.newBuilder(translationUri())
                    .timeout(Duration.ofMillis(properties.getTimeoutMs()))
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Ollama 翻译请求失败: HTTP " + response.statusCode() + ", " + response.body());
            }

            JsonNode root = objectMapper.readTree(response.body());
            String content = root.path("message").path("content").asText("");
            if (content.isBlank()) {
                throw new IllegalStateException("Ollama 翻译结果为空: " + response.body());
            }
            return content.strip();
        } catch (IOException e) {
            throw new IllegalStateException("Ollama 翻译请求失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Ollama 翻译请求被中断", e);
        }
    }

    public String modelName() {
        return properties.getModel();
    }

    private Map<String, Object> buildRequest(
            String sourceText,
            String previousSourceText,
            String previousTranslatedText,
            String additionalGlossaryPrompt) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("temperature", properties.getTemperature());
        options.put("top_p", properties.getTopP());
        options.put("num_ctx", properties.getNumCtx());
        options.put("num_gpu", properties.getNumGpu());
        options.put("num_predict", properties.getNumPredict());

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", properties.getModel());
        request.put("stream", false);
        request.put("options", options);
        request.put("messages", List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user", "content", buildUserPrompt(
                        sourceText,
                        previousSourceText,
                        previousTranslatedText,
                        additionalGlossaryPrompt))
        ));
        return request;
    }

    private String buildUserPrompt(
            String sourceText,
            String previousSourceText,
            String previousTranslatedText,
            String additionalGlossaryPrompt) {
        boolean hasContext = previousSourceText != null && !previousSourceText.isBlank()
                && previousTranslatedText != null && !previousTranslatedText.isBlank();
        String glossary = mergeGlossaries(additionalGlossaryPrompt);
        if (!hasContext) {
            if (glossary.isBlank()) {
                return "将下面的日文文本翻译成中文：" + sourceText;
            }
            return """
                    要求：
                    - 只输出译文正文。
                    - 不要输出“原文”“译文”“上文译文”等标签。
                    - 专有名词必须遵守术语表。

                    根据以下术语表（可以为空）：
                    %s

                    将下面的日文文本根据对应关系和备注翻译成中文：
                    %s
                    """.formatted(glossary, sourceText);
        }

        if (glossary.isBlank()) {
            return """
                    要求：
                    - 只输出待翻译正文的中文译文。
                    - 上文信息只用于保持人称、称呼和语气一致，不属于待翻译正文。
                    - 不要重复上文译文。
                    - 不要输出“原文”“译文”“上文译文”等标签。

                    上文信息：
                    上文原文：
                    %s

                    上文译文：
                    %s

                    将下面的日文文本翻译成中文：
                    %s
                    """.formatted(previousSourceText.strip(), previousTranslatedText.strip(), sourceText);
        }

        return """
                要求：
                - 只输出待翻译正文的中文译文。
                - 上文信息只用于保持人称、称呼、语气和专有名词一致，不属于待翻译正文。
                - 不要重复上文译文。
                - 不要输出“原文”“译文”“上文译文”等标签。
                - 专有名词必须遵守术语表。

                根据以下术语表（可以为空）：
                %s

                上文信息：
                上文原文：
                %s

                上文译文：
                %s

                将下面的日文文本根据对应关系和备注翻译成中文：
                %s
                """.formatted(glossary, previousSourceText.strip(), previousTranslatedText.strip(), sourceText);
    }

    private String mergeGlossaries(String additionalGlossaryPrompt) {
        String baseGlossary = textPostProcessor.glossaryPrompt();
        LinkedHashSet<String> lines = new LinkedHashSet<>();
        addGlossaryLines(lines, baseGlossary);
        addGlossaryLines(lines, additionalGlossaryPrompt);
        return String.join(System.lineSeparator(), lines).strip();
    }

    private void addGlossaryLines(LinkedHashSet<String> lines, String glossaryText) {
        if (glossaryText == null || glossaryText.isBlank()) {
            return;
        }
        for (String line : glossaryText.strip().split("\\R")) {
            String normalizedLine = line.strip();
            if (!normalizedLine.isBlank()) {
                lines.add(normalizedLine);
            }
        }
    }

    private URI translationUri() {
        String baseUrl = properties.getBaseUrl();
        String normalizedBaseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return URI.create(normalizedBaseUrl + "/api/chat");
    }
}

