from __future__ import annotations

from enum import StrEnum
from typing import Annotated, Any, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator


def _to_camel(value: str) -> str:
    head, *tail = value.split("_")
    return head + "".join(part.capitalize() for part in tail)


class ApiModel(BaseModel):
    """Base model for a strict, camelCase JSON contract."""

    model_config = ConfigDict(
        alias_generator=_to_camel,
        populate_by_name=True,
        extra="forbid",
        str_strip_whitespace=True,
    )


NonEmptyText = Annotated[str, Field(min_length=1)]


class FileType(StrEnum):
    TXT = "TXT"
    PDF = "PDF"
    DOCX = "DOCX"


class SectionType(StrEnum):
    PROFILE = "PROFILE"
    EDUCATION = "EDUCATION"
    EXPERIENCE = "EXPERIENCE"
    PROJECT = "PROJECT"
    SKILLS = "SKILLS"
    AWARDS = "AWARDS"
    OTHER = "OTHER"


class RequirementCategory(StrEnum):
    SKILL = "SKILL"
    EDUCATION = "EDUCATION"
    EXPERIENCE = "EXPERIENCE"
    LANGUAGE = "LANGUAGE"
    LOCATION = "LOCATION"
    RESPONSIBILITY = "RESPONSIBILITY"
    OTHER = "OTHER"


class Importance(StrEnum):
    MUST = "MUST"
    PREFERRED = "PREFERRED"
    NORMAL = "NORMAL"


class SkillMatchKind(StrEnum):
    EXACT = "EXACT"
    ALIAS = "ALIAS"


class Verdict(StrEnum):
    EXACT = "EXACT"
    ALIAS = "ALIAS"
    SEMANTIC = "SEMANTIC"
    PARTIAL = "PARTIAL"
    MISSING = "MISSING"
    CONFLICT = "CONFLICT"


class SuggestionKind(StrEnum):
    REWRITE = "REWRITE"
    MISSING_INFORMATION = "MISSING_INFORMATION"


class HealthResponse(ApiModel):
    status: Literal["ok"] = "ok"
    service: Literal["careerlens-ai-worker"] = "careerlens-ai-worker"
    version: str
    provider_mode: str
    capabilities: list[str]


class ResumeParseRequest(ApiModel):
    file_name: str = Field(default="resume.txt", min_length=1, max_length=255)
    mime_type: str | None = Field(default=None, max_length=120)
    content_base64: str | None = Field(default=None, min_length=1)
    raw_text: str | None = Field(default=None, min_length=1, max_length=2_000_000)

    @model_validator(mode="after")
    def validate_source(self) -> ResumeParseRequest:
        if self.content_base64 is None and self.raw_text is None:
            raise ValueError("contentBase64 or rawText is required")
        return self


class DocumentBlock(ApiModel):
    evidence_id: str
    page: int = Field(ge=1)
    text: str
    char_start: int = Field(ge=0)
    char_end: int = Field(ge=0)
    bbox: tuple[float, float, float, float] | None = None


class SkillHit(ApiModel):
    skill_id: str
    canonical: str
    matched_text: str
    match_kind: SkillMatchKind
    char_start: int = Field(ge=0)
    char_end: int = Field(ge=0)
    confidence: float = Field(ge=0, le=1)


class ResumeSection(ApiModel):
    element_id: str
    type: SectionType
    heading: str | None = None
    text: str
    evidence_ids: list[str]
    confidence: float = Field(ge=0, le=1)


class ParseMetadata(ApiModel):
    parser: str
    page_count: int = Field(ge=1)
    char_count: int = Field(ge=0)
    sha256: str


class ResumeParseResponse(ApiModel):
    structured_content: dict[str, Any] = Field(default_factory=dict)
    document_id: str
    file_name: str
    file_type: FileType
    raw_text: str
    blocks: list[DocumentBlock]
    sections: list[ResumeSection]
    skills: list[SkillHit]
    warnings: list[str]
    metadata: ParseMetadata


class JdExtractRequest(ApiModel):
    text: str = Field(min_length=1, max_length=500_000)
    language: Literal["auto", "zh", "en"] = "auto"


class JdRequirement(ApiModel):
    requirement_id: str
    text: str
    category: RequirementCategory
    importance: Importance
    hard_condition: bool
    skills: list[SkillHit]
    evidence_id: str
    char_start: int = Field(ge=0)
    char_end: int = Field(ge=0)
    confidence: float = Field(ge=0, le=1)


class JdExtractResponse(ApiModel):
    document_id: str
    language: Literal["zh", "en", "mixed"]
    requirements: list[JdRequirement]
    skills: list[SkillHit]
    warnings: list[str]
    extractor_version: str


