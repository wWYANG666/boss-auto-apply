from __future__ import annotations

from app.models import JdExtractRequest, MatchScoreRequest
from app.services.jd_extractor import extract_jd
from app.services.matcher import score_match
from app.services.skills import extract_skills


def test_skill_alias_normalization_does_not_confuse_java_and_javascript():
    hits = extract_skills("Vue 3 + TypeScript + JavaScript")
    names = {hit.canonical for hit in hits}
    assert names == {"Vue.js", "TypeScript", "JavaScript"}
    assert "Java" not in names


def test_jd_extraction_is_deterministic():
    request = JdExtractRequest(text="必须掌握 Java 和 SpringBoot\n熟悉 Redis 者优先")
    first = extract_jd(request).model_dump(mode="json")
    second = extract_jd(request).model_dump(mode="json")
    assert first == second


def test_match_scoring_is_deterministic():
    request = MatchScoreRequest.model_validate(
        {
            "resumeText": "Java Spring Boot Redis backend development",
            "requirements": [{"text": "Java and Redis experience", "category": "SKILL"}],
        }
    )
    assert score_match(request).model_dump(mode="json") == score_match(request).model_dump(mode="json")
