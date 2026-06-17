package org.novelflow.novel.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class NovelProjectResponse {

    private String projectId;
    private String projectName;
    private String projectPath;
    private String sourceLanguage;
    private String targetLanguage;
}

