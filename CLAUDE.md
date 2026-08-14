# 企业知识库问答智能体（RAG）- 项目文档

Spring Boot + LangChain4j + Vue3 构建的企业知识库问答智能体，支持多会话记忆隔离、流式输出、混合检索（kNN + BM25 + RRF）和引用溯源。

## 技术栈

| 层 | 技术 | 版本 |
|---|---|---|
| 框架 | Spring Boot | 4.1.0 |
| AI | LangChain4j | 1.0.0-beta2 |
| LLM | DeepSeek (OpenAI 兼容) | deepseek-chat |
| Embedding | 硅基流动 BGE-M3 (OpenAI 兼容) | 1024 维 |
| 向量库 | Elasticsearch | 8.15.3 (Docker) |
| 文档解析 | Apache Tika | 3.0.0 |
| 缓存 | Redis | - |
| 持久化 | MySQL + JPA | - |
| 前端 | Vue3 + Vite | 5.x |

## 快速启动

### 1. 前置条件

- JDK 17+
- MySQL 8.0+（root / 123456），创建数据库 `ai_chat`
- Redis（密码 123456）
- Docker（运行 Elasticsearch）
- Node.js 18+

### 2. 启动 Elasticsearch

```bash
# Windows WSL2 首次需要：
wsl -d docker-desktop sysctl -w vm.max_map_count=262144

docker run -d --name es-kb -p 9200:9200 -p 9300:9300 \
  -e "discovery.type=single-node" -e "xpack.security.enabled=false" \
  -e "ES_JAVA_OPTS=-Xms512m -Xmx512m" -v esdata:/usr/share/elasticsearch/data \
  docker.elastic.co/elasticsearch/elasticsearch:8.15.3
```

启动后后端会自动创建索引 `kb_vectors`（1024 维 dense_vector + cjk 分词 text + flattened metadata）。

### 3. 启动后端

API Key 通过环境变量提供（不写入仓库）：

```bash
# Linux / macOS
export DEEPSEEK_API_KEY=sk-xxx
export SILICONFLOW_API_KEY=sk-xxx

# Windows PowerShell
$env:DEEPSEEK_API_KEY="sk-xxx"
$env:SILICONFLOW_API_KEY="sk-xxx"

# 使用 Maven wrapper
./mvnw spring-boot:run
```
后端运行在 http://localhost:8080

### 4. 启动前端

```bash
cd frontend
npm install
npm run dev
```
前端运行在 http://localhost:5173，API 请求自动代理到 8080

### 5. 数据库

JPA `ddl-auto=update` 会自动建表。如果不需要自动建表，改为 `validate` 并手动执行：

```sql
CREATE TABLE chat_session (
    id VARCHAR(36) PRIMARY KEY,
    title VARCHAR(200),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE chat_message (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id VARCHAR(36) NOT NULL,
    role VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    citations TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (session_id) REFERENCES chat_session(id)
);

CREATE TABLE knowledge_document (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    doc_id VARCHAR(32),
    file_name VARCHAR(255),
    stored_path VARCHAR(500),
    file_size BIGINT,
    chunk_count INT,
    status VARCHAR(20),
    error_msg TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);
```

## 项目结构

