from __future__ import annotations

import re
from dataclasses import dataclass

from app.models import (
    Importance,
    MatchEvidence,
    MatchItem,
    MatchScoreRequest,
    MatchScoreResponse,
    RequirementCategory,
    ResumeElement,
    ScoreDimension,
    SkillMatchKind,
    Verdict,
)
from app.services.embeddings import ALGORITHM, text_similarity
from app.services.skills import canonical_names, extract_skills
from app.services.text_utils import excerpt, stable_id


@dataclass(slots=True)
class _ElementFeatures:
    element: ResumeElement
    skill_ids: set[str]
    skill_kinds: dict[str, SkillMatchKind]


def _resume_elements(request: MatchScoreRequest) -> list[ResumeElement]:
    if request.resume_elements:
        return request.resume_elements
    assert request.resume_text is not None
    return [
        ResumeElement(
            element_id=stable_id("resume", request.resume_text),
            text=request.resume_text,
            evidence_ids=[stable_id("resume_ev", request.resume_text)],
        )
    ]


def _importance_weight(value: Importance) -> float:
    if value == Importance.MUST:
        return 1.5
    if value == Importance.PREFERRED:
        return 1.2
    return 1.0


def _verdict(
    *,
    coverage: float,
    similarity: float,
    all_exact: bool,
    has_required_skills: bool,
    hard_condition: bool,
    score: float,
) -> Verdict:
    if hard_condition and score < 25:
        return Verdict.CONFLICT
    if has_required_skills:
        if coverage >= 0.999:
            return Verdict.EXACT if all_exact else Verdict.ALIAS
        if coverage > 0 or similarity >= 0.30:
            return Verdict.PARTIAL
        return Verdict.MISSING
    if similarity >= 0.52:
        return Verdict.PARTIAL
    if similarity >= 0.24:
        return Verdict.PARTIAL
    return Verdict.MISSING


def score_match(request: MatchScoreRequest) -> MatchScoreResponse:
    elements = _resume_elements(request)
    features: list[_ElementFeatures] = []
    for element in elements:
        positive = "；".join(
            clause
            for clause in re.split(r"[。；;，,\n]", element.text)
            if not re.search(r"未|没有|不熟悉|不了解|never|\bnot\b|\bwithout\b|\bno\b", clause, re.I)
        )
        element = element.model_copy(update={"text": positive})
        hits = extract_skills(positive)
        features.append(
            _ElementFeatures(
                element=element,
                skill_ids={hit.skill_id for hit in hits},
                skill_kinds={hit.skill_id: hit.match_kind for hit in hits},
            )
        )

    items: list[MatchItem] = []
    skill_dimension_values: list[float] = []
    semantic_values: list[float] = []
    evidence_values: list[float] = []
    constraint_values: list[float] = []
    weighted_item_total = 0.0
    item_weight_total = 0.0

    for index, requirement in enumerate(request.requirements):
        requirement_id = requirement.requirement_id or stable_id("req", str(index), requirement.text)
        required_hits = requirement.skills or extract_skills(requirement.text)
        required_ids = {hit.skill_id for hit in required_hits}

        best_feature = features[0]
        best_similarity = -1.0
        best_coverage = 0.0
        for feature in features:
            similarity = text_similarity(requirement.text, feature.element.text)
            coverage = len(required_ids & feature.skill_ids) / len(required_ids) if required_ids else 0.0
            rank = (coverage * 0.72 + similarity * 0.28) if required_ids else similarity
            best_rank = best_coverage * 0.72 + best_similarity * 0.28 if required_ids else best_similarity
            if rank > best_rank:
                best_feature = feature
                best_similarity = similarity
                best_coverage = coverage

        best_similarity = max(0.0, best_similarity)
        if required_ids and best_coverage == 0:
            best_similarity = 0.0
        matched_ids = required_ids & best_feature.skill_ids
        missing_ids = required_ids - best_feature.skill_ids
        all_exact = bool(required_ids) and all(
            best_feature.skill_kinds.get(skill_id) == SkillMatchKind.EXACT for skill_id in required_ids
        )

        if required_ids:
            item_score = (best_coverage * 0.78 + best_similarity * 0.22) * 100
            skill_dimension_values.append(best_coverage * 100)
        else:
            item_score = best_similarity * 100
        item_score = round(max(0.0, min(100.0, item_score)), 2)
        semantic_values.append(best_similarity * 100)

        evidence_quality = max(best_coverage, best_similarity)
        evidence_values.append(evidence_quality * 100)
        if requirement.category in {
            RequirementCategory.EDUCATION,
            RequirementCategory.EXPERIENCE,
            RequirementCategory.LANGUAGE,
            RequirementCategory.LOCATION,
        }:
            constraint_values.append(item_score)

        verdict = _verdict(
            coverage=best_coverage,
            similarity=best_similarity,
            all_exact=all_exact,
            has_required_skills=bool(required_ids),
            hard_condition=requirement.hard_condition,
            score=item_score,
        )
        evidence = None
        if matched_ids or best_similarity >= 0.16:
            evidence_id = (
                best_feature.element.evidence_ids[0]
                if best_feature.element.evidence_ids
                else stable_id("resume_ev", best_feature.element.element_id, best_feature.element.text)
            )
            evidence = MatchEvidence(
                evidence_id=evidence_id,
                resume_element_id=best_feature.element.element_id,
                resume_quote=excerpt(best_feature.element.text),
                jd_quote=excerpt(requirement.text),
                similarity=round(best_similarity, 4),
            )

        confidence = 0.56 + max(best_coverage, best_similarity) * 0.39
        items.append(
            MatchItem(
                requirement_id=requirement_id,
                requirement=requirement.text,
                category=requirement.category,
                importance=requirement.importance,
                hard_condition=requirement.hard_condition,
                verdict=verdict,
                score=item_score,
                confidence=round(min(0.98, confidence), 4),
                matched_skills=canonical_names(matched_ids),
                missing_skills=canonical_names(missing_ids),
                evidence=evidence,
            )
        )
        item_weight = _importance_weight(requirement.importance)
        weighted_item_total += item_score * item_weight
        item_weight_total += item_weight

    semantic_score = sum(semantic_values) / len(semantic_values)
    skill_score = (
        sum(skill_dimension_values) / len(skill_dimension_values)
        if skill_dimension_values
        else semantic_score
    )
    evidence_score = sum(evidence_values) / len(evidence_values)
    constraint_score = (
        sum(constraint_values) / len(constraint_values)
        if constraint_values
        else weighted_item_total / item_weight_total
    )
    weights = request.weights
    overall = (
        skill_score * weights.skills
        + semantic_score * weights.semantic
        + evidence_score * weights.evidence
        + constraint_score * weights.constraints
    )
    dimensions = [
        ScoreDimension(name="skills", score=round(skill_score, 2), weight=weights.skills),
        ScoreDimension(name="semantic", score=round(semantic_score, 2), weight=weights.semantic),
        ScoreDimension(name="evidence", score=round(evidence_score, 2), weight=weights.evidence),
        ScoreDimension(name="constraints", score=round(constraint_score, 2), weight=weights.constraints),
    ]
    hard_conflicts = [item.requirement_id for item in items if item.hard_condition and item.score < 50]
    return MatchScoreResponse(
        overall_score=round(max(0.0, min(100.0, overall)), 2),
        dimensions=dimensions,
        items=items,
        hard_conflicts=hard_conflicts,
        algorithm_version=f"{request.rule_version}+{ALGORITHM}",
        deterministic=True,
    )
