package org.novelflow.novel.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UploadSourceResponse {

    private String projectId;
    private String fileName;
    private String filePath;
    private long fileSize;
}

