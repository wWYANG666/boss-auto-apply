# CareerLens AI Worker

CareerLens 的离线优先 AI/文档处理服务。它不依赖在线大模型即可完成简历解析、JD 要求抽取、技能归一化、确定性匹配和事实约束建议；在线模型只是可选扩展点。

## 能力

- TXT：UTF-8、GB18030、UTF-16 基础文本提取。
- PDF：使用 `pypdf` 提取文本层并保留页码；扫描件返回 `PDF_HAS_NO_TEXT_LAYER`，由上层决定是否进入 OCR 队列。
- DOCX：直接读取 OOXML，不依赖 Microsoft Word 或 `python-docx`。
- 中文/英文 JD：抽取技能、职责、学历、经验、语言和地点要求，识别硬条件与加分项。
- 技能归一化：内置 Java、前端、Python、云原生和 AI 常用技能别名词典。
- 匹配：技能覆盖率 + 离线特征哈希语义相似度，输出分项分数、证据和硬条件冲突。
- 建议：只改写原简历已存在的事实；缺失技能返回 `MISSING_INFORMATION`，不生成虚构经历。

所有 ID、Embedding 和分数都是确定性的。同一输入与规则版本会得到同一结果。

## 本地启动

Python 3.11 及以上：

```powershell
python -m venv .venv
.venv\Scripts\Activate.ps1
python -m pip install -r requirements-dev.txt
uvicorn app.main:app --reload --port 8001
```

接口文档：<http://localhost:8001/docs>

健康检查：

```powershell
Invoke-RestMethod http://localhost:8001/internal/v1/health
```

## API

接口仅供 Spring Boot Core API 在内部网络调用。请求和响应字段使用 camelCase，未知字段会返回 `422 VALIDATION_ERROR`。

### `GET /internal/v1/health`

返回服务版本、运行模式与能力列表。

### `POST /internal/v1/resumes/parse`

使用 Base64 文件内容：

```json
{
  "fileName": "resume.docx",
  "mimeType": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
  "contentBase64": "BASE64_CONTENT"
}
```

也可在可信内部调用中传递提取好的文本：

```json
{"fileName":"resume.txt","rawText":"专业技能\nJava、Spring Boot、Redis"}
```

响应包含：

- `rawText`：规范化文本。
- `blocks`：带页码、字符区间、`evidenceId` 的文本块。
- `sections`：简历章节与引用的证据 ID。
- `skills`：规范化技能及原文位置。
- `warnings`：无文本层或编码回退等可处理告警。

上传上限为 15 MiB。PDF 解析依赖缺失时返回结构化错误 `PARSER_DEPENDENCY_MISSING`；这不会阻止 TXT、DOCX、JD 或匹配能力启动。

### `POST /internal/v1/jds/extract`

```json
{
  "text": "必须熟练掌握 Java 和 SpringBoot，熟悉 Redis 者优先",
  "language": "auto"
}
```

响应的 `requirements` 包含 `category`、`importance`、`hardCondition`、技能、JD 原文字符位置及 `evidenceId`。抽取结果设计为先由用户确认，再作为正式 JD 版本参与评分。

### `POST /internal/v1/embeddings/batch`

```json
{"texts":["Spring Boot Redis","Java 后端开发"],"dimension":256}
```

这是无需下载模型的稳定特征哈希向量，适合 MVP 的轻量语义召回和离线测试，不应宣传为神经网络 Embedding。

### `POST /internal/v1/matches/score`

```json
{
  "resumeElements": [
    {"elementId":"project-1","text":"使用 Spring Boot 与 Redis 开发预约系统","evidenceIds":["ev-1"]}
  ],
  "requirements": [
    {"requirementId":"req-1","text":"必须掌握 SpringBoot 与 Redis","category":"SKILL","importance":"MUST","hardCondition":true}
  ]
}
```

输出总分、技能/语义/证据/约束四个维度、每项判定、缺失技能、最佳简历证据及硬条件冲突。可传 `weights`，四项权重总和必须为 `1.0`。

### `POST /internal/v1/suggestions/generate`

```json
{
  "resumeElements": [
    {"elementId":"line-1","text":"我负责了 SpringBoot, Redis 后端开发","evidenceIds":["ev-line-1"]}
  ],
  "requirements": [
    {"requirementId":"req-1","text":"熟悉 Spring Boot、Redis 和 Kubernetes","category":"SKILL"}
  ]
}
```

`REWRITE` 建议包含 `test` + `replace` JSON Patch，应用前必须检查旧值；`MISSING_INFORMATION` 只有补充提示，没有替换文本和 Patch。每项建议均携带 `evidenceIds`。

## 测试和静态检查

```powershell
pytest
ruff check app tests
mypy app
```

## Docker

```powershell
docker build -t careerlens-ai-worker .
docker run --rm -p 8001:8001 careerlens-ai-worker
```

容器以非 root 用户运行，并内置健康检查。

## 在线模型扩展

`app/providers/openai_compatible.py` 提供 OpenAI-compatible JSON Schema 调用接口，但默认路由不使用它。接入时仍需：

1. 使用 Pydantic 校验模型输出。
2. 将简历/JD 当作不可信数据而不是指令。
3. 对改写前后技能集合做事实一致性校验。
4. 记录模型、耗时、Token 和结果哈希，不记录完整简历或密钥。
5. Provider 异常时回退到现有离线规则，保持核心流程可用。

## 与 Core API 的边界

- Worker 无状态且不访问业务数据库。
- Core API 负责鉴权、用户隔离、版本、事务、最终评分版本和审计。
- 原始文件由 Core API/对象存储管理；Worker 只处理单次请求内容。
- 外部投递自动化不在本服务内执行。
