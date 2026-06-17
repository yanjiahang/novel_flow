package org.novelflow.novel.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.Map;

@Getter
@Setter
public class NovelGraphSnapshotResponse {

    private String jobId;
    private String checkpointId;
    private String node;
    private String nextNode;
    private String checkpointStore;
    private String checkpointLocation;
    private String checkpointFilePath;
    private Map<String, Object> state;
}

