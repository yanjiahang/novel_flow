package org.novelflow.novel.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.novelflow.novel.domain.dos.NovelAgentMemoryDO;

import java.util.List;

public interface NovelAgentMemoryMapper extends BaseMapper<NovelAgentMemoryDO> {

    @Insert("""
            INSERT INTO t_novel_agent_memory (
                memory_uuid, user_id, session_id, project_id, namespace, memory_key,
                category, scope_type, content, summary, search_text, metadata,
                embedding, importance, enabled, create_time, update_time
            ) VALUES (
                #{memoryUuid}, #{userId}, #{sessionId}, #{projectId}, #{namespace}, #{memoryKey},
                #{category}, #{scopeType}, #{content}, #{summary}, #{searchText}, CAST(#{metadataJson} AS jsonb),
                CASE
                    WHEN #{embeddingVector} IS NULL OR #{embeddingVector} = '' THEN NULL
                    ELSE CAST(#{embeddingVector} AS vector)
                END,
                #{importance}, #{enabled}, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            )
            ON CONFLICT (user_id, namespace, memory_key)
            DO UPDATE SET
                session_id = EXCLUDED.session_id,
                project_id = EXCLUDED.project_id,
                category = EXCLUDED.category,
                scope_type = EXCLUDED.scope_type,
                content = EXCLUDED.content,
                summary = EXCLUDED.summary,
                search_text = EXCLUDED.search_text,
                metadata = EXCLUDED.metadata,
                embedding = COALESCE(EXCLUDED.embedding, t_novel_agent_memory.embedding),
                importance = EXCLUDED.importance,
                enabled = EXCLUDED.enabled,
                update_time = CURRENT_TIMESTAMP
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int upsertMemory(NovelAgentMemoryDO memory);

    @Update("""
            UPDATE t_novel_agent_memory
            SET session_id = #{sessionId},
                project_id = #{projectId},
                category = #{category},
                scope_type = #{scopeType},
                content = #{content},
                summary = #{summary},
                search_text = #{searchText},
                metadata = CAST(#{metadataJson} AS jsonb),
                embedding = CASE
                    WHEN #{embeddingVector} IS NULL OR #{embeddingVector} = '' THEN embedding
                    ELSE CAST(#{embeddingVector} AS vector)
                END,
                importance = #{importance},
                enabled = #{enabled},
                update_time = CURRENT_TIMESTAMP
            WHERE memory_uuid = #{memoryUuid}
            """)
    int updateByMemoryUuid(NovelAgentMemoryDO memory);

    @Update("""
            UPDATE t_novel_agent_memory
            SET access_count = access_count + 1,
                last_access_time = CURRENT_TIMESTAMP
            WHERE memory_uuid = #{memoryUuid}
            """)
    int touch(String memoryUuid);

    @Update("""
            UPDATE t_novel_agent_memory
            SET enabled = false,
                update_time = CURRENT_TIMESTAMP
            WHERE user_id = #{userId}
              AND (memory_uuid = #{memoryUuidOrKey} OR memory_key = #{memoryUuidOrKey})
            """)
    int disable(String userId, String memoryUuidOrKey);

    @Select("""
            SELECT id, memory_uuid, user_id, session_id, project_id, namespace, memory_key,
                   category, scope_type, content, summary, search_text, metadata::text AS metadata_json,
                   importance, enabled, access_count, create_time, update_time, last_access_time
            FROM t_novel_agent_memory
            WHERE enabled = true
              AND user_id = #{userId}
            ORDER BY update_time DESC
            LIMIT #{limit}
            """)
    List<NovelAgentMemoryDO> listRecent(String userId, int limit);
}
