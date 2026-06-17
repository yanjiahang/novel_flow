package org.novelflow.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "novel")
public class NovelProperties {

    /**
     * Root directory for all novel workflow projects.
     */
    private String workspace = "./novel-projects";

    /**
     * Source files accepted by the novel workflow upload endpoint.
     */
    private String allowedExtensions = "txt,md,epub";
}

