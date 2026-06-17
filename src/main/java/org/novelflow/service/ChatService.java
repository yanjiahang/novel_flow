package org.novelflow.service;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.store.Store;
import org.novelflow.config.NovelAgentMemoryProperties;
import org.novelflow.agent.tool.DateTimeTools;
import org.novelflow.novel.agent.session.NovelAgentContextHook;
import org.novelflow.novel.agent.tool.NovelMemoryTools;
import org.novelflow.novel.agent.tool.NovelWorkflowTools;
import org.novelflow.novel.agent.session.NovelAgentPersistenceService;
import org.novelflow.novel.agent.session.NovelAgentSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * NovelFlow Supervisor Agent 服务。
 * Agent 只负责理解用户意图和选择工具，实际长任务由 NovelFlow Graph Runtime 执行。
 */
@Service
public class ChatService {

    private static final Logger logger = LoggerFactory.getLogger(ChatService.class);

    private final DateTimeTools dateTimeTools;
    private final NovelWorkflowTools novelWorkflowTools;
    private final NovelMemoryTools novelMemoryTools;
    private final NovelAgentPersistenceService novelAgentPersistenceService;
    private final NovelAgentSessionService novelAgentSessionService;
    private final NovelAgentContextHook novelAgentContextHook;
    private final NovelAgentMemoryProperties novelAgentMemoryProperties;
    private final Store novelFlowAgentStore;
    private final ChatModel chatModel;

    public ChatService(
            DateTimeTools dateTimeTools,
            NovelWorkflowTools novelWorkflowTools,
            NovelMemoryTools novelMemoryTools,
            NovelAgentPersistenceService novelAgentPersistenceService,
            NovelAgentSessionService novelAgentSessionService,
            NovelAgentContextHook novelAgentContextHook,
            NovelAgentMemoryProperties novelAgentMemoryProperties,
            Store novelFlowAgentStore,
            ChatModel chatModel) {
        this.dateTimeTools = dateTimeTools;
        this.novelWorkflowTools = novelWorkflowTools;
        this.novelMemoryTools = novelMemoryTools;
        this.novelAgentPersistenceService = novelAgentPersistenceService;
        this.novelAgentSessionService = novelAgentSessionService;
        this.novelAgentContextHook = novelAgentContextHook;
        this.novelAgentMemoryProperties = novelAgentMemoryProperties;
        this.novelFlowAgentStore = novelFlowAgentStore;
        this.chatModel = chatModel;
    }

    /**
     * 获取标准对话 ChatModel。具体模型由 application.yml 中的 spring.ai.model.chat 配置决定。
     */
    public ChatModel getStandardChatModel() {
        return chatModel;
    }

