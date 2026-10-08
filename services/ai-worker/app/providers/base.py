from __future__ import annotations

from typing import Any, Protocol


class StructuredTextProvider(Protocol):
    """Extension point for providers that return schema-constrained JSON."""

    async def generate_json(
        self,
        *,
        system_prompt: str,
        payload: dict[str, Any],
        json_schema: dict[str, Any],
    ) -> dict[str, Any]: ...
