# NovelFlow Phase 1

第一阶段目标是把 Oncall 项目改造成小说 Agent 工作流的工程起点。当前阶段不调用大模型，先完成项目初始化、原文上传、章节拆分和状态查询。

当前默认 ChatModel 已切到本地 Ollama：`qwen2.5-coder:7b`。

## 已实现能力

- 创建小说工作流项目目录。
- 上传 `.txt` / `.md` / `.epub` 原文到项目 `raw/` 目录。
- 用 Java 章节扫描器拆分原文，EPUB 会先按 OPF spine 顺序抽取 XHTML 正文，再输出章节到 `source/` 目录。
- 生成基础状态文件：`config/progress.yaml`、`config/chapter_manifest.json`、`logs/events.jsonl`。
- 查询项目当前文件数量和 checkpoint 文件路径。

## 目录结构

```text
novel-projects/{projectId}/
├── config/
│   ├── chapter_manifest.json
│   ├── progress.yaml
│   ├── summary.yaml
│   ├── term_notes.yaml
│   └── terms.yaml
├── raw/
├── source/
├── output/
├── review/
├── polished/
├── logs/
│   └── events.jsonl
└── final/
```

## 接口

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

## 下一阶段

下一阶段应接入 `TranslatorAgent`，完成单章翻译节点：

- 读取 `source/{chapter}.md`。
- 读取 `config/terms.yaml`、`config/summary.yaml`。
- 通过模型路由选择执行模型。
- 写入 `output/{chapter}_translated.md`。
- 更新 `progress.yaml` 和 `logs/events.jsonl`。
