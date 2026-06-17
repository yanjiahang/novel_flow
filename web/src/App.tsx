import { FormEvent, ReactNode, useEffect, useMemo, useRef, useState } from 'react';
import {
  Bot,
  CheckCircle2,
  Clock3,
  FileUp,
  FolderOpen,
  Loader2,
  MessageSquare,
  PlayCircle,
  RefreshCw,
  Send,
  Square,
  Wrench,
  XCircle
} from 'lucide-react';
import {
  getProjectOverview,
  getSession,
  listProjects,
  listSessions,
  streamAgent,
  uploadNovel
} from './services/api';
import type { AgentEvent, ChatMessage, ChatTurn, ChatTurnRole, ProjectOverview, SessionSummary } from './types';

const SESSION_KEY = 'novelflow.ui.sessionId';

function newId(prefix: string) {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return `${prefix}-${crypto.randomUUID()}`;
  }
  return `${prefix}-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function newSessionId() {
  return `session_${Math.random().toString(36).slice(2, 11)}_${Date.now()}`;
}

function textValue(value: unknown) {
  return value == null ? '' : String(value);
}

function numberValue(value: unknown) {
  return typeof value === 'number' && Number.isFinite(value) ? value : 0;
}

function formatTime(value?: string | number) {
  if (!value) {
    return '';
  }
  const date = typeof value === 'number' ? new Date(value) : new Date(value);
  if (Number.isNaN(date.getTime())) {
    return '';
  }
  return date.toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  });
}

function eventLabel(type: string) {
  const labels: Record<string, string> = {
    agent_run_started: 'Agent',
    agent_run_finished: '完成',
    agent_run_failed: '失败',
    assistant_message_started: '回复',
    assistant_delta: '输出',
    session_state_updated: '状态',
    tool_call_started: '工具',
    tool_call_finished: '工具',
    tool_call_failed: '工具',
    translation_job_started: '翻译',
    translation_job_progress: '翻译',
    translation_job_finished: '翻译',
    translation_job_waiting: '等待',
    agent_progress: '进度'
  };
  return labels[type] || type;
}

function timestampValue(value?: string) {
  if (!value) {
    return 0;
  }
  const time = new Date(value).getTime();
  return Number.isFinite(time) ? time : 0;
}

function sortedByTime(events: AgentEvent[]) {
  return [...events].sort((left, right) => timestampValue(left.createdAt) - timestampValue(right.createdAt));
}

function latestActivity(events: AgentEvent[]) {
  return [...events]
    .reverse()
    .find((event) => event.eventType !== 'assistant_delta' && event.eventType !== 'assistant_message_started');
}

function toolName(event: AgentEvent) {
  return textValue(event.payload?.tool || event.message || '工具调用');
}

function jobTitle(event: AgentEvent) {
  const chapter = event.chapterIndex ? `第 ${event.chapterIndex} 章` : '翻译任务';
  const title = textValue(event.payload?.title || event.payload?.sourceFileName);
  return title ? `${chapter} · ${title}` : chapter;
}

function statusIcon(status?: string) {
  const normalized = (status || '').toLowerCase();
  if (normalized === 'succeeded' || normalized === 'completed') {
    return <CheckCircle2 size={16} />;
  }
  if (normalized === 'failed') {
    return <XCircle size={16} />;
  }
  if (normalized === 'waiting') {
    return <Clock3 size={16} />;
  }
  return <Loader2 size={16} className="spin" />;
}

function normalizeLoadedTurn(message: { role?: string; content?: string }): ChatTurn {
  const normalizedRole = textValue(message.role).trim().toLowerCase();
  let role: ChatTurnRole = normalizedRole === 'assistant'
    ? 'assistant'
    : normalizedRole === 'system'
      ? 'system'
      : 'user';
  let content = textValue(message.content);
  if (role === 'user' && content.startsWith('我刚上传了一本 NovelFlow 小说')) {
    const sourceFileName = content.match(/sourceFileName:\s*(.+)/)?.[1]?.trim();
    role = 'system';
    content = `NovelFlow 上传小说：${sourceFileName || '源文件'}`;
  }
  return {
    id: newId(role),
    role,
    content,
    createdAt: new Date().toISOString()
  };
}

function normalizeSessionSummaries(items: SessionSummary[]) {
  const seen = new Set<string>();
  return items.filter((session) => {
    const id = textValue(session.sessionId).trim();
    if (!id || id === 'default' || seen.has(id)) {
      return false;
    }
    seen.add(id);
    return true;
  });
}

type MarkdownBlock =
  | { type: 'paragraph'; text: string }
  | { type: 'heading'; level: number; text: string }
  | { type: 'table'; headers: string[]; rows: string[][] }
  | { type: 'list'; ordered: boolean; items: string[] }
  | { type: 'quote'; text: string }
  | { type: 'rule' };

function parseTableRow(line: string) {
  let trimmed = line.trim();
  if (trimmed.startsWith('|')) {
    trimmed = trimmed.slice(1);
  }
  if (trimmed.endsWith('|')) {
    trimmed = trimmed.slice(0, -1);
  }
  return trimmed.split('|').map((cell) => cell.trim());
}

function isTableSeparator(line: string) {
  const cells = parseTableRow(line);
  return cells.length > 1 && cells.every((cell) => /^:?-{3,}:?$/.test(cell.replace(/\s/g, '')));
}

function parseMarkdownLite(content: string): MarkdownBlock[] {
  const lines = content.replace(/\r\n/g, '\n').split('\n');
  const blocks: MarkdownBlock[] = [];
  let index = 0;

  while (index < lines.length) {
    const rawLine = lines[index];
    const line = rawLine.trim();

    if (!line) {
      index += 1;
      continue;
    }

    if (/^-{3,}$/.test(line)) {
      blocks.push({ type: 'rule' });
      index += 1;
      continue;
    }

    const heading = line.match(/^(#{1,4})\s+(.+)$/);
    if (heading) {
      blocks.push({ type: 'heading', level: heading[1].length, text: heading[2].trim() });
      index += 1;
      continue;
    }

    if (line.startsWith('>')) {
      const quoteLines: string[] = [];
      while (index < lines.length && lines[index].trim().startsWith('>')) {
        quoteLines.push(lines[index].trim().replace(/^>\s?/, ''));
        index += 1;
      }
      blocks.push({ type: 'quote', text: quoteLines.join('\n') });
      continue;
    }

    if (line.includes('|') && index + 1 < lines.length && isTableSeparator(lines[index + 1])) {
      const headers = parseTableRow(rawLine);
      index += 2;
      const rows: string[][] = [];
      while (index < lines.length && lines[index].trim().includes('|') && lines[index].trim()) {
        const row = parseTableRow(lines[index]);
        rows.push(headers.map((_, cellIndex) => row[cellIndex] || ''));
        index += 1;
      }
      blocks.push({ type: 'table', headers, rows });
      continue;
    }

    const ordered = /^\d+\.\s+/.test(line);
    const unordered = /^[-*]\s+/.test(line);
    if (ordered || unordered) {
      const items: string[] = [];
      const matcher = ordered ? /^\d+\.\s+/ : /^[-*]\s+/;
      while (index < lines.length && matcher.test(lines[index].trim())) {
        items.push(lines[index].trim().replace(matcher, '').trim());
        index += 1;
      }
      blocks.push({ type: 'list', ordered, items });
      continue;
    }

    const paragraphLines = [rawLine.trim()];
    index += 1;
    while (index < lines.length) {
      const next = lines[index].trim();
      if (!next
        || /^(#{1,4})\s+/.test(next)
        || /^-{3,}$/.test(next)
        || next.startsWith('>')
        || /^\d+\.\s+/.test(next)
        || /^[-*]\s+/.test(next)
        || (next.includes('|') && index + 1 < lines.length && isTableSeparator(lines[index + 1]))) {
        break;
      }
      paragraphLines.push(next);
      index += 1;
    }
    blocks.push({ type: 'paragraph', text: paragraphLines.join('\n') });
  }

  return blocks;
}

function renderInlineMarkdown(text: string): ReactNode[] {
  const nodes: ReactNode[] = [];
  const pattern = /(\*\*[^*]+\*\*|`[^`]+`)/g;
  let lastIndex = 0;
  let match: RegExpExecArray | null;

  while ((match = pattern.exec(text)) !== null) {
    if (match.index > lastIndex) {
      nodes.push(text.slice(lastIndex, match.index));
    }
    const token = match[0];
    if (token.startsWith('**')) {
      nodes.push(<strong key={`${match.index}-strong`}>{token.slice(2, -2)}</strong>);
    } else {
      nodes.push(<code key={`${match.index}-code`}>{token.slice(1, -1)}</code>);
    }
    lastIndex = match.index + token.length;
  }

  if (lastIndex < text.length) {
    nodes.push(text.slice(lastIndex));
  }
  return nodes.length > 0 ? nodes : [text];
}

