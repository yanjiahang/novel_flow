package org.novelflow.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "novel.agent.memory")
public class NovelAgentMemoryProperties {

    /**
     * Enable PostgreSQL-backed long-term memory.
     */
    private boolean enabled = true;

    /**
     * Local single-user identifier. It gives memories a stable cross-session namespace.
     */
    private String userId = "local-user";

    /**
     * pgvector dimension. Keep this aligned with the DashScope embedding model options.
     */
    private int embeddingDimensions = 1536;

    /**
     * Number of memories injected into the Agent context before a model call.
     */
    private int injectLimit = 6;

    /**
     * Number of memories returned by the explicit recall tool by default.
     */
    private int searchLimit = 8;

    /**
     * Similarity threshold used to update a near-duplicate memory instead of inserting a new one.
     */
    private double duplicateScoreThreshold = 0.92;
}
