package org.novelflow.novel.agent.event;

import lombok.Getter;
import lombok.Setter;
import org.novelflow.novel.agent.session.NovelAgentProgressService;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
public class AgentEvent {

    public static final String RUN_STARTED = "agent_run_started";
    public static final String RUN_FINISHED = "agent_run_finished";
    public static final String RUN_FAILED = "agent_run_failed";
    public static final String ASSISTANT_MESSAGE_STARTED = "assistant_message_started";
    public static final String ASSISTANT_DELTA = "assistant_delta";
    public static final String SESSION_STATE_UPDATED = "session_state_updated";
    public static final String TOOL_CALL_STARTED = "tool_call_started";
    public static final String TOOL_CALL_FINISHED = "tool_call_finished";
    public static final String TOOL_CALL_FAILED = "tool_call_failed";
    public static final String TRANSLATION_JOB_STARTED = "translation_job_started";
    public static final String TRANSLATION_JOB_PROGRESS = "translation_job_progress";
    public static final String TRANSLATION_JOB_FINISHED = "translation_job_finished";
    public static final String TRANSLATION_JOB_WAITING = "translation_job_waiting";
    public static final String AGENT_PROGRESS = "agent_progress";

    private String eventId;
    private String runId;
    private String sessionId;
    private String eventType;
    private String status;
    private String message;
    private String messageId;
    private String toolCallId;
    private String projectId;
    private String jobId;
    private Integer chapterIndex;
    private String createdAt;
    private Map<String, Object> payload = new LinkedHashMap<>();

    public static AgentEvent of(String eventType, String runId, String sessionId, String message) {
        AgentEvent event = new AgentEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setRunId(runId);
        event.setSessionId(sessionId);
        event.setEventType(eventType);
        event.setMessage(message == null ? "" : message);
        event.setCreatedAt(Instant.now().toString());
        event.setPayload(new LinkedHashMap<>());
        return event;
    }

    public static AgentEvent delta(String runId, String sessionId, String messageId, String delta) {
        AgentEvent event = of(ASSISTANT_DELTA, runId, sessionId, "");
        event.setStatus("streaming");
        event.setMessageId(messageId);
        event.getPayload().put("delta", delta == null ? "" : delta);
        return event;
    }

    public static AgentEvent progress(String runId, String sessionId, NovelAgentProgressService.ProgressEvent progress) {
        Map<String, Object> data = progress == null ? Map.of() : progress.getData();
        String phase = progress == null ? "" : progress.getPhase();
        String message = progress == null ? "" : progress.getMessage();

        AgentEvent event = of(toEventType(phase, data), runId, sessionId, message);
        event.setStatus(toStatus(phase, data));
        event.setPayload(copyPayload(data));
        event.setProjectId(stringValue(data.get("projectId")));
        event.setJobId(stringValue(data.get("jobId")));
        event.setChapterIndex(integerValue(data.get("chapterIndex")));

        String tool = stringValue(data.get("tool"));
        if (!tool.isBlank()) {
            event.setToolCallId(runId + ":tool:" + tool);
        }
        String jobId = stringValue(data.get("jobId"));
        if (!jobId.isBlank()) {
            event.setMessageId("job:" + jobId);
        }
        return event;
    }

    private static String toEventType(String phase, Map<String, Object> data) {
        String safePhase = phase == null ? "" : phase;
        if ("tool:start".equals(safePhase)) {
            return TOOL_CALL_STARTED;
        }
        if ("tool:end".equals(safePhase)) {
            return TOOL_CALL_FINISHED;
        }
        if ("tool:error".equals(safePhase)) {
            return TOOL_CALL_FAILED;
        }
        if ("translation:started".equals(safePhase)) {
            return TRANSLATION_JOB_STARTED;
        }
        if ("translation:reading".equals(safePhase)) {
            return TRANSLATION_JOB_FINISHED;
        }
        if ("translation:waiting".equals(safePhase)) {
            return TRANSLATION_JOB_WAITING;
        }
        if (safePhase.startsWith("translation:")) {
            String status = stringValue(data.get("status"));
            if ("COMPLETED".equalsIgnoreCase(status)) {
                return TRANSLATION_JOB_FINISHED;
            }
            return TRANSLATION_JOB_PROGRESS;
        }
        return AGENT_PROGRESS;
    }

    private static String toStatus(String phase, Map<String, Object> data) {
        String safePhase = phase == null ? "" : phase;
        if ("tool:start".equals(safePhase)) {
            return "running";
        }
        if ("tool:end".equals(safePhase)) {
            return "succeeded";
        }
        if ("tool:error".equals(safePhase)) {
            return "failed";
        }
        if ("translation:waiting".equals(safePhase)) {
            return "waiting";
        }
        if (safePhase.startsWith("translation:")) {
            String status = stringValue(data.get("status"));
            if (!status.isBlank()) {
                return status.toLowerCase();
            }
            return "running";
        }
        return "running";
    }

    private static Map<String, Object> copyPayload(Map<String, Object> data) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (data == null) {
            return result;
        }
        data.forEach((key, value) -> {
            if (key != null && value != null) {
                result.put(key, value);
            }
        });
        return result;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static Integer integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}

