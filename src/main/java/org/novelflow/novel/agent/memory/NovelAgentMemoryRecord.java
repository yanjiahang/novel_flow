package org.novelflow.novel.agent.memory;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;
import java.util.Map;

@Value
@Builder
public class NovelAgentMemoryRecord {

    Long id;

    String memoryUuid;

    String userId;

    String sessionId;

    String projectId;

    String namespace;

    String memoryKey;

    String category;

    String scopeType;

    String content;

    String summary;

    Map<String, Object> metadata;

    int importance;

    int accessCount;

    double vectorScore;

    double textScore;

    double score;

    LocalDateTime createTime;

    LocalDateTime updateTime;
}
