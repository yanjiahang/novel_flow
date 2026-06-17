package org.novelflow.novel.agent.event;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

@Getter
@Setter
public class AgentStreamRequest {

    @JsonProperty("sessionId")
    @JsonAlias({"id", "Id", "ID"})
    private String sessionId;

    @JsonProperty("message")
    @JsonAlias({"question", "Question", "QUESTION"})
    private String message;

    @JsonProperty("displayMessage")
    @JsonAlias({"displayText", "DisplayMessage", "DISPLAY_MESSAGE"})
    private String displayMessage;

    @JsonProperty("displayRole")
    @JsonAlias({"DisplayRole", "DISPLAY_ROLE"})
    private String displayRole;

    @JsonProperty("novelContext")
    @JsonAlias({"NovelContext", "NOVEL_CONTEXT"})
    private Map<String, Object> novelContext;

    @JsonProperty("messages")
    @JsonAlias({"clientHistory", "ClientHistory", "CLIENT_HISTORY"})
    private List<Map<String, String>> messages;
}

