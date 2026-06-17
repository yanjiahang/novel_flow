package org.novelflow.novel.agent.session;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.alibaba.cloud.ai.graph.agent.hook.messages.MessagesModelHook;
import com.alibaba.cloud.ai.graph.agent.hook.messages.UpdatePolicy;
import org.novelflow.config.NovelAgentMemoryProperties;
import org.novelflow.novel.agent.memory.NovelAgentMemoryRecord;
import org.novelflow.novel.agent.memory.NovelAgentMemoryService;
import org.novelflow.novel.service.NovelProjectService;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class NovelAgentContextHook extends MessagesModelHook {

    private static final String CONTEXT_MARKER = "<!-- NOVELFLOW_AGENT_CONTEXT -->";
    private static final String THREAD_PREFIX = "novelflow-agent-";

    private final NovelAgentSessionService novelAgentSessionService;
    private final NovelProjectService novelProjectService;
    private final NovelAgentMemoryService novelAgentMemoryService;
    private final NovelAgentMemoryProperties novelAgentMemoryProperties;

    public NovelAgentContextHook(
            NovelAgentSessionService novelAgentSessionService,
            NovelProjectService novelProjectService,
            NovelAgentMemoryService novelAgentMemoryService,
            NovelAgentMemoryProperties novelAgentMemoryProperties) {
        this.novelAgentSessionService = novelAgentSessionService;
        this.novelProjectService = novelProjectService;
        this.novelAgentMemoryService = novelAgentMemoryService;
        this.novelAgentMemoryProperties = novelAgentMemoryProperties;
    }

    @Override
    public String getName() {
        return "novel_agent_context";
    }

    @Override
    public HookPosition[] getHookPositions() {
        return new HookPosition[]{HookPosition.BEFORE_MODEL};
    }

    @Override
    public AgentCommand beforeModel(List<Message> messages, RunnableConfig config) {
        String sessionId = config.threadId()
                .map(this::sessionIdFromThreadId)
                .orElse("");
        if (sessionId.isBlank()) {
            return new AgentCommand(messages, UpdatePolicy.REPLACE);
        }

        Map<String, Object> sessionContext = novelAgentSessionService.snapshot(sessionId);
        String contextText = buildContextText(sessionId, sessionContext, latestUserQuery(messages));

        List<Message> updated = new ArrayList<>();
        int insertIndex = 0;
        for (Message message : messages) {
            String text = safe(message.getText());
            if (text.startsWith(CONTEXT_MARKER)) {
                continue;
            }
            updated.add(message);
            if (message instanceof SystemMessage) {
                insertIndex = updated.size();
            }
        }
        updated.add(insertIndex, new SystemMessage(contextText));
        return new AgentCommand(updated, UpdatePolicy.REPLACE);
    }

    private String buildContextText(String sessionId, Map<String, Object> sessionContext, String latestUserQuery) {
        String projectId = stringValue(sessionContext.get("currentNovelProjectId"));
        String sourceFileName = stringValue(sessionContext.get("currentNovelSourceFileName"));
        String lastJobId = stringValue(sessionContext.get("lastTranslationJobId"));
        String lastJobStatus = stringValue(sessionContext.get("lastJobStatus"));
        Map<String, Object> translation = mapValue(sessionContext.get("currentNovelTranslation"));
        Map<String, Object> excerpt = mapValue(sessionContext.get("currentNovelExcerpt"));

        StringBuilder builder = new StringBuilder();
        builder.append(CONTEXT_MARKER).append('\n');
        builder.append("NovelFlow 动态上下文包。该信息由后端 Hook 注入，优先级高于旧聊天记忆，不要向用户逐字复述。\n");
        builder.append("- sessionId: ").append(sessionId).append('\n');
        builder.append("- currentNovelProjectId: ").append(projectId.isBlank() ? "(none)" : projectId).append('\n');
        builder.append("- currentNovelSourceFileName: ").append(sourceFileName.isBlank() ? "(none)" : sourceFileName).append('\n');
        builder.append("- lastTranslationJobId: ").append(lastJobId.isBlank() ? "(none)" : lastJobId).append('\n');
        builder.append("- lastJobStatus: ").append(lastJobStatus.isBlank() ? "(unknown)" : lastJobStatus).append('\n');

        builder.append("- currentNovelTranslation: chapterIndex=")
                .append(intValue(translation.get("chapterIndex")))
                .append(", nextOffset=").append(intValue(translation.get("nextOffset")))
                .append(", hasMore=").append(booleanValue(translation.get("hasMore")))
                .append(", title=").append(stringValue(translation.get("title")))
                .append('\n');
        builder.append("- currentNovelExcerpt: chapterIndex=")
                .append(intValue(excerpt.get("chapterIndex")))
                .append(", startSourceOffset=").append(intValue(excerpt.get("startSourceOffset")))
                .append(", nextSourceOffset=").append(intValue(excerpt.get("nextSourceOffset")))
                .append(", sourceCharLimit=").append(intValue(excerpt.get("sourceCharLimit")))
                .append(", hasMoreSource=").append(booleanValue(excerpt.get("hasMoreSource")))
                .append(", title=").append(stringValue(excerpt.get("title")))
                .append('\n');

        if (!projectId.isBlank() && novelProjectService.projectExists(projectId)) {
            appendProjectSummary(builder, projectId);
        }

        appendLongTermMemory(builder, sessionId, projectId, latestUserQuery);

        builder.append("上下文使用规则：\n");
        builder.append("- 用户未给 projectId 时，优先使用 currentNovelProjectId；如果为空，再用书名/文件名查找已有项目。\n");
        builder.append("- 用户说“继续/下一段/接着”时，若 currentNovelExcerpt.hasMoreSource=true，优先继续源文片段翻译。\n");
        builder.append("- 否则若 currentNovelTranslation.hasMore=true，继续读取当前章节译文。\n");
        builder.append("- 不要因为上下文为空就创建新项目；只有用户明确新建或上传新文件后才创建。\n");
        return builder.toString();
    }

    private void appendLongTermMemory(StringBuilder builder, String sessionId, String projectId, String latestUserQuery) {
        if (!novelAgentMemoryProperties.isEnabled()) {
            return;
        }
        try {
            String query = latestUserQuery.isBlank() ? projectId : latestUserQuery;
            List<NovelAgentMemoryRecord> memories = novelAgentMemoryService.recallForPrompt(
                    sessionId,
                    projectId,
                    query,
                    novelAgentMemoryProperties.getInjectLimit());
            String memoryContext = novelAgentMemoryService.buildInjectedContext(memories);
            if (!memoryContext.isBlank()) {
                builder.append(memoryContext);
            }
        } catch (Exception e) {
            builder.append("长期记忆读取失败：").append(e.getMessage()).append('\n');
        }
    }

    private void appendProjectSummary(StringBuilder builder, String projectId) {
        try {
            Map<String, Object> project = novelProjectService.getProjectSummary(projectId);
            builder.append("当前项目摘要：\n");
            builder.append("- projectName: ").append(stringValue(project.get("projectName"))).append('\n');
            builder.append("- sourceFileName: ").append(stringValue(project.get("sourceFileName"))).append('\n');
            builder.append("- chapterCount: ").append(intValue(project.get("chapterCount"))).append('\n');
            builder.append("- fullTranslatedChapters: ").append(intValue(firstNonNull(project.get("fullTranslatedChapters"), project.get("translatedCount")))).append('\n');
            builder.append("- fullPolishedChapters: ").append(intValue(project.get("fullPolishedChapters"))).append('\n');
            builder.append("- excerptCount: ").append(intValue(project.get("excerptCount"))).append('\n');
            builder.append("- excerptChapterCount: ").append(intValue(project.get("excerptChapterCount"))).append('\n');

            Object chaptersObject = project.get("chapters");
            if (chaptersObject instanceof List<?> chapters && !chapters.isEmpty()) {
                builder.append("章节摘要（前20章）：\n");
                chapters.stream().limit(20).forEach(item -> {
                    if (item instanceof Map<?, ?> chapter) {
                        Map<String, Object> excerptStatus = mapValue(chapter.get("excerptStatus"));
                        builder.append("  - 第").append(intValue(chapter.get("index")))
                                .append("章: ").append(stringValue(chapter.get("title")))
                                .append(", chars=").append(intValue(chapter.get("charCount")))
                                .append(", translated=").append(booleanValue(chapter.get("translated")))
                                .append(", polished=").append(booleanValue(chapter.get("polished")));
                        if (!excerptStatus.isEmpty()) {
                            builder.append(", excerptRanges=").append(intValue(excerptStatus.get("rangeCount")))
                                    .append(", nextExcerptSourceOffset=").append(intValue(excerptStatus.get("nextSourceOffset")))
                                    .append(", excerptReviewStatus=").append(stringValue(excerptStatus.get("reviewStatus")))
                                    .append(", latestExcerptJobStatus=")
                                    .append(stringValue(mapValue(excerptStatus.get("latestJob")).get("status")));
                        }
                        builder
                                .append('\n');
                    }
                });
                if (chapters.size() > 20) {
                    builder.append("  - 其余章节数量: ").append(chapters.size() - 20).append('\n');
                }
            }

            Object excerptChaptersObject = project.get("excerptChapters");
            if (excerptChaptersObject instanceof List<?> excerptChapters && !excerptChapters.isEmpty()) {
                builder.append("片段翻译章节：\n");
                excerptChapters.stream().limit(8).forEach(item -> {
                    if (item instanceof Map<?, ?> excerpt) {
                        builder.append("  - 第").append(intValue(excerpt.get("chapterIndex")))
                                .append("章")
                                .append(": ranges=").append(intValue(excerpt.get("rangeCount")))
                                .append(", nextSourceOffset=").append(intValue(excerpt.get("nextSourceOffset")))
                                .append(", fullSourceChars=").append(intValue(excerpt.get("fullSourceCharCount")))
                                .append(", reviewStatus=").append(stringValue(excerpt.get("reviewStatus")))
                                .append(", latestJobStatus=")
                                .append(stringValue(mapValue(excerpt.get("latestJob")).get("status")))
                                .append('\n');
                    }
                });
            }

            Object excerptsObject = project.get("latestExcerpts");
            if (excerptsObject instanceof List<?> excerpts && !excerpts.isEmpty()) {
                builder.append("最近片段译文：\n");
                excerpts.stream().limit(5).forEach(item -> {
                    if (item instanceof Map<?, ?> excerpt) {
                        builder.append("  - ").append(stringValue(excerpt.get("fileName")))
                                .append(", chars=").append(intValue(excerpt.get("charCount")))
                                .append('\n');
                    }
                });
            }
        } catch (Exception e) {
            builder.append("当前项目摘要读取失败：").append(e.getMessage()).append('\n');
        }
    }

    private String sessionIdFromThreadId(String threadId) {
        String safeThreadId = safe(threadId);
        if (safeThreadId.startsWith(THREAD_PREFIX)) {
            return safeThreadId.substring(THREAD_PREFIX.length());
        }
        return safeThreadId;
    }

    private String latestUserQuery(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (message instanceof UserMessage) {
                return safe(message.getText());
            }
        }
        return "";
    }

    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> source) {
            java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
            source.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        return Map.of();
    }

    private Object firstNonNull(Object first, Object second) {
        return first != null ? first : second;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}

