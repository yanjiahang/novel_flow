package org.novelflow.novel.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class NovelProjectStatusResponse {

    private String projectId;
    private String projectPath;
    private boolean exists;
    private int rawFileCount;
    private int chapterCount;
    private int translatedCount;
    private int reviewCount;
    private int polishedCount;
    private int fullTranslatedCount;
    private int fullPolishedCount;
    private int excerptCount;
    private int excerptChapterCount;
    private int reviewJsonCount;
    private String progressFile;
    private String runtimeStateFile;
    private String eventLogFile;
}

