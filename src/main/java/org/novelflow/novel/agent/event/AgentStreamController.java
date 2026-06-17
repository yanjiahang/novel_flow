package org.novelflow.novel.agent.event;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import org.novelflow.novel.agent.session.NovelAgentPersistenceService;
import org.novelflow.novel.agent.session.NovelAgentProgressService;
import org.novelflow.novel.agent.session.NovelAgentSessionService;
import org.novelflow.service.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

@RestController
@RequestMapping("/api/agent")
public class AgentStreamController {

    private static final Logger logger = LoggerFactory.getLogger(AgentStreamController.class);
    private static final int MAX_WINDOW_SIZE = 6;
    private static final DateTimeFormatter RUN_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final ChatService chatService;
    private final NovelAgentSessionService novelAgentSessionService;
    private final NovelAgentPersistenceService novelAgentPersistenceService;
    private final NovelAgentProgressService novelAgentProgressService;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public AgentStreamController(
            ChatService chatService,
            NovelAgentSessionService novelAgentSessionService,
            NovelAgentPersistenceService novelAgentPersistenceService,
            NovelAgentProgressService novelAgentProgressService) {
        this.chatService = chatService;
        this.novelAgentSessionService = novelAgentSessionService;
        this.novelAgentPersistenceService = novelAgentPersistenceService;
        this.novelAgentProgressService = novelAgentProgressService;
    }

