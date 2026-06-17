package org.novelflow.novel.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TranslationProgressResponse {

    private String projectId;
    private int chapterIndex;
    private String title;
    private String status;
    private String outputFileName;
    private String outputPath;
    private String chunkDirectory;
    private int sourceCharCount;
    private int translatedCharCount;
    private int totalChunks;
    private int completedChunks;
    private String latestChunkFileName;
    private String latestChunkPath;
    private String latestChunkPreview;
    private String updatedAt;
}

