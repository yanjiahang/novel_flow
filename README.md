# NovelFlow Agent

基于 Spring Boot + Spring AI Alibaba 的长篇日语小说翻译 Agent Runtime。项目从原 SuperBizAgent 骨架演进而来，当前已聚焦 NovelFlow 翻译工作流；Supervisor Agent 默认使用阿里云百炼 DashScope，正文翻译使用本地 SakuraLLM/Ollama，前端使用 React + TypeScript。

## 当前阶段

Phase 1 已完成小说工作流的非 LLM 闭环：

- 小说项目初始化。
- `.txt` / `.md` / `.epub` 原文上传。
- Java 章节扫描与拆分，EPUB 会先抽取 spine 正文。
- 基础状态文件与事件日志。
- 项目状态查询接口。

详细接口见 [NOVELFLOW_PHASE1.md](docs/NOVELFLOW_PHASE1.md)。

## 目标架构

```text
ScanChapter
  -> TranslateChapter
  -> UpdateTerms
  -> UpdateSummary
  -> FirstReview
  -> ApplyRewrite
  -> QualityCheck
  -> Retranslate
  -> MergeFinal
```

后续会逐步引入：

- 任务图与节点状态机。
- 多 Agent 协作。
- 多模型路由。
- 术语记忆与上下文摘要。
- 审改分离。
- 三向质检与自动重译。
- 断点恢复与 JSONL tracing。

## 技术栈

| 技术 | 说明 |
|------|------|
| Java 17 | 主开发语言 |
| Spring Boot 3.5 | Web 与服务框架 |
| Spring AI Alibaba | Agent Framework / Graph Runtime |
| DashScope | Supervisor Agent / 审校修订 ChatModel |
| Ollama | SakuraLLM 本地正文翻译 |
| React + TypeScript | NovelFlow Agent Workspace |

## 运行

```bash
mvn spring-boot:run
```

默认端口见 `src/main/resources/application.yml`。

## Phase 1 接口

创建项目：

```bash
curl -X POST http://localhost:8082/api/novels/projects \
  -H "Content-Type: application/json" \
  -d "{\"projectName\":\"demo-novel\",\"sourceLanguage\":\"日语\",\"targetLanguage\":\"中文\"}"
```

上传原文：

```bash
curl -X POST http://localhost:8082/api/novels/projects/{projectId}/source \
  -F "file=@D:/path/to/novel.epub"
```

拆分章节：

```bash
curl -X POST http://localhost:8082/api/novels/projects/{projectId}/split \
  -H "Content-Type: application/json" \
  -d "{}"
```

查询状态：

```bash
curl http://localhost:8082/api/novels/projects/{projectId}
```
