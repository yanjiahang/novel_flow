package org.novelflow.novel.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TranslateChapterResponse {

    private String projectId;
    private int chapterIndex;
    private String title;
    private String sourceFileName;
    private String outputFileName;
    private String outputPath;
    private int sourceCharCount;
    private int translatedCharCount;
    private String preview;
    private int previewCharCount;
    private boolean hasMore;
}