    /**
     * 构建系统提示词（包含历史消息）
     * @param history 历史消息列表
     * @return 完整的系统提示词
     */
    public String buildSystemPrompt(List<Map<String, String>> history, String sessionId) {
        StringBuilder systemPromptBuilder = new StringBuilder();

        systemPromptBuilder.append("""
                你是 NovelFlow Supervisor Agent，负责调度日语轻小说翻译工作流。

                你的职责：
                1. 理解用户自然语言意图，并选择 NovelFlow 工具执行。
                2. 通过工具创建项目、拆分章节、启动翻译任务、查询进度、查看 Graph checkpoint、维护术语、维护存储一致性、导出译文。
                3. 翻译长任务必须交给 Graph Runtime。你不能直接在对话里翻译整章正文。
                4. 当用户没有提供 projectId 时，先判断当前会话上下文是否已有可用项目；如果没有，使用 findNovelProject 或 listNovelProjects 查找已有项目。
                5. 当用户要求“翻译第 N 章”并希望在聊天里看到译文时，优先使用 translateChapterAndPreview。
                6. 当用户说“前 N 字/只翻译 N 字/翻译一段/试译/片段”时，必须使用 translateChapterExcerpt；这里的 N 是源文字符数，不是输出预览长度。
                7. 只有当用户明确说“后台启动/不用等/只提交任务”时，才使用 startChapterTranslation。
                8. 当用户说“继续/下一段/接着/往下/继续显示下一段译文”时：
                   - 如果 currentNovelExcerpt.hasMoreSource=true 且上一轮是片段翻译，使用 continueChapterExcerptTranslation；
                   - 否则使用 continueCurrentTranslation，不要猜章节号。
                9. 当用户要求查看进度时，优先使用 getTranslationJob、getNovelRuntimeState、getTranslationProgress。
                10. 当用户刚上传文件，并提供 projectId/sourceFileName 时，必须调用 splitNovelProject 完成章节拆分。
                11. 当用户修正译名或术语时，先用 updateNovelTerm 写入项目术语记忆；该工具会同步写入 PostgreSQL project-scope 长期记忆，再建议 retry 或后续从 checkpoint 重跑。
                12. 当工具返回 error 时，直接解释错误和下一步，不要假装任务成功。
                13. 回答要简洁、可操作，保留关键 ID、状态、文件路径和下一步。
                14. 当 translateChapterAndPreview 或 translateChapterExcerpt 返回译文 content 时，展示工具返回的译文正文；不要自行扩写或重复整章。
                15. 当 readTranslationJobOutput、readChapterTranslation 或 continueCurrentTranslation 返回译文 content 时，要展示译文正文，不要只总结。
                16. 当用户说“修复当前《某书名》项目的章节索引/章节拆分/目录”时，如果没有明确 projectId，先用书名调用 findNovelProject；找到已有项目后再调用 splitNovelProject，不能创建新项目。
                17. createNovelProject 只用于用户明确要求新建项目，或页面刚上传文件后给出新的 projectId；不要因为旧对话重启后缺少上下文就新建项目。
                18. 当用户问“当前项目是什么/翻译到哪/每章多少字/之前做过什么”时，调用 getNovelProjectOverview，并按章节和进度清晰说明。
                19. 当用户要求检查/修复/清理 Redis、会话、checkpoint、空项目或存储一致性时，使用 maintainNovelFlowStorage。先用 mode=inspect 或 applyChanges=false 预览；只有用户明确要求执行清理时才传 applyChanges=true。
                20. 当用户明确表达跨会话长期偏好、固定译名、审校规则、工作习惯或“记住/以后都/下次也”时，使用 rememberNovelFlowMemory 写入 PostgreSQL 长期记忆。
                21. 当用户询问“你记得什么/之前我要求过什么/我的译名偏好是什么”或当前任务需要参考过往偏好时，使用 recallNovelFlowMemory。
                22. 不要保存密码、API Key、身份证号等敏感信息到长期记忆；如果用户要求记住敏感信息，应该拒绝保存并说明原因。
                23. 当用户要求“从某个 Graph 历史状态恢复/回滚/重跑/时光旅行”时，先用 getTranslationGraphHistory 找到合适 checkpointId，再用 forkTranslationFromCheckpoint 派生新任务；不要覆盖原任务。
                24. 当 getTranslationJob 或项目概览显示任务状态为 INTERRUPTED 时，说明这是服务停止或进程中断造成的非正常终止；先查 Graph history，再选择终态前的 checkpoint 派生恢复任务，不要直接假装原任务仍在运行。
                25. 当用户要求“查看这次任务译文/查看任务输出/查看 jobId 的译文/查看片段译文/查看恢复任务译文”时，优先使用 readTranslationJobOutput；它只读取已有任务产物，不会启动新翻译。
                26. translateChapterAndPreview 会启动新的整章翻译任务；除非用户明确要求“翻译整章/翻译第 N 章”，不要用它来读取 excerpt 或已有 job 输出。

                可用能力边界：
                - 旧内部文档检索能力已移除；当前只暴露 NovelFlow 翻译工作流工具。
                - 当前 source 文件上传仍通过页面或 /api/novels/projects/{projectId}/source 完成。
                - 当前导出工具支持 Markdown/TXT，EPUB 导出是后续扩展。
                - Graph checkpoint 支持 state/history 查询，也支持通过 forkTranslationFromCheckpoint 从历史 checkpoint 派生新翻译任务继续执行；真正 human-in-the-loop 审批流仍是后续扩展。
                - 长期记忆保存在 PostgreSQL/pgvector 中，并由 Hook 自动按相关性注入；工具可显式保存、检索或删除记忆。

                当用户询问时间时，使用 getCurrentDateTime。

                """);

        systemPromptBuilder.append("""
                当前项目、章节、最近任务、片段续翻位置和项目摘要会由 NovelAgentContextHook 在每次模型调用前动态注入。
                如果动态上下文与旧聊天记忆冲突，以动态上下文为准。

                使用规则：
                - 如果 currentNovelProjectId 非空，默认使用它；如果工具提示该项目不存在，再按书名或文件名查找已有项目。
                - 如果 currentNovelProjectId 为空但用户问题里含书名、文件名或《书名》，先调用 findNovelProject，不要直接创建项目。
                - findNovelProject 返回多个同名项目时，优先选择 translatedCount/polishedCount 更高的项目；如果用户只是修复章节拆分，则选择 chapterCount>0 且 rawFileCount>0 的项目。
                - 如果 currentNovelTranslation.hasMore=true，用户说“继续”时直接从 currentNovelTranslation.nextOffset 继续。
                - 如果 currentNovelTranslation.chapterIndex 非 0，用户说“查看译文/继续译文”默认指这个章节。
                - 如果 currentNovelExcerpt.hasMoreSource=true，用户说“继续翻译/继续下一段源文”默认继续翻译该章节后续源文片段。
                - 如果用户刚上传小说，当前 sourceFileName 非空，优先拆分当前项目。

                """);

        // 添加历史消息
        if (!history.isEmpty()) {
            systemPromptBuilder.append("--- 对话历史 ---\n");
            for (Map<String, String> msg : history) {
                String role = msg.get("role");
                String content = msg.get("content");
                if ("user".equals(role)) {
                    systemPromptBuilder.append("用户: ").append(content).append("\n");
                } else if ("assistant".equals(role)) {
                    systemPromptBuilder.append("助手: ").append(content).append("\n");
                }
            }
            systemPromptBuilder.append("--- 对话历史结束 ---\n\n");
        }
        
        systemPromptBuilder.append("请基于以上会话上下文和对话历史，回答用户的新问题。");
        
        return systemPromptBuilder.toString();
    }

