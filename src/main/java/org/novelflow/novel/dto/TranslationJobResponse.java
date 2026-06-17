package org.novelflow.novel.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TranslationJobResponse {

    private String jobId;
    private String projectId;
    private int chapterIndex;
    private String status;
    private String currentNode;
    private String message;
    private String errorMessage;
    private String jobType;
    private String title;
    private String sourceFileName;
    private int sourceStartOffset;
    private int sourceEndOffset;
    private int sourceCharLimit;
    private int sourceCharCount;
    private int fullSourceCharCount;
    private int currentChunkIndex;
    private int completedChunks;
    private int totalChunks;
    private String createdAt;
    private String startedAt;
    private String updatedAt;
    private String completedAt;
    private String jobFilePath;
    private String progressFilePath;
    private String outputPath;
    private String translationModel;
    private String forkedFromJobId;
    private String forkedFromCheckpointId;
    private String restoredCheckpointId;
}

