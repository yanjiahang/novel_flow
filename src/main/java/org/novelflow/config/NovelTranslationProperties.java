package org.novelflow.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "novel.translation")
public class NovelTranslationProperties {

    /**
     * Ollama endpoint used only by the novel translation workflow.
     */
    private String baseUrl = "http://localhost:11434";

    /**
     * Translation-specialized local model.
     */
    private String model = "hf.co/SakuraLLM/Sakura-7B-Qwen2.5-v1.0-GGUF:latest";

    private double temperature = 0.1;

    private double topP = 0.3;

    private int numCtx = 8192;

    /**
     * Ask Ollama to offload as many layers as possible to GPU.
     */
    private int numGpu = 999;

    private int numPredict = 1536;

    private int chunkSize = 1200;

    /**
     * External glossary file. Supports normal filesystem paths and classpath: resources.
     */
    private String glossaryPath = "./config/novel-glossary.json";

    /**
     * Previous source text passed as context for the next chunk.
     */
    private int contextSourceChars = 800;

    /**
     * Previous translated text passed as context for the next chunk.
     */
    private int contextTranslationChars = 800;

    /**
     * Enable general-model review.
     */
    private boolean reviewEnabled = true;

    /**
     * Review strategy: chapter, chunk, both, or none.
     */
    private String reviewMode = "chapter";

    /**
     * Enable automatic chunk revision when review finds fixable issues.
     */
    private boolean fixEnabled = true;

    /**
     * Max chars from source/translation sent to the reviewer or fixer.
     */
    private int reviewMaxChars = 1800;

    /**
     * Max chars from source/translation sent to chapter-level reviewer.
     */
    private int chapterReviewMaxChars = 200000;

    private long timeoutMs = 300000;
}

