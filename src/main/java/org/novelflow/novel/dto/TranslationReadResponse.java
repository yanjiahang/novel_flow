package org.novelflow.novel.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TranslationReadResponse {

    private String projectId;
    private String jobId;
    private String jobType;
    private int chapterIndex;
    private String title;
    private String outputFileName;
    private String outputPath;
    private int sourceStartOffset;
    private int sourceEndOffset;
    private int sourceCharLimit;
    private int sourceCharCount;
    private int fullSourceCharCount;
    private int offset;
    private int nextOffset;
    private int totalChars;
    private boolean hasMore;
    private String content;
}

