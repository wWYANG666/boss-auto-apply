from __future__ import annotations

import re

from app.models import (
    JsonPatchOperation,
    ResumeSuggestion,
    SuggestionGenerateRequest,
    SuggestionGenerateResponse,
    SuggestionKind,
)
from app.services.skills import canonical_names, extract_skills
from app.services.text_utils import clean_text, detect_language, stable_id


def _json_pointer(value: str) -> str:
    return value.replace("~", "~0").replace("/", "~1")


def _canonicalize_aliases(text: str) -> str:
    hits = extract_skills(text)
    output = text
    for hit in sorted(hits, key=lambda value: value.char_start, reverse=True):
        if hit.matched_text.casefold() == hit.canonical.casefold():
            continue
        output = output[: hit.char_start] + hit.canonical + output[hit.char_end :]
    return output


def _fact_preserving_rewrite(text: str) -> str:
    value = clean_text(text)
    value = re.sub(r"^(?:本人|我)\s*", "", value)
    value = re.sub(r"^(负责|参与|使用|完成|实现)了", r"\1", value)
    value = _canonicalize_aliases(value)
    value = re.sub(r"\s*[,，]\s*", "，", value)
    value = re.sub(r"\s*[;；]\s*", "；", value)
    value = re.sub(r"([。；;，,])\1+", r"\1", value)
    return value.strip()


def generate_suggestions(request: SuggestionGenerateRequest) -> SuggestionGenerateResponse:
    suggestions: list[ResumeSuggestion] = []
    requirement_skill_map: dict[str, set[str]] = {}
    for index, requirement in enumerate(request.requirements):
        requirement_id = requirement.requirement_id or stable_id("req", str(index), requirement.text)
        hits = requirement.skills or extract_skills(requirement.text)
        requirement_skill_map[requirement_id] = {hit.skill_id for hit in hits}

    resume_skill_ids: set[str] = set()
    for element in request.resume_elements:
        before = element.text
        before_hits = extract_skills(before)
        before_skill_ids = {hit.skill_id for hit in before_hits}
        resume_skill_ids.update(before_skill_ids)
        after = _fact_preserving_rewrite(before)
        if after == before:
            continue

        # The rewrite gate prevents an optional provider/refactor from adding an unsupported skill.
        after_skill_ids = {hit.skill_id for hit in extract_skills(after)}
        if not after_skill_ids.issubset(before_skill_ids):
            continue
        evidence_ids = element.evidence_ids or [stable_id("resume_ev", element.element_id, before)]
        related = [
            requirement_id
            for requirement_id, required_ids in requirement_skill_map.items()
            if required_ids & before_skill_ids
        ]
        language = detect_language(before)
        reason = (
            "统一技术名称并精简口语化表达，未增加原文之外的经历或技能。"
            if language != "en"
            else "Normalizes technology names and removes filler without adding experience or skills."
        )
        path = f"/elements/{_json_pointer(element.element_id)}/text"
        suggestions.append(
            ResumeSuggestion(
                suggestion_id=stable_id("sug", element.element_id, before, after),
                kind=SuggestionKind.REWRITE,
                target_element_id=element.element_id,
                before=before,
                after=after,
                reason=reason,
                evidence_ids=evidence_ids,
                supporting_facts=canonical_names(before_skill_ids),
                related_requirement_ids=related,
                patch=[
                    JsonPatchOperation(op="test", path=path, value=before),
                    JsonPatchOperation(op="replace", path=path, value=after),
                ],
                confidence=0.98,
            )
        )
        if len(suggestions) >= request.max_suggestions:
            break

    if len(suggestions) < request.max_suggestions:
        for index, requirement in enumerate(request.requirements):
            requirement_id = requirement.requirement_id or stable_id("req", str(index), requirement.text)
            required_ids = requirement_skill_map[requirement_id]
            missing_ids = required_ids - resume_skill_ids
            if not missing_ids:
                continue
            names = canonical_names(missing_ids)
            label = "、".join(names)
            language = detect_language(requirement.text)
            reason = (
                f"简历中未找到岗位要求的 {label}；如确有相关经历，请补充可核验的事实。"
                if language != "en"
                else f"The resume has no evidence for {', '.join(names)}; add verified facts if applicable."
            )
            evidence_ids = [requirement.evidence_id] if requirement.evidence_id else []
            suggestions.append(
                ResumeSuggestion(
                    suggestion_id=stable_id("sug_missing", requirement_id, *sorted(missing_ids)),
                    kind=SuggestionKind.MISSING_INFORMATION,
                    target_element_id=None,
                    before=None,
                    after=None,
                    reason=reason,
                    evidence_ids=evidence_ids,
                    supporting_facts=[],
                    related_requirement_ids=[requirement_id],
                    patch=[],
                    confidence=0.99,
                )
            )
            if len(suggestions) >= request.max_suggestions:
                break

    warnings = []
    if not suggestions:
        warnings.append("NO_FACT_GROUNDED_SUGGESTIONS")
    return SuggestionGenerateResponse(
        suggestions=suggestions,
        provider_mode="offline-rules",
        grounded=True,
        warnings=warnings,
    )
