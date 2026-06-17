export type AgentEventType =
  | 'agent_run_started'
  | 'agent_run_finished'
  | 'agent_run_failed'
  | 'assistant_message_started'
  | 'assistant_delta'
  | 'session_state_updated'
  | 'tool_call_started'
  | 'tool_call_finished'
  | 'tool_call_failed'
  | 'translation_job_started'
  | 'translation_job_progress'
  | 'translation_job_finished'
  | 'translation_job_waiting'
  | 'agent_progress';

export interface AgentEvent {
  eventId: string;
  runId: string;
  sessionId: string;
  eventType: AgentEventType;
  status?: string;
  message?: string;
  messageId?: string;
  toolCallId?: string;
  projectId?: string;
  jobId?: string;
  chapterIndex?: number;
  createdAt?: string;
  payload?: Record<string, unknown>;
}

export interface ChatMessage {
  role: 'user' | 'assistant';
  content: string;
}

export type ChatTurnRole = 'user' | 'assistant' | 'system';

export interface ChatTurn {
  id: string;
  role: ChatTurnRole;
  content: string;
  createdAt: string;
  streaming?: boolean;
}

export interface SessionSummary {
  sessionId: string;
  title?: string;
  messagePairCount?: number;
  updatedAt?: number;
  createTime?: number;
  novelContext?: Record<string, unknown>;
}

export interface SessionInfo {
  sessionId: string;
  title?: string;
  messagePairCount?: number;
  createTime?: number;
  updatedAt?: number;
  novelContext?: Record<string, unknown>;
  messages?: Array<{ role: ChatTurnRole; content: string }>;
}

export interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
}

export interface ProjectOverview {
  projectId?: string;
  projectName?: string;
  sourceFileName?: string;
  chapterCount?: number;
  chapters?: Array<Record<string, unknown>>;
  [key: string]: unknown;
}

export interface UploadResult {
  projectId: string;
  sourceFileName: string;
  projectName: string;
}
