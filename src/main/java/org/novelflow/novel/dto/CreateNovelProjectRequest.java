package org.novelflow.novel.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateNovelProjectRequest {

    private String projectName;
    private String sourceLanguage = "日语";
    private String targetLanguage = "中文";
}