    /**
     * 构建本地方法工具数组。
     */
    public Object[] buildMethodToolsArray() {
        List<Object> tools = new ArrayList<>();
        tools.add(dateTimeTools);
        tools.add(novelWorkflowTools);
        tools.add(novelMemoryTools);
        return tools.toArray();
    }

    /**
     * 记录可用本地工具。
     */
    public void logAvailableTools() {
        logger.info("NovelFlow Supervisor Agent tools: getCurrentDateTime, listNovelProjects, findNovelProject, createNovelProject, splitNovelProject, startChapterTranslation, translateChapterAndPreview, translateChapterExcerpt, continueChapterExcerptTranslation, cancelTranslationJob, retryTranslationJob, getNovelProjectStatus, getNovelProjectOverview, getNovelRuntimeState, getNovelTermMemory, getCurrentNovelSession, continueCurrentTranslation, readChapterTranslation, readTranslationJobOutput, getTranslationJob, getTranslationProgress, getTranslationGraphState, getTranslationGraphHistory, forkTranslationFromCheckpoint, maintainNovelFlowStorage, rememberNovelFlowMemory, recallNovelFlowMemory, forgetNovelFlowMemory, updateNovelTerm, exportNovel");
    }

    /**
     * 创建 ReactAgent
     * @param chatModel 聊天模型
     * @param systemPrompt 系统提示词
     * @return 配置好的 ReactAgent
     */
    public ReactAgent createReactAgent(ChatModel chatModel, String systemPrompt) {
        logger.info("Creating NovelFlow Supervisor Agent: model={}, toolBeans={}",
                chatModel.getClass().getSimpleName(), buildMethodToolsArray().length);
        return ReactAgent.builder()
                .name("novel_flow_supervisor")
                .model(chatModel)
                .systemPrompt(systemPrompt)
                .methodTools(buildMethodToolsArray())
                .hooks(novelAgentContextHook)
                .saver(novelAgentPersistenceService.agentCheckpointSaver())
                .build();
    }

    /**
     * 执行 ReactAgent 对话（非流式）
     * @param agent ReactAgent 实例
     * @param question 用户问题
     * @return AI 回复
     */
    public String executeChat(ReactAgent agent, String question, String sessionId) throws GraphRunnerException {
        RunnableConfig config = agentRunnableConfig(sessionId);
        String threadId = config.threadId().orElse("");
        logger.info("NovelFlow Supervisor Agent CALL: sessionId={}, threadId={}, question={}",
                sessionId, threadId, question);
        var response = agent.call(question, config);
        String answer = response.getText();
        logger.info("NovelFlow Supervisor Agent DONE: sessionId={}, threadId={}, answerChars={}",
                sessionId, threadId, answer.length());
        return answer;
    }

    /**
     * 执行 ReactAgent 流式对话。底层仍然是 Agent/Graph Runtime，不绕过工具、Hook 和记忆。
     */
    public Flux<NodeOutput> streamChat(ReactAgent agent, String question, String sessionId) throws GraphRunnerException {
        RunnableConfig config = agentRunnableConfig(sessionId);
        String threadId = config.threadId().orElse("");
        logger.info("NovelFlow Supervisor Agent STREAM: sessionId={}, threadId={}, question={}",
                sessionId, threadId, question);
        return agent.stream(question, config)
                .doOnComplete(() -> logger.info(
                        "NovelFlow Supervisor Agent STREAM DONE: sessionId={}, threadId={}",
                        sessionId, threadId));
    }

    public RunnableConfig agentRunnableConfig(String sessionId) {
        return RunnableConfig.builder()
                .threadId(novelAgentPersistenceService.agentThreadId(sessionId))
                .addMetadata("user_id", novelAgentMemoryProperties.getUserId())
                .addMetadata("session_id", sessionId)
                .store(novelFlowAgentStore)
                .build();
    }
}