```
demo/
├── pom.xml
├── CLAUDE.md
├── src/main/java/com/example/demo/
│   ├── DemoApplication.java              # 启动类
│   ├── config/
│   │   ├── AiConfig.java                 # 聊天模型 + Embedding 模型 + 记忆 Bean 配置
│   │   ├── EsConfig.java                 # ES RestClient / ElasticsearchClient / EmbeddingStore
│   │   ├── EsIndexInitializer.java       # 启动时创建向量索引（1024 维映射）
│   │   ├── RedisChatMemoryStore.java     # Redis 记忆存储实现
│   │   ├── Utf8SseFilter.java            # SSE 中文编码 Filter
│   │   └── WebConfig.java                # CORS 配置
│   ├── controller/
│   │   ├── ChatController.java           # SSE 流式聊天 API
│   │   ├── KnowledgeDocumentController.java  # 知识库文档上传/列表/删除 API
│   │   └── SessionController.java        # 会话 CRUD API
│   ├── dto/
│   │   ├── ChatRequest.java              # 聊天请求
│   │   ├── CitationVO.java               # 引用来源（文档名/片段/块号）
│   │   ├── DocumentVO.java               # 文档响应
│   │   ├── MessageVO.java                # 消息响应（含 citations）
│   │   └── SessionVO.java                # 会话响应
│   ├── entity/
│   │   ├── ChatMessage.java              # 消息 JPA 实体（含 citations 字段）
│   │   ├── ChatSession.java              # 会话 JPA 实体
│   │   └── KnowledgeDocument.java        # 文档元数据 JPA 实体
│   ├── repository/
│   │   ├── ChatMessageRepository.java
│   │   ├── ChatSessionRepository.java
│   │   └── KnowledgeDocumentRepository.java
│   └── service/
│       ├── ChatService.java              # 聊天核心逻辑 + RAG 上下文注入
│       ├── KnowledgeDocumentService.java # 文档入库链路（Tika→切分→向量化→ES）
│       ├── RagRetriever.java             # 混合检索（kNN + BM25 + RRF）
│       └── SessionService.java           # 会话管理
├── src/main/resources/
│   └── application.properties
└── frontend/                             # Vue3 前端
    ├── package.json
    ├── vite.config.js
    └── src/
        ├── App.vue                       # 主布局（对话/知识库 tab）
        ├── main.js
        ├── api/
        │   ├── chat.js                   # 会话 API + SSE 客户端
        │   └── documents.js              # 知识库文档 API
        └── components/
            ├── ChatWindow.vue            # 聊天窗口
            ├── KnowledgeBasePanel.vue    # 知识库管理面板
            ├── MessageBubble.vue         # 消息气泡（含引用块）
            └── SessionList.vue           # 会话列表 + 侧栏 tab
```

## 核心设计

### RAG 混合检索

- **文档入库**：上传 → 磁盘存储 → Tika 解析（PDF/DOCX/TXT/MD）→ 字符切分（800 字/块，100 重叠）→ BGE-M3 向量化（1024 维，按 32 分批）→ 写入 ES
- **双路召回**：kNN 向量检索（余弦相似度，minScore 0.35）+ BM25 全文检索（cjk 中文分词）
- **RRF 融合**：`ReciprocalRankFuser`（k=60）融合两路结果，取 top-5
- **检索上下文注入**：检索片段组装为 SystemMessage 置顶传入模型，仅本次调用有效，不持久化到记忆

### 引用溯源

- SSE 新增 `citations` 事件（token 流开始前发送）：文档名、块号、片段摘录
- 消息落库时 citations 以 JSON 存 MySQL `chat_message.citations` 列
- 前端在 AI 回答下方渲染「📎 引用来源」块

### 会话记忆隔离

- `RedisChatMemoryStore` 实现 LangChain4j 的 `ChatMemoryStore` 接口
- 每个会话以 `sessionId` 为 key（`chat:memory:{sessionId}`）在 Redis 中独立存储
- `ChatMemoryProvider` 按 sessionId 动态创建 `MessageWindowChatMemory`（保留最近 20 条消息）
- 7 天 TTL，自动过期

### 流式输出 (SSE)

- 前端通过 `fetch` + `ReadableStream` 消费 SSE 事件流
- 后端 `SseEmitter` 逐 token 推送，事件类型：`citations`（引用）、`token`（内容）、`done`（完成）、`error`（错误）

### 多存储策略

| 存储 | 内容 | 用途 |
|------|------|------|
| Redis | 最近 20 条消息 | 会话记忆上下文 |
| MySQL | 全部消息 + 文档元数据 | 历史记录与知识库管理 |
| Elasticsearch | 文档切块向量 + 原文 | 混合检索 |
| 本地磁盘 | 上传的原始文件 | 溯源与重处理 |

## API

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/sessions` | 创建新会话 |
| GET | `/api/sessions` | 获取会话列表 |
| DELETE | `/api/sessions/{id}` | 删除会话 |
| GET | `/api/sessions/{id}/messages` | 获取历史消息（含引用） |
| POST | `/api/chat/stream` | 发送消息 (SSE 流式) |
| POST | `/api/documents/upload` | 上传知识库文档 |
| GET | `/api/documents` | 文档列表 |
| DELETE | `/api/documents/{id}` | 删除文档（同步删向量） |

## 注意事项

- DeepSeek / 硅基流动 API Key 通过环境变量 `DEEPSEEK_API_KEY` / `SILICONFLOW_API_KEY` 提供，勿写入仓库
- 硅基流动 embedding 单请求限制 64 条，代码按 32 条分批
- MySQL 密码和 Redis 密码均为 `123456`，生产环境应修改
- Maven 版本需要 3.6.3+，建议使用项目自带的 `mvnw`
