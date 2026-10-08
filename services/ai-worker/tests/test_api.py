from __future__ import annotations

import base64


def test_health_contract(client):
    response = client.get("/internal/v1/health", headers={"X-Request-ID": "test-health"})
    assert response.status_code == 200
    assert response.headers["X-Request-ID"] == "test-health"
    body = response.json()
    assert body["status"] == "ok"
    assert body["providerMode"] == "offline-rules"
    assert "match.explainable" in body["capabilities"]


def test_strict_validation_rejects_unknown_fields(client):
    response = client.post(
        "/internal/v1/jds/extract",
        json={"text": "熟悉 Java", "unexpected": True},
    )
    assert response.status_code == 422
    body = response.json()
    assert body["error"]["code"] == "VALIDATION_ERROR"
    assert body["error"]["requestId"]


def test_parse_utf8_text_and_sections(client):
    content = (
        "个人简介\n应届软件工程毕业生\n专业技能\n熟悉 Java、SpringBoot、Redis\n"
        "项目经历\n使用 Docker 部署校园预约系统"
    )
    response = client.post(
        "/internal/v1/resumes/parse",
        json={
            "fileName": "resume.txt",
            "mimeType": "text/plain",
            "contentBase64": base64.b64encode(content.encode()).decode(),
        },
    )
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["fileType"] == "TXT"
    assert body["metadata"]["parser"] == "text:utf-8-sig"
    assert {skill["canonical"] for skill in body["skills"]} >= {
        "Java",
        "Spring Boot",
        "Redis",
        "Docker",
    }
    assert {section["type"] for section in body["sections"]} >= {"PROFILE", "SKILLS", "PROJECT"}
    evidence_ids = [block["evidenceId"] for block in body["blocks"]]
    assert len(evidence_ids) == len(set(evidence_ids))


def test_parse_docx_without_python_docx_dependency(client, simple_docx):
    response = client.post(
        "/internal/v1/resumes/parse",
        json={
            "fileName": "resume.docx",
            "contentBase64": base64.b64encode(simple_docx).decode(),
        },
    )
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["metadata"]["parser"] == "docx-xml"
    assert "负责校园预约平台后端开发" in body["rawText"]
    assert {skill["canonical"] for skill in body["skills"]} >= {
        "Spring Boot",
        "PostgreSQL",
        "Docker",
    }


def test_parse_rejects_unsupported_file_type(client):
    response = client.post(
        "/internal/v1/resumes/parse",
        json={"fileName": "resume.exe", "rawText": "hello"},
    )
    assert response.status_code == 422
    assert response.json()["error"]["code"] == "UNSUPPORTED_DOCUMENT_TYPE"


def test_extract_chinese_and_english_jd_requirements(client):
    text = """岗位要求：
    - 本科及以上学历，计算机相关专业
    - 必须熟练掌握 Java、SpringBoot 和 Redis，至少 1 年项目经验
    - 熟悉 Postgres 或 MySQL 者优先
    Responsibilities:
    - Develop REST APIs and collaborate with frontend engineers.
    - Docker and Kubernetes experience is a plus.
    """
    response = client.post("/internal/v1/jds/extract", json={"text": text})
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["language"] == "mixed"
    assert len(body["requirements"]) >= 5
    required = next(item for item in body["requirements"] if "必须" in item["text"])
    assert required["importance"] == "MUST"
    assert required["hardCondition"] is True
    assert {skill["canonical"] for skill in required["skills"]} >= {"Java", "Spring Boot", "Redis"}
    preferred = next(item for item in body["requirements"] if "优先" in item["text"])
    assert preferred["importance"] == "PREFERRED"
    english_preferred = next(item for item in body["requirements"] if "plus" in item["text"])
    assert english_preferred["importance"] == "PREFERRED"


def test_embeddings_are_deterministic_and_semantically_skill_aware(client):
    payload = {
        "texts": ["熟悉 SpringBoot 与 Redis", "Spring Boot Redis experience", "视觉设计作品"],
        "dimension": 64,
    }
    first = client.post("/internal/v1/embeddings/batch", json=payload)
    second = client.post("/internal/v1/embeddings/batch", json=payload)
    assert first.status_code == 200
    assert first.json() == second.json()
    body = first.json()
    assert body["dimension"] == 64
    assert all(len(item["vector"]) == 64 for item in body["vectors"])
    assert body["vectors"][0]["norm"] == 1.0


def test_matching_returns_evidence_and_hard_conflicts(client):
    response = client.post(
        "/internal/v1/matches/score",
        json={
            "resumeElements": [
                {
                    "elementId": "project-1",
                    "text": "使用 SpringBoot、Redis 与 MySQL 开发预约系统，并用 Docker 部署",
                    "evidenceIds": ["ev-project-1"],
                }
            ],
            "requirements": [
                {
                    "requirementId": "req-java",
                    "text": "必须熟练掌握 Spring Boot 和 Redis",
                    "category": "SKILL",
                    "importance": "MUST",
                    "hardCondition": True,
                },
                {
                    "requirementId": "req-k8s",
                    "text": "必须掌握 Kubernetes",
                    "category": "SKILL",
                    "importance": "MUST",
                    "hardCondition": True,
                },
            ],
        },
    )
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["deterministic"] is True
    matched = next(item for item in body["items"] if item["requirementId"] == "req-java")
    assert matched["verdict"] in {"EXACT", "ALIAS"}
    assert matched["evidence"]["evidenceId"] == "ev-project-1"
    assert set(matched["matchedSkills"]) == {"Spring Boot", "Redis"}
    missing = next(item for item in body["items"] if item["requirementId"] == "req-k8s")
    assert missing["verdict"] == "CONFLICT"
    assert "req-k8s" in body["hardConflicts"]


def test_suggestions_only_rewrite_supported_facts(client):
    response = client.post(
        "/internal/v1/suggestions/generate",
        json={
            "resumeElements": [
                {
                    "elementId": "line-1",
                    "text": "我负责了 SpringBoot, Redis 后端开发",
                    "evidenceIds": ["ev-line-1"],
                }
            ],
            "requirements": [
                {
                    "requirementId": "req-1",
                    "text": "熟悉 Spring Boot、Redis 和 Kubernetes",
                    "category": "SKILL",
                    "importance": "MUST",
                    "evidenceId": "jd-ev-1",
                }
            ],
        },
    )
    assert response.status_code == 200, response.text
    body = response.json()
    rewrite = next(item for item in body["suggestions"] if item["kind"] == "REWRITE")
    assert rewrite["targetElementId"] == "line-1"
    assert "Spring Boot" in rewrite["after"]
    assert "Kubernetes" not in rewrite["after"]
    assert rewrite["evidenceIds"] == ["ev-line-1"]
    assert [operation["op"] for operation in rewrite["patch"]] == ["test", "replace"]
    missing = next(item for item in body["suggestions"] if item["kind"] == "MISSING_INFORMATION")
    assert "Kubernetes" in missing["reason"]
    assert missing["after"] is None
    assert missing["patch"] == []
    assert body["grounded"] is True
