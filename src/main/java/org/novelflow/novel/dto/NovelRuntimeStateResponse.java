package org.novelflow.novel.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.LinkedHashMap;
import java.util.Map;

@Getter
@Setter
public class NovelRuntimeStateResponse {

    private String projectId;
    private String status;
    private String currentNode;
    private String createdAt;
    private String updatedAt;
    private String stateFilePath;
    private Map<String, RuntimeNodeState> nodes = new LinkedHashMap<>();
    private Map<String, Object> metadata = new LinkedHashMap<>();

    @Getter
    @Setter
    public static class RuntimeNodeState {

        private String nodeName;
        private String status;
        private String message;
        private String startedAt;
        private String updatedAt;
        private String completedAt;
        private Map<String, Object> metadata = new LinkedHashMap<>();
    }
}

