import type { AgentEvent, ApiResponse, ChatMessage, ProjectOverview, SessionInfo, SessionSummary, UploadResult } from '../types';

const jsonHeaders = {
  'Content-Type': 'application/json'
};

async function readApi<T>(response: Response): Promise<T> {
  if (!response.ok) {
    throw new Error(`HTTP ${response.status}`);
  }
  const body = (await response.json()) as ApiResponse<T>;
  if (body.code !== 200) {
    throw new Error(body.message || '请求失败');
  }
  return body.data;
}

export async function listSessions(limit = 50): Promise<SessionSummary[]> {
  const response = await fetch(`/api/chat/sessions?limit=${limit}`);
  return readApi<SessionSummary[]>(response);
}

export async function getSession(sessionId: string): Promise<SessionInfo> {
  const response = await fetch(`/api/chat/session/${encodeURIComponent(sessionId)}`);
  return readApi<SessionInfo>(response);
}

export async function listProjects(query = ''): Promise<Array<Record<string, unknown>>> {
  const response = await fetch(`/api/novels/projects?query=${encodeURIComponent(query)}`);
  return readApi<Array<Record<string, unknown>>>(response);
}

export async function getProjectOverview(projectId: string): Promise<ProjectOverview> {
  const response = await fetch(`/api/novels/projects/${encodeURIComponent(projectId)}/overview`);
  return readApi<ProjectOverview>(response);
}

export async function uploadNovel(file: File): Promise<UploadResult> {
  const projectName = file.name.replace(/\.[^.]+$/, '');
  const createResponse = await fetch('/api/novels/projects', {
    method: 'POST',
    headers: jsonHeaders,
    body: JSON.stringify({
      projectName,
      sourceLanguage: '日语',
      targetLanguage: '中文'
    })
  });
  const project = await readApi<{ projectId: string }>(createResponse);

  const formData = new FormData();
  formData.append('file', file);
  const uploadResponse = await fetch(`/api/novels/projects/${encodeURIComponent(project.projectId)}/source`, {
    method: 'POST',
    body: formData
  });
  const upload = await readApi<{ fileName: string }>(uploadResponse);

  return {
    projectId: project.projectId,
    sourceFileName: upload.fileName,
    projectName
  };
}

export async function streamAgent(
  params: {
    sessionId: string;
    message: string;
    displayMessage?: string;
    displayRole?: 'user' | 'system';
    novelContext?: Record<string, unknown>;
    messages?: ChatMessage[];
  },
  onEvent: (event: AgentEvent) => void,
  signal?: AbortSignal
): Promise<void> {
  const response = await fetch('/api/agent/stream', {
    method: 'POST',
    headers: jsonHeaders,
    signal,
    body: JSON.stringify(params)
  });

  if (!response.ok || !response.body) {
    throw new Error(`HTTP ${response.status}`);
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';

  while (true) {
    const { done, value } = await reader.read();
    if (done) {
      break;
    }
    buffer += decoder.decode(value, { stream: true });
    const events = buffer.split(/\r?\n\r?\n/);
    buffer = events.pop() || '';
    for (const rawEvent of events) {
      const dataLines = rawEvent
        .split(/\r?\n/)
        .filter((line) => line.startsWith('data:'))
        .map((line) => line.slice(5).trimStart());
      if (dataLines.length === 0) {
        continue;
      }
      const rawData = dataLines.join('\n');
      try {
        onEvent(JSON.parse(rawData) as AgentEvent);
      } catch (error) {
        console.warn('忽略无法解析的 AgentEvent:', rawData, error);
      }
    }
  }
}