class EmbeddingBatchRequest(ApiModel):
    texts: list[str] = Field(min_length=1, max_length=256)
    dimension: int = Field(default=256, ge=32, le=2048)

    @model_validator(mode="after")
    def validate_texts(self) -> EmbeddingBatchRequest:
        if any(not value.strip() for value in self.texts):
            raise ValueError("texts must not contain blank values")
        if any(len(value) > 100_000 for value in self.texts):
            raise ValueError("each text must be at most 100000 characters")
        return self


class EmbeddingVector(ApiModel):
    index: int = Field(ge=0)
    vector: list[float]
    norm: float = Field(ge=0)


class EmbeddingBatchResponse(ApiModel):
    vectors: list[EmbeddingVector]
    dimension: int
    algorithm: str


class ResumeElement(ApiModel):
    element_id: str = Field(min_length=1, max_length=200)
    text: str = Field(min_length=1, max_length=100_000)
    evidence_ids: list[str] = Field(default_factory=list, max_length=100)


class RequirementInput(ApiModel):
    requirement_id: str | None = None
    text: str = Field(min_length=1, max_length=100_000)
    category: RequirementCategory = RequirementCategory.OTHER
    importance: Importance = Importance.NORMAL
    hard_condition: bool = False
    skills: list[SkillHit] = Field(default_factory=list)
    evidence_id: str | None = None
    char_start: int | None = Field(default=None, ge=0)
    char_end: int | None = Field(default=None, ge=0)
    confidence: float | None = Field(default=None, ge=0, le=1)


class MatchWeights(ApiModel):
    skills: float = Field(default=0.4, ge=0, le=1)
    semantic: float = Field(default=0.3, ge=0, le=1)
    evidence: float = Field(default=0.2, ge=0, le=1)
    constraints: float = Field(default=0.1, ge=0, le=1)

    @model_validator(mode="after")
    def validate_total(self) -> MatchWeights:
        total = self.skills + self.semantic + self.evidence + self.constraints
        if abs(total - 1.0) > 0.0001:
            raise ValueError("match weights must sum to 1.0")
        return self


class MatchScoreRequest(ApiModel):
    resume_text: str | None = Field(default=None, max_length=500_000)
    resume_elements: list[ResumeElement] = Field(default_factory=list, max_length=500)
    requirements: list[RequirementInput] = Field(min_length=1, max_length=500)
    weights: MatchWeights = Field(default_factory=MatchWeights)
    rule_version: str = Field(default="offline-v1", min_length=1, max_length=50)

    @model_validator(mode="after")
    def validate_resume(self) -> MatchScoreRequest:
        if not (self.resume_text and self.resume_text.strip()) and not self.resume_elements:
            raise ValueError("resumeText or resumeElements is required")
        return self


class MatchEvidence(ApiModel):
    evidence_id: str
    resume_element_id: str
    resume_quote: str
    jd_quote: str
    similarity: float = Field(ge=0, le=1)


class MatchItem(ApiModel):
    requirement_id: str
    requirement: str
    category: RequirementCategory
    importance: Importance
    hard_condition: bool
    verdict: Verdict
    score: float = Field(ge=0, le=100)
    confidence: float = Field(ge=0, le=1)
    matched_skills: list[str]
    missing_skills: list[str]
    evidence: MatchEvidence | None


class ScoreDimension(ApiModel):
    name: Literal["skills", "semantic", "evidence", "constraints"]
    score: float = Field(ge=0, le=100)
    weight: float = Field(ge=0, le=1)


class MatchScoreResponse(ApiModel):
    overall_score: float = Field(ge=0, le=100)
    dimensions: list[ScoreDimension]
    items: list[MatchItem]
    hard_conflicts: list[str]
    algorithm_version: str
    deterministic: Literal[True] = True


class SuggestionGenerateRequest(ApiModel):
    resume_elements: list[ResumeElement] = Field(min_length=1, max_length=500)
    requirements: list[RequirementInput] = Field(default_factory=list, max_length=500)
    max_suggestions: int = Field(default=10, ge=1, le=50)


class JsonPatchOperation(ApiModel):
    op: Literal["test", "replace"]
    path: str
    value: str


class ResumeSuggestion(ApiModel):
    suggestion_id: str
    kind: SuggestionKind
    target_element_id: str | None
    before: str | None
    after: str | None
    reason: str
    evidence_ids: list[str]
    supporting_facts: list[str]
    related_requirement_ids: list[str]
    patch: list[JsonPatchOperation]
    confidence: float = Field(ge=0, le=1)


class SuggestionGenerateResponse(ApiModel):
    suggestions: list[ResumeSuggestion]
    provider_mode: str
    grounded: Literal[True] = True
    warnings: list[str]


class ErrorBody(ApiModel):
    code: str
    message: str
    request_id: str | None = None


class ErrorResponse(ApiModel):
    error: ErrorBody
