from __future__ import annotations

import json
import re
from collections.abc import Iterable
from dataclasses import dataclass
from importlib.resources import files

from app.models import SkillHit, SkillMatchKind


@dataclass(frozen=True, slots=True)
class SkillDefinition:
    skill_id: str
    canonical: str
    aliases: tuple[str, ...]


def _load_catalog() -> tuple[SkillDefinition, ...]:
    path = files("app.data").joinpath("skill_aliases.json")
    raw = json.loads(path.read_text(encoding="utf-8"))
    return tuple(
        SkillDefinition(
            skill_id=item["id"],
            canonical=item["canonical"],
            aliases=tuple(sorted(set(item["aliases"]), key=len, reverse=True)),
        )
        for item in raw
    )


SKILL_CATALOG = _load_catalog()
SKILLS_BY_ID = {item.skill_id: item for item in SKILL_CATALOG}


def _pattern(alias: str) -> re.Pattern[str]:
    escaped = re.escape(alias)
    if re.search(r"[A-Za-z0-9]", alias):
        return re.compile(rf"(?<![A-Za-z0-9_]){escaped}(?![A-Za-z0-9_])", re.IGNORECASE)
    return re.compile(escaped, re.IGNORECASE)


_ALIAS_PATTERNS: tuple[tuple[SkillDefinition, str, re.Pattern[str]], ...] = tuple(
    (definition, alias, _pattern(alias))
    for definition in SKILL_CATALOG
    for alias in definition.aliases
)


def extract_skills(text: str, *, offset: int = 0) -> list[SkillHit]:
    """Find known skills and return one stable hit per canonical skill."""

    best: dict[str, SkillHit] = {}
    for definition, _alias, pattern in _ALIAS_PATTERNS:
        match = pattern.search(text)
        if match is None:
            continue
        matched = match.group(0)
        exact = matched.casefold() == definition.canonical.casefold()
        hit = SkillHit(
            skill_id=definition.skill_id,
            canonical=definition.canonical,
            matched_text=matched,
            match_kind=SkillMatchKind.EXACT if exact else SkillMatchKind.ALIAS,
            char_start=offset + match.start(),
            char_end=offset + match.end(),
            confidence=1.0 if exact else 0.96,
        )
        current = best.get(definition.skill_id)
        if current is None or (
            hit.match_kind == SkillMatchKind.EXACT and current.match_kind != SkillMatchKind.EXACT
        ) or hit.char_start < current.char_start:
            best[definition.skill_id] = hit
    return sorted(best.values(), key=lambda value: (value.char_start, value.skill_id))


def canonical_skill_ids(text: str) -> set[str]:
    return {hit.skill_id for hit in extract_skills(text)}


def canonical_names(skill_ids: Iterable[str]) -> list[str]:
    return [
        SKILLS_BY_ID[skill_id].canonical
        for skill_id in sorted(set(skill_ids))
        if skill_id in SKILLS_BY_ID
    ]