function renderInlineLines(text: string) {
  return text.split('\n').map((line, index) => (
    <span key={`${line}-${index}`}>
      {index > 0 && <br />}
      {renderInlineMarkdown(line)}
    </span>
  ));
}

function MessageContent({ content }: { content: string }) {
  const blocks = useMemo(() => parseMarkdownLite(content), [content]);
  return (
    <div className="rich-message">
      {blocks.map((block, index) => {
        if (block.type === 'heading') {
          const Tag = (`h${Math.min(4, block.level)}` as keyof JSX.IntrinsicElements);
          return (
            <Tag key={index} className={`rich-heading level-${block.level}`}>
              {renderInlineMarkdown(block.text)}
            </Tag>
          );
        }
        if (block.type === 'table') {
          const isChapterTable = block.headers.some((header) => header.includes('章节'));
          return (
            <div className="rich-table-wrap" key={index}>
              <table className={`rich-table ${isChapterTable ? 'chapter-summary' : ''}`}>
                <thead>
                  <tr>
                    {block.headers.map((header, headerIndex) => (
                      <th key={`${header}-${headerIndex}`}>{renderInlineMarkdown(header)}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {block.rows.map((row, rowIndex) => (
                    <tr key={rowIndex}>
                      {row.map((cell, cellIndex) => (
                        <td key={`${rowIndex}-${cellIndex}`}>{renderInlineMarkdown(cell)}</td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          );
        }
        if (block.type === 'list') {
          const ListTag = block.ordered ? 'ol' : 'ul';
          return (
            <ListTag key={index} className="rich-list">
              {block.items.map((item, itemIndex) => (
                <li key={`${item}-${itemIndex}`}>{renderInlineMarkdown(item)}</li>
              ))}
            </ListTag>
          );
        }
        if (block.type === 'quote') {
          return <blockquote key={index}>{renderInlineLines(block.text)}</blockquote>;
        }
        if (block.type === 'rule') {
          return <hr key={index} />;
        }
        return <p key={index}>{renderInlineLines(block.text)}</p>;
      })}
    </div>
  );
}

export function App() {
  const [sessionId, setSessionId] = useState(() => localStorage.getItem(SESSION_KEY) || newSessionId());
  const [sessions, setSessions] = useState<SessionSummary[]>([]);
  const [projects, setProjects] = useState<Array<Record<string, unknown>>>([]);
  const [turns, setTurns] = useState<ChatTurn[]>([]);
  const [events, setEvents] = useState<AgentEvent[]>([]);
  const [toolEvents, setToolEvents] = useState<Record<string, AgentEvent>>({});
  const [jobEvents, setJobEvents] = useState<Record<string, AgentEvent>>({});
  const [input, setInput] = useState('');
  const [streaming, setStreaming] = useState(false);
  const [novelContext, setNovelContext] = useState<Record<string, unknown>>({});
  const [projectOverview, setProjectOverview] = useState<ProjectOverview | null>(null);
  const [uploading, setUploading] = useState(false);
  const currentAssistantId = useRef<string>('');
  const abortRef = useRef<AbortController | null>(null);
  const bottomRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    localStorage.setItem(SESSION_KEY, sessionId);
  }, [sessionId]);

  useEffect(() => {
    void refreshSessions();
    void refreshProjects();
  }, []);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' });
  }, [turns, events, jobEvents, toolEvents]);

  const activeProjectId = textValue(novelContext.currentNovelProjectId || novelContext.projectId);
  const recoverableInterruptedJob = useMemo(() => {
    const value = novelContext.recoverableInterruptedJob;
    if (!value || typeof value !== 'object' || Array.isArray(value)) {
      return null;
    }
    return value as Record<string, unknown>;
  }, [novelContext]);

  useEffect(() => {
    if (!activeProjectId) {
      setProjectOverview(null);
      return;
    }
    void getProjectOverview(activeProjectId)
      .then(setProjectOverview)
      .catch(() => setProjectOverview(null));
  }, [activeProjectId]);

  const chatHistory = useMemo<ChatMessage[]>(() => {
    const history: ChatMessage[] = [];
    for (const turn of turns) {
      if ((turn.role === 'user' || turn.role === 'assistant') && turn.content.trim()) {
        history.push({ role: turn.role, content: turn.content });
      }
    }
    return history;
  }, [turns]);

  async function refreshSessions() {
    try {
      setSessions(normalizeSessionSummaries(await listSessions(50)));
    } catch (error) {
      console.warn('读取会话列表失败', error);
    }
  }

  async function refreshProjects() {
    try {
      setProjects(await listProjects(''));
    } catch (error) {
      console.warn('读取项目列表失败', error);
    }
  }

  async function loadSession(id: string) {
    if (!id || id === 'default') {
      return;
    }
    const session = await getSession(id);
    setSessionId(session.sessionId);
    setNovelContext(session.novelContext || {});
    setTurns((session.messages || []).map(normalizeLoadedTurn));
    setEvents([]);
    setToolEvents({});
    setJobEvents({});
  }

  function startNewSession() {
    const id = newSessionId();
    setSessionId(id);
    setNovelContext({});
    setProjectOverview(null);
    setTurns([]);
    setEvents([]);
    setToolEvents({});
    setJobEvents({});
  }

  function appendAssistantDelta(delta: string) {
    const id = currentAssistantId.current;
    if (!id) {
      return;
    }
    setTurns((current) => current.map((turn) => (
      turn.id === id ? { ...turn, content: turn.content + delta } : turn
    )));
  }

  function markAssistantComplete() {
    const id = currentAssistantId.current;
    setTurns((current) => current.map((turn) => (
      turn.id === id ? { ...turn, streaming: false } : turn
    )));
  }

  function applyEvent(event: AgentEvent) {
    setEvents((current) => [...current.slice(-80), event]);
    if (event.eventType === 'assistant_delta') {
      appendAssistantDelta(textValue(event.payload?.delta));
      return;
    }
    if (event.eventType === 'agent_run_finished' || event.eventType === 'agent_run_failed') {
      markAssistantComplete();
      return;
    }
    if (event.eventType === 'session_state_updated') {
      setNovelContext(event.payload || {});
      return;
    }
    if (event.toolCallId || event.eventType.startsWith('tool_call_')) {
      const key = event.toolCallId || `${event.runId}:${textValue(event.payload?.tool || event.eventId)}`;
      setToolEvents((current) => ({ ...current, [key]: event }));
    }
    if (event.jobId || event.eventType.startsWith('translation_job_')) {
      const key = event.jobId || event.messageId || event.eventId;
      setJobEvents((current) => ({ ...current, [key]: event }));
    }
  }

  async function sendAgentMessage(
    message: string,
    options?: {
      displayText?: string;
      displayRole?: Exclude<ChatTurnRole, 'assistant'>;
      context?: Record<string, unknown>;
    }
  ) {
    const finalMessage = message.trim();
    if (!finalMessage || streaming) {
      return;
    }
    const mergedContext = { ...novelContext, ...(options?.context || {}) };
    setNovelContext(mergedContext);
    const displayText = options?.displayText || finalMessage;
    const displayRole = options?.displayRole || 'user';
    const requestHistory = [...chatHistory];
    if (displayRole === 'user') {
      requestHistory.push({ role: 'user', content: displayText });
    }
    const visibleTurn: ChatTurn = {
      id: newId(displayRole),
      role: displayRole,
      content: displayText,
      createdAt: new Date().toISOString()
    };
    const assistantTurn: ChatTurn = {
      id: newId('assistant'),
      role: 'assistant',
      content: '',
      createdAt: new Date().toISOString(),
      streaming: true
    };
    currentAssistantId.current = assistantTurn.id;
    setEvents([]);
    setToolEvents({});
    setJobEvents({});
    setTurns((current) => [...current, visibleTurn, assistantTurn]);
    setInput('');
    setStreaming(true);
    const controller = new AbortController();
    abortRef.current = controller;

    try {
      await streamAgent({
        sessionId,
        message: finalMessage,
        displayMessage: displayText,
        displayRole,
        novelContext: mergedContext,
        messages: requestHistory
      }, applyEvent, controller.signal);
      await refreshSessions();
      await refreshProjects();
    } catch (error) {
      appendAssistantDelta(`请求失败：${error instanceof Error ? error.message : String(error)}`);
      markAssistantComplete();
    } finally {
      setStreaming(false);
      abortRef.current = null;
    }
  }

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    await sendAgentMessage(input);
  }

  async function onUpload(file: File | null) {
    if (!file || uploading || streaming) {
      return;
    }
    setUploading(true);
    try {
      const result = await uploadNovel(file);
      const context = {
        currentNovelProjectId: result.projectId,
        currentNovelSourceFileName: result.sourceFileName
      };
      await sendAgentMessage(
        [
          '我刚上传了一本 NovelFlow 小说，请作为 Supervisor Agent 继续处理。',
          `projectId: ${result.projectId}`,
          `sourceFileName: ${result.sourceFileName}`,
          '请调用 splitNovelProject 完成章节拆分，并给出完整章节概览和下一步建议。'
        ].join('\n'),
        {
          displayText: `NovelFlow 上传小说：${file.name}`,
          displayRole: 'system',
          context
        }
      );
    } catch (error) {
      setTurns((current) => [...current, {
        id: newId('assistant'),
        role: 'assistant',
        content: `NovelFlow 处理失败：${error instanceof Error ? error.message : String(error)}`,
        createdAt: new Date().toISOString()
      }]);
    } finally {
      setUploading(false);
    }
  }

  function stopStream() {
    abortRef.current?.abort();
    markAssistantComplete();
    setStreaming(false);
  }

  async function recoverInterruptedJob() {
    if (!recoverableInterruptedJob || streaming) {
      return;
    }
    const projectId = textValue(recoverableInterruptedJob.projectId || activeProjectId);
    const jobId = textValue(recoverableInterruptedJob.jobId);
    if (!projectId || !jobId) {
      return;
    }
    await sendAgentMessage(
      `请查看项目 ${projectId} 的翻译任务 ${jobId} 的 Graph 历史，选择失败前可恢复的 checkpoint，并从 checkpoint 派生新任务继续执行。`,
      {
        displayText: `恢复中断任务：${jobId}`
      }
    );
  }

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <Bot size={24} />
          <div>
            <strong>NovelFlow</strong>
            <span>Agent Workspace</span>
          </div>
        </div>
        <button className="primary-button" onClick={startNewSession}>
          <MessageSquare size={16} />
          新对话
        </button>
        <section className="sidebar-section sessions-section">
          <div className="section-title">
            <span>会话</span>
            <button className="icon-button" onClick={() => void refreshSessions()} title="刷新会话">
              <RefreshCw size={15} />
            </button>
          </div>
          <div className="session-list">
            {sessions.length === 0 ? <div className="empty-text">暂无后端会话</div> : sessions.map((session) => (
              <button
                key={session.sessionId}
                className={`session-item ${session.sessionId === sessionId ? 'active' : ''}`}
                onClick={() => void loadSession(session.sessionId)}
                title={session.title || session.sessionId}
              >
                <span>{session.title || session.sessionId}</span>
                <small>
                  {[
                    session.messagePairCount ? `${session.messagePairCount} 轮` : '',
                    formatTime(session.updatedAt)
                  ].filter(Boolean).join(' · ')}
                </small>
              </button>
            ))}
          </div>
        </section>
        <section className="sidebar-section projects-section">
          <div className="section-title">
            <span>项目</span>
            <button className="icon-button" onClick={() => void refreshProjects()} title="刷新项目">
              <RefreshCw size={15} />
            </button>
          </div>
          <div className="project-list">
            {projects.slice(0, 8).map((project) => (
              <button
                key={textValue(project.projectId)}
                className={`project-item ${textValue(project.projectId) === activeProjectId ? 'active' : ''}`}
                title={textValue(project.projectName || project.sourceFileName || project.projectId)}
                onClick={() => {
                  const projectId = textValue(project.projectId);
                  setNovelContext((current) => ({ ...current, currentNovelProjectId: projectId }));
                }}
              >
                <FolderOpen size={15} />
                <span>{textValue(project.projectName || project.sourceFileName || project.projectId)}</span>
              </button>
            ))}
          </div>
        </section>
      </aside>

      <main className="workspace">
        <header className="workspace-header">
          <div>
            <h1>Agent 事件流</h1>
            <p>Session: {sessionId}</p>
          </div>
          <label className={`upload-button ${uploading ? 'disabled' : ''}`}>
            <FileUp size={16} />
            {uploading ? '上传中' : '上传小说'}
            <input
              type="file"
              accept=".txt,.md,.epub"
              disabled={uploading || streaming}
              onChange={(event) => void onUpload(event.target.files?.[0] || null)}
            />
          </label>
        </header>

        {recoverableInterruptedJob && (
          <div className="recovery-banner">
            <div>
              <strong>检测到上次中断的翻译任务</strong>
              <span>
                {textValue(recoverableInterruptedJob.title || '当前章节')}
                {textValue(recoverableInterruptedJob.jobId) ? ` · ${textValue(recoverableInterruptedJob.jobId)}` : ''}
              </span>
              <p>{textValue(recoverableInterruptedJob.message || recoverableInterruptedJob.recoverHint)}</p>
            </div>
            <button type="button" onClick={() => void recoverInterruptedJob()} disabled={streaming}>
              恢复
            </button>
          </div>
        )}

        <div className="main-grid">
          <section className="chat-panel">
            <div className="message-list">
              {turns.length === 0 ? (
                <div className="welcome">
                  <Bot size={36} />
                  <h2>从一句话开始调度 NovelFlow</h2>
                  <p>可以上传 EPUB，也可以直接说“查看当前项目进度”“翻译第 3 章前 5000 字”。</p>
                </div>
              ) : turns.map((turn) => (
                <article key={turn.id} className={`chat-message ${turn.role}`}>
                  <div className="message-avatar">
                    {turn.role === 'user' ? '你' : turn.role === 'system' ? 'NF' : 'AI'}
                  </div>
                  <div className="message-body">
                    {turn.content ? (
                      <MessageContent content={turn.content} />
                    ) : turn.streaming ? (
                      <LiveRunStatus
                        events={events}
                        toolEvents={Object.values(toolEvents)}
                        jobEvents={Object.values(jobEvents)}
                      />
                    ) : (
                      <pre />
                    )}
                    {turn.streaming && <span className="streaming-dot" />}
                  </div>
                </article>
              ))}
              <div ref={bottomRef} />
            </div>
            <form className="composer" onSubmit={onSubmit}>
              <textarea
                value={input}
                disabled={streaming}
                placeholder="问问 NovelFlow，例如：翻译第3章前5000字"
                onChange={(event) => setInput(event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === 'Enter' && !event.shiftKey) {
                    event.preventDefault();
                    void sendAgentMessage(input);
                  }
                }}
              />
              {streaming ? (
                <button type="button" className="stop-button" onClick={stopStream}>
                  <Square size={16} />
                  停止
                </button>
              ) : (
                <button type="submit" className="send-button" disabled={!input.trim()}>
                  <Send size={16} />
                  发送
                </button>
              )}
            </form>
          </section>

          <aside className="inspector">
            <TaskPanel title="翻译任务" events={Object.values(jobEvents)} />
            <ToolPanel events={Object.values(toolEvents)} />
            <ProjectPanel overview={projectOverview} context={novelContext} />
            <Timeline events={events.slice(-18).reverse()} />
          </aside>
        </div>
      </main>
    </div>
  );
}

function LiveRunStatus({
  events,
  toolEvents,
  jobEvents
}: {
  events: AgentEvent[];
  toolEvents: AgentEvent[];
  jobEvents: AgentEvent[];
}) {
  const activity = latestActivity(events);
  const latestTools = sortedByTime(toolEvents).slice(-4);
  const latestJobs = sortedByTime(jobEvents).slice(-2);
  const headline = activity?.message || 'NovelFlow Agent 正在启动';
  return (
    <div className="live-run-card">
      <div className="live-run-header">
        <span className={`live-dot ${activity?.status || 'running'}`} />
        <div>
          <strong>{eventLabel(activity?.eventType || 'agent_run_started')}</strong>
          <p>{headline}</p>
        </div>
      </div>
      {latestTools.length > 0 && (
        <div className="live-section">
          <span className="live-section-title">工具调用</span>
          {latestTools.map((event) => (
            <div className="live-tool" key={event.toolCallId || event.eventId}>
              <div className={`tool-icon ${event.status || ''}`}>{statusIcon(event.status)}</div>
              <div>
                <strong>{toolName(event)}</strong>
                <span>{event.message}</span>
              </div>
            </div>
          ))}
        </div>
      )}
      {latestJobs.length > 0 && (
        <div className="live-section">
          <span className="live-section-title">翻译任务</span>
          {latestJobs.map((event) => {
            const percent = numberValue(event.payload?.percent);
            const total = numberValue(event.payload?.totalChunks);
            const completed = numberValue(event.payload?.completedChunks);
            return (
              <div className="live-job" key={event.jobId || event.eventId}>
                <div className="job-header">
                  <strong>{jobTitle(event)}</strong>
                  <span className={`status ${event.status || ''}`}>{event.status || 'running'}</span>
                </div>
                {total > 0 && (
                  <>
                    <div className="progress-line">
                      <span>{completed}/{total}</span>
                      <span>{percent ? `${percent.toFixed(1)}%` : ''}</span>
                    </div>
                    <div className="progress-track">
                      <div className="progress-fill" style={{ width: `${Math.max(0, Math.min(100, percent))}%` }} />
                    </div>
                  </>
                )}
                <p>{event.message}</p>
              </div>
            );
          })}
        </div>
      )}
      {events.length <= 1 && <div className="live-empty">正在建立 Agent 运行上下文...</div>}
    </div>
  );
}

function TaskPanel({ title, events }: { title: string; events: AgentEvent[] }) {
  return (
    <section className="panel">
      <h2><PlayCircle size={17} />{title}</h2>
      {events.length === 0 ? <div className="empty-text">暂无翻译任务事件</div> : events.map((event) => {
        const percent = numberValue(event.payload?.percent);
        const total = numberValue(event.payload?.totalChunks);
        const completed = numberValue(event.payload?.completedChunks);
        return (
          <div className="job-card" key={event.jobId || event.eventId}>
            <div className="job-header">
              <strong>{event.chapterIndex ? `第 ${event.chapterIndex} 章` : '翻译任务'}</strong>
              <span className={`status ${event.status || ''}`}>{event.status || 'running'}</span>
            </div>
            <div className="job-title">{textValue(event.payload?.title || event.jobId)}</div>
            {total > 0 && (
              <>
                <div className="progress-line">
                  <span>{completed}/{total}</span>
                  <span>{percent ? `${percent.toFixed(1)}%` : ''}</span>
                </div>
                <div className="progress-track">
                  <div className="progress-fill" style={{ width: `${Math.max(0, Math.min(100, percent))}%` }} />
                </div>
              </>
            )}
            <p>{event.message}</p>
          </div>
        );
      })}
    </section>
  );
}

function ToolPanel({ events }: { events: AgentEvent[] }) {
  return (
    <section className="panel">
      <h2><Wrench size={17} />工具调用</h2>
      {events.length === 0 ? <div className="empty-text">暂无工具事件</div> : events.map((event) => (
        <div className="tool-row" key={event.toolCallId || event.eventId}>
          <div className={`tool-icon ${event.status || ''}`}>{statusIcon(event.status)}</div>
          <div>
            <strong>{textValue(event.payload?.tool || event.message || '工具')}</strong>
            <span>{event.message}</span>
          </div>
        </div>
      ))}
    </section>
  );
}

function ProjectPanel({ overview, context }: { overview: ProjectOverview | null; context: Record<string, unknown> }) {
  const chapters = Array.isArray(overview?.chapters) ? overview?.chapters || [] : [];
  return (
    <section className="panel project-panel">
      <h2><FolderOpen size={17} />当前项目</h2>
      <div className="kv">
        <span>Project ID</span>
        <strong>{textValue(overview?.projectId || context.currentNovelProjectId || '未选择')}</strong>
      </div>
      <div className="kv">
        <span>源文件</span>
        <strong>{textValue(overview?.sourceFileName || context.currentNovelSourceFileName || '-')}</strong>
      </div>
      <div className="kv">
        <span>章节数</span>
        <strong>{textValue(overview?.chapterCount || chapters.length || '-')}</strong>
      </div>
      {chapters.length > 0 && (
        <div className="chapter-table">
          {chapters.slice(0, 14).map((chapter, index) => (
            <div className="chapter-row" key={textValue(chapter.chapterIndex || index)}>
              <span>{textValue(chapter.chapterIndex || index + 1)}</span>
              <strong>{textValue(chapter.title || '未命名章节')}</strong>
              <em>{textValue(chapter.sourceCharCount || chapter.charCount || chapter.characters || '')}</em>
            </div>
          ))}
        </div>
      )}
    </section>
  );
}

function Timeline({ events }: { events: AgentEvent[] }) {
  return (
    <section className="panel timeline-panel">
      <h2><Clock3 size={17} />事件时间线</h2>
      {events.length === 0 ? <div className="empty-text">等待 AgentEvent</div> : events.map((event) => (
        <div className="timeline-row" key={event.eventId}>
          <span className={`timeline-dot ${event.status || ''}`} />
          <div>
            <strong>{eventLabel(event.eventType)}</strong>
            <p>{event.message || event.eventType}</p>
            <small>{formatTime(event.createdAt)}</small>
          </div>
        </div>
      ))}
    </section>
  );
}
