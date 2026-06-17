package org.novelflow.novel.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class StartTranslationJobRequest {

    private int chapterIndex = 1;
    private int sourceStartOffset = 0;
    private int sourceCharLimit = 0;
}

