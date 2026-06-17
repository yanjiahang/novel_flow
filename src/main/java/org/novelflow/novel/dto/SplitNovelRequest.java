package org.novelflow.novel.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SplitNovelRequest {

    /**
     * Optional source file name under the project's raw directory.
     * If omitted, the first supported raw file is used.
     */
    private String sourceFileName;
}

