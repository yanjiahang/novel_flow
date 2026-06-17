package org.novelflow.novel.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class NovelGraphHistoryResponse {

    private String jobId;
    private String checkpointStore;
    private String checkpointLocation;
    private String checkpointDirectory;
    private int totalSnapshots;
    private List<NovelGraphSnapshotResponse> snapshots = new ArrayList<>();
}

