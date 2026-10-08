import asyncio

from app.models import ResumeElement, SuggestionGenerateRequest
from app.services.provider_suggestions import generate_with_provider, grounded


def test_fact_gate_rejects_new_numbers_and_skills():
    assert not grounded("Java API", "Java API with Redis")
    assert not grounded("实现Java API", "实现Java API，性能提升90%")
    assert grounded("使用Java实现API", "实现Java API")


def test_online_missing_config_is_explicit_fallback(monkeypatch):
    monkeypatch.setenv("LLM_MODE", "online")
    monkeypatch.delenv("LLM_API_KEY", raising=False)
    result = asyncio.run(
        generate_with_provider(
            SuggestionGenerateRequest(
                resume_elements=[ResumeElement(element_id="summary", text="使用了 Java 开发")]
            )
        )
    )
    assert result.provider_mode == "offline-fallback"
    assert "ONLINE_PROVIDER_FAILED" in result.warnings
