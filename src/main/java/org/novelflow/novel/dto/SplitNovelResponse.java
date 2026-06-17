package org.novelflow.novel.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class SplitNovelResponse {

    private String projectId;
    private String sourceFileName;
    private int totalChapters;
    private List<ChapterInfo> chapters = new ArrayList<>();
}

