from __future__ import annotations

import json
from typing import Any

import httpx


class OpenAICompatibleProvider:
    """Small optional client for OpenAI-compatible chat completion endpoints.

    It is intentionally not enabled by default. Callers must still validate the
    response with Pydantic and run the fact-grounding gate before applying edits.
    """

    def __init__(self, *, base_url: str, api_key: str, model: str, timeout_seconds: float = 30.0):
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.model = model
        self.timeout_seconds = timeout_seconds

    async def generate_json(
        self,
        *,
        system_prompt: str,
        payload: dict[str, Any],
        json_schema: dict[str, Any],
    ) -> dict[str, Any]:
        request_body = {
            "model": self.model,
            "temperature": 0,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": json.dumps(payload, ensure_ascii=False)},
            ],
            "response_format": {
                "type": "json_schema",
                "json_schema": {"name": "careerlens_response", "strict": True, "schema": json_schema},
            },
        }
        headers = {"Authorization": f"Bearer {self.api_key}"}
        async with httpx.AsyncClient(timeout=self.timeout_seconds) as client:
            response = await client.post(
                f"{self.base_url}/chat/completions",
                headers=headers,
                json=request_body,
            )
            response.raise_for_status()
            body = response.json()
        content = body["choices"][0]["message"]["content"]
        if isinstance(content, dict):
            return content
        parsed = json.loads(content)
        if not isinstance(parsed, dict):
            raise ValueError("provider response must be a JSON object")
        return parsed
