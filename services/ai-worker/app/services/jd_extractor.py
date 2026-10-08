from __future__ import annotations

import hashlib
import re

from app.models import (
    Importance,
    JdExtractRequest,
    JdExtractResponse,
    JdRequirement,
    RequirementCategory,
    SkillHit,
)
from app.services.skills import extract_skills
from app.services.text_utils import clean_text, detect_language, stable_id

EXTRACTOR_VERSION = "rules-zh-en-v1"
_BULLET_RE = re.compile(r"^(?:[-*•·▪◦]+|\(?\d{1,2}[.)、]|[①-⑳])\s*")
_HEADING_RE = re.compile(
    r"^(岗位职责|职位描述|任职要求|岗位要求|职位要求|加分项|优先条件|福利待遇|job description|"
    r"responsibilities|requirements|qualifications|preferred qualifications|benefits)[:：]?$",
    re.I,
)
_MUST_RE = re.compile(
    r"(?:必须|硬性|至少|本科及以上|硕士及以上|博士|\d+\s*年(?:以上)?|"
    r"\bmust\b|\brequired\b|\bminimum\b|\bat least\b|\d+\+?\s*years?)",
    re.I,
)
_STRONG_RE = re.compile(r"(?:精通|熟练掌握|具备.+能力|proficient|strong knowledge|solid experience)", re.I)
_PREFERRED_RE = re.compile(r"(?:优先|加分|更佳|最好|preferred|nice to have|\bplus\b|bonus)", re.I)
_RESPONSIBILITY_RE = re.compile(
    r"(?:负责|参与|设计|开发|实现|维护|优化|搭建|协作|推动|"
    r"responsible|design|develop|build|implement|maintain|collaborate|deliver)",
    re.I,
)
_EDUCATION_RE = re.compile(r"(?:学历|本科|硕士|博士|大专|bachelor|master|ph\.?d|degree|education)", re.I)
_EXPERIENCE_RE = re.compile(
    r"(?:工作经验|开发经验|项目经验|实习经验|\d+\s*年|years? of experience|experience)", re.I
)
_LANGUAGE_RE = re.compile(
    r"(?:英语|英文|普通话|日语|CET[- ]?[46]|IELTS|TOEFL|English|Mandarin|Japanese)", re.I
)
_LOCATION_RE = re.compile(r"(?:工作地点|办公地点|坐标|base[ d]* in|location|on[- ]?site|remote|hybrid)", re.I)
_SIGNAL_RE = re.compile(
    r"(?:要求|能力|经验|熟悉|掌握|精通|了解|负责|参与|优先|学历|"
    r"required|qualification|experience|knowledge|skill|responsib|proficient|familiar)",
    re.I,
)


def _candidate_spans(original: str) -> list[tuple[str, int, int]]:
    spans: list[tuple[str, int, int]] = []
    for line_match in re.finditer(r"[^\r\n]+", original):
        raw_line = line_match.group(0)
        stripped_left = raw_line.lstrip()
        left_offset = len(raw_line) - len(stripped_left)
        bullet_match = _BULLET_RE.match(stripped_left)
        bullet_offset = bullet_match.end() if bullet_match else 0
        body = stripped_left[bullet_offset:].strip()
        start = line_match.start() + left_offset + bullet_offset
        start += len(stripped_left[bullet_offset:]) - len(stripped_left[bullet_offset:].lstrip())
        if not body or _HEADING_RE.fullmatch(body):
            continue
        # Long prose lines are split on sentence punctuation while keeping global offsets.
        if len(body) > 160:
            local_cursor = 0
            for part in re.split(r"(?<=[。；;!?！？])", body):
                cleaned = part.strip(" \t。；;!?！？")
                if len(cleaned) >= 3:
                    relative = body.find(cleaned, local_cursor)
                    spans.append((cleaned, start + relative, start + relative + len(cleaned)))
                    local_cursor = relative + len(cleaned)
        elif len(body) >= 3:
            spans.append((body, start, start + len(body)))
    return spans


def _category(text: str, skills: list[SkillHit]) -> RequirementCategory:
    if _EDUCATION_RE.search(text):
        return RequirementCategory.EDUCATION
    if _EXPERIENCE_RE.search(text):
        return RequirementCategory.EXPERIENCE
    if _LANGUAGE_RE.search(text):
        return RequirementCategory.LANGUAGE
    if _LOCATION_RE.search(text):
        return RequirementCategory.LOCATION
    if skills:
        return RequirementCategory.SKILL
    if _RESPONSIBILITY_RE.search(text):
        return RequirementCategory.RESPONSIBILITY
    return RequirementCategory.OTHER


def _importance(text: str) -> tuple[Importance, bool]:
    if _PREFERRED_RE.search(text):
        return Importance.PREFERRED, False
    hard = bool(_MUST_RE.search(text))
    if hard or _STRONG_RE.search(text):
        return Importance.MUST, hard
    return Importance.NORMAL, False


def _is_requirement(text: str, skills: list[SkillHit], category: RequirementCategory) -> bool:
    if skills or _SIGNAL_RE.search(text):
        return True
    return category in {
        RequirementCategory.EDUCATION,
        RequirementCategory.EXPERIENCE,
        RequirementCategory.LANGUAGE,
        RequirementCategory.LOCATION,
        RequirementCategory.RESPONSIBILITY,
    }


def extract_jd(request: JdExtractRequest) -> JdExtractResponse:
    original = request.text.replace("\r\n", "\n").replace("\r", "\n")
    normalized = clean_text(original)
    digest = hashlib.sha256(normalized.encode("utf-8")).hexdigest()
    requirements: list[JdRequirement] = []

    for text, start, end in _candidate_spans(original):
        skills = extract_skills(text, offset=start)
        category = _category(text, skills)
        if not _is_requirement(text, skills, category):
            continue
        importance, hard_condition = _importance(text)
        requirement_id = stable_id("req", digest, str(start), text)
        requirements.append(
            JdRequirement(
                requirement_id=requirement_id,
                text=text,
                category=category,
                importance=importance,
                hard_condition=hard_condition,
                skills=skills,
                evidence_id=stable_id("jd_ev", digest, str(start), text),
                char_start=start,
                char_end=end,
                confidence=0.97 if skills or hard_condition else 0.84,
            )
        )

    unique_skills: dict[str, SkillHit] = {}
    for requirement in requirements:
        for skill in requirement.skills:
            unique_skills.setdefault(skill.skill_id, skill)

    warnings: list[str] = []
    if not requirements:
        warnings.append("NO_REQUIREMENTS_EXTRACTED")
    if not unique_skills:
        warnings.append("NO_KNOWN_SKILLS_EXTRACTED")

    detected = detect_language(normalized) if request.language == "auto" else request.language
    return JdExtractResponse(
        document_id=stable_id("jd", digest),
        language=detected,
        requirements=requirements,
        skills=sorted(unique_skills.values(), key=lambda value: (value.char_start, value.skill_id)),
        warnings=warnings,
        extractor_version=EXTRACTOR_VERSION,
    )
