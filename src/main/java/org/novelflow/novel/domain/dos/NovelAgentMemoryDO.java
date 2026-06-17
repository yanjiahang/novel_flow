package org.novelflow.novel.domain.dos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_novel_agent_memory")
public class NovelAgentMemoryDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String memoryUuid;

    private String userId;

    private String sessionId;

    private String projectId;

    private String namespace;

    private String memoryKey;

    private String category;

    private String scopeType;

    private String content;

    private String summary;

    private String searchText;

    private String metadataJson;

    private Integer importance;

    private Boolean enabled;

    private Integer accessCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private LocalDateTime lastAccessTime;

    @TableField(exist = false)
    private String embeddingVector;
}
