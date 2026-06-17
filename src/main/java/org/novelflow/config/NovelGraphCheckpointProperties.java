package org.novelflow.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "novel.graph.checkpoint")
public class NovelGraphCheckpointProperties {

    /**
     * Checkpoint saver implementation: redis or file.
     */
    private String saver = "file";

    private Redis redis = new Redis();

    @Getter
    @Setter
    public static class Redis {

        private String address = "redis://localhost:6379";

        private String password = "";

        private int database = 0;

        private int connectionPoolSize = 16;

        private int connectionMinimumIdleSize = 4;

        private int timeoutMs = 3000;
    }
}

