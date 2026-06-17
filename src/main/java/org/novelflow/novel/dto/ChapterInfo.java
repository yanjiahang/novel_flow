package org.novelflow.novel.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ChapterInfo {

    private int index;
    private String title;
    private String fileName;
    private int startLine;
    private int endLine;
    private int charCount;
}