    @PostMapping(value = "/stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter stream(@RequestBody AgentStreamRequest request) {
        SseEmitter emitter = new SseEmitter(900000L);
        String message = request == null ? "" : safe(request.getMessage()).trim();
        String displayMessage = request == null ? "" : safe(request.getDisplayMessage()).trim();
        String displayRole = normalizeDisplayRole(request == null ? "" : request.getDisplayRole());
        String sessionId = normalizeSessionId(request == null ? "" : request.getSessionId());
        String runId = newRunId();
        String assistantMessageId = "assistant:" + runId;

        if (message.isBlank()) {
            try {
                AgentEvent event = AgentEvent.of(AgentEvent.RUN_FAILED, runId, sessionId, "问题内容不能为空");
                event.setStatus("failed");
                sendEvent(emitter, event);
                emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }

        executor.execute(() -> {
            try {
                logger.info("NovelFlow AgentEvent stream request: sessionId={}, runId={}, message={}",
                        sessionId, runId, message);

                novelAgentPersistenceService.touchSession(sessionId);
                novelAgentSessionService.mergeFrontendContext(sessionId, request.getNovelContext());
                List<Map<String, String>> history = resolveHistory(sessionId, request);
                String persistedMessage = displayMessage.isBlank() ? message : displayMessage;
                novelAgentPersistenceService.appendMessage(sessionId, displayRole, persistedMessage);

                AgentEvent started = AgentEvent.of(AgentEvent.RUN_STARTED, runId, sessionId, "NovelFlow Agent 开始处理请求");
                started.setStatus("running");
                started.getPayload().put("historyPairs", history.size() / 2);
                sendEvent(emitter, started);

                ChatModel chatModel = chatService.getStandardChatModel();
                chatService.logAvailableTools();
                ReactAgent agent = chatService.createReactAgent(chatModel, chatService.buildSystemPrompt(List.of(), sessionId));

                novelAgentProgressService.bind(runId, progress -> {
                    try {
                        sendEvent(emitter, AgentEvent.progress(runId, sessionId, progress));
                    } catch (IOException e) {
                        throw new IllegalStateException("发送 NovelFlow AgentEvent 失败", e);
                    }
                });

                StringBuilder fullAnswer = new StringBuilder();
                AtomicReference<String> fallbackFinishedAnswer = new AtomicReference<>("");
                AgentEvent assistantStarted = AgentEvent.of(
                        AgentEvent.ASSISTANT_MESSAGE_STARTED, runId, sessionId, "助手开始输出");
                assistantStarted.setStatus("streaming");
                assistantStarted.setMessageId(assistantMessageId);
                sendEvent(emitter, assistantStarted);

                AgentEvent thinking = AgentEvent.of(
                        AgentEvent.AGENT_PROGRESS, runId, sessionId, "正在分析用户意图并选择工具");
                thinking.setStatus("running");
                thinking.setMessageId(assistantMessageId);
                sendEvent(emitter, thinking);

                novelAgentSessionService.bind(sessionId);
                try {
                    for (NodeOutput output : chatService.streamChat(agent, message, sessionId).toIterable()) {
                        handleNodeOutput(
                                emitter,
                                runId,
                                sessionId,
                                assistantMessageId,
                                output,
                                fullAnswer,
                                fallbackFinishedAnswer);
                    }
                } finally {
                    novelAgentProgressService.clear(runId);
                    novelAgentSessionService.clear();
                }

                if (fullAnswer.isEmpty() && !fallbackFinishedAnswer.get().isBlank()) {
                    String fallbackAnswer = fallbackFinishedAnswer.get();
                    sendAnswerDeltas(emitter, runId, sessionId, assistantMessageId, fallbackAnswer);
                    fullAnswer.append(fallbackAnswer);
                }

                String finalAnswer = fullAnswer.toString();
                novelAgentPersistenceService.appendMessage(sessionId, "assistant", finalAnswer);
                novelAgentSessionService.persistSession(sessionId);

                AgentEvent stateUpdated = AgentEvent.of(
                        AgentEvent.SESSION_STATE_UPDATED, runId, sessionId, "会话状态已更新");
                stateUpdated.setStatus("succeeded");
                stateUpdated.setPayload(new LinkedHashMap<>(novelAgentSessionService.snapshot(sessionId)));
                sendEvent(emitter, stateUpdated);

                AgentEvent finished = AgentEvent.of(AgentEvent.RUN_FINISHED, runId, sessionId, "NovelFlow Agent 运行完成");
                finished.setStatus("succeeded");
                finished.setMessageId(assistantMessageId);
                finished.getPayload().put("answerChars", finalAnswer.length());
                finished.getPayload().put("messagePairCount", novelAgentPersistenceService.messagePairCount(sessionId));
                sendEvent(emitter, finished);
                emitter.complete();
            } catch (Exception e) {
                logger.error("NovelFlow AgentEvent stream failed: sessionId={}, runId={}", sessionId, runId, e);
                try {
                    AgentEvent failed = AgentEvent.of(
                            AgentEvent.RUN_FAILED,
                            runId,
                            sessionId,
                            e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                    failed.setStatus("failed");
                    failed.getPayload().put("errorType", e.getClass().getName());
                    novelAgentPersistenceService.appendMessage(sessionId, "assistant",
                            "请求中断或失败：" + failed.getMessage());
                    sendEvent(emitter, failed);
                    emitter.complete();
                } catch (IOException ex) {
                    emitter.completeWithError(ex);
                }
            }
        });

        return emitter;
    }

    private void handleNodeOutput(
            SseEmitter emitter,
            String runId,
            String sessionId,
            String assistantMessageId,
            NodeOutput output,
            StringBuilder fullAnswer,
            AtomicReference<String> fallbackFinishedAnswer) throws IOException {
        if (!(output instanceof StreamingOutput<?> streaming)) {
            return;
        }
        OutputType outputType = streaming.getOutputType();
        Message outputMessage = streaming.message();
        if (outputType == OutputType.AGENT_MODEL_STREAMING) {
            String delta = streamingText(streaming, outputMessage);
            if (!delta.isEmpty()) {
                fullAnswer.append(delta);
                sendEvent(emitter, AgentEvent.delta(runId, sessionId, assistantMessageId, delta));
            }
            return;
        }
        if (outputType == OutputType.AGENT_MODEL_FINISHED) {
            String finishedText = finishedAssistantText(outputMessage);
            if (!finishedText.isBlank()) {
                fallbackFinishedAnswer.set(finishedText);
            }
        }
    }

    private String streamingText(StreamingOutput<?> streaming, Message outputMessage) {
        String chunk = safe(streaming.chunk());
        if (!chunk.isEmpty()) {
            return chunk;
        }
        if (outputMessage == null) {
            return "";
        }
        return safe(outputMessage.getText());
    }

    private String finishedAssistantText(Message outputMessage) {
        if (!(outputMessage instanceof AssistantMessage assistantMessage)) {
            return "";
        }
        if (assistantMessage.hasToolCalls()) {
            return "";
        }
        return safe(assistantMessage.getText());
    }

    private void sendAnswerDeltas(
            SseEmitter emitter,
            String runId,
            String sessionId,
            String messageId,
            String answer) throws IOException {
        String safeAnswer = answer == null ? "" : answer;
        int chunkSize = 96;
        for (int i = 0; i < safeAnswer.length(); i += chunkSize) {
            int end = Math.min(i + chunkSize, safeAnswer.length());
            sendEvent(emitter, AgentEvent.delta(runId, sessionId, messageId, safeAnswer.substring(i, end)));
        }
    }

    private void sendEvent(SseEmitter emitter, AgentEvent event) throws IOException {
        synchronized (emitter) {
            emitter.send(SseEmitter.event()
                    .name("agent-event")
                    .id(event.getEventId())
                    .data(event, MediaType.APPLICATION_JSON));
        }
    }

    private List<Map<String, String>> resolveHistory(String sessionId, AgentStreamRequest request) {
        List<Map<String, String>> serverHistory = novelAgentPersistenceService.readRecentHistory(sessionId, MAX_WINDOW_SIZE);
        List<Map<String, String>> clientHistory = normalizeClientHistory(
                request == null ? null : request.getMessages(),
                request == null ? "" : request.getMessage());
        if (clientHistory.size() > serverHistory.size()) {
            novelAgentPersistenceService.replaceHistory(sessionId, clientHistory);
            return clientHistory;
        }
        return serverHistory;
    }

    private List<Map<String, String>> normalizeClientHistory(List<Map<String, String>> clientHistory, String currentQuestion) {
        if (clientHistory == null || clientHistory.isEmpty()) {
            return List.of();
        }
        List<Map<String, String>> normalized = new ArrayList<>();
        for (Map<String, String> message : clientHistory) {
            if (message == null) {
                continue;
            }
            String role = safe(message.get("role")).trim();
            String content = safe(message.get("content")).trim();
            if ((!role.equals("user") && !role.equals("assistant")) || content.isBlank()) {
                continue;
            }
            normalized.add(Map.of("role", role, "content", content));
        }
        if (!normalized.isEmpty()) {
            Map<String, String> last = normalized.get(normalized.size() - 1);
            if ("user".equals(last.get("role")) && Objects.equals(last.get("content"), safe(currentQuestion).trim())) {
                normalized.remove(normalized.size() - 1);
            }
        }
        int maxMessages = MAX_WINDOW_SIZE * 2;
        if (normalized.size() > maxMessages) {
            return new ArrayList<>(normalized.subList(normalized.size() - maxMessages, normalized.size()));
        }
        return normalized;
    }

    private String normalizeSessionId(String sessionId) {
        String safeSessionId = safe(sessionId).trim();
        return safeSessionId.isBlank() ? UUID.randomUUID().toString() : safeSessionId;
    }

    private String normalizeDisplayRole(String role) {
        return "system".equals(safe(role).trim()) ? "system" : "user";
    }

    private String newRunId() {
        return "run-" + LocalDateTime.now().format(RUN_TIME_FORMAT) + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}

