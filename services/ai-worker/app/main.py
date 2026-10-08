from __future__ import annotations

import logging
import os
import re
import uuid

from fastapi import APIRouter, FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.middleware.base import RequestResponseEndpoint
from starlette.responses import Response

from app import __version__
from app.models import (
    EmbeddingBatchRequest,
    EmbeddingBatchResponse,
    EmbeddingVector,
    ErrorBody,
    ErrorResponse,
    HealthResponse,
    JdExtractRequest,
    JdExtractResponse,
    MatchScoreRequest,
    MatchScoreResponse,
    ResumeParseRequest,
    ResumeParseResponse,
    SuggestionGenerateRequest,
    SuggestionGenerateResponse,
)
from app.services.embeddings import ALGORITHM, embed_text, vector_norm
from app.services.jd_extractor import extract_jd
from app.services.matcher import score_match
from app.services.parsers import DocumentParseError, parse_resume

logging.basicConfig(level=os.getenv("LOG_LEVEL", "INFO").upper())
logger = logging.getLogger("careerlens.ai_worker")
_REQUEST_ID_RE = re.compile(r"^[A-Za-z0-9._:-]{1,100}$")


app = FastAPI(
    title="CareerLens AI Worker",
    description="Offline-first document parsing, JD extraction, matching and grounded suggestions.",
    version=__version__,
    docs_url="/docs",
    redoc_url=None,
)
router = APIRouter(prefix="/internal/v1")


def _error(code: str, message: str, request: Request, status_code: int) -> JSONResponse:
    payload = ErrorResponse(
        error=ErrorBody(
            code=code,
            message=message,
            request_id=getattr(request.state, "request_id", None),
        )
    )
    return JSONResponse(status_code=status_code, content=payload.model_dump(mode="json", by_alias=True))


@app.middleware("http")
async def request_context(request: Request, call_next: RequestResponseEndpoint) -> Response:
    supplied = request.headers.get("X-Request-ID", "")
    request_id = supplied if _REQUEST_ID_RE.fullmatch(supplied) else uuid.uuid4().hex
    request.state.request_id = request_id
    response = await call_next(request)
    response.headers["X-Request-ID"] = request_id
    return response


@app.exception_handler(RequestValidationError)
async def validation_exception_handler(request: Request, exc: RequestValidationError) -> JSONResponse:
    first = exc.errors()[0] if exc.errors() else {"loc": (), "msg": "invalid request"}
    location = ".".join(str(item) for item in first.get("loc", ()) if item != "body")
    message = str(first.get("msg", "invalid request"))
    if location:
        message = f"{location}: {message}"
    return _error("VALIDATION_ERROR", message, request, 422)


@app.exception_handler(DocumentParseError)
async def document_exception_handler(request: Request, exc: DocumentParseError) -> JSONResponse:
    return _error(exc.code, str(exc), request, 422)


@app.exception_handler(Exception)
async def unexpected_exception_handler(request: Request, exc: Exception) -> JSONResponse:
    logger.exception("Unhandled AI worker error request_id=%s", request.state.request_id, exc_info=exc)
    return _error("INTERNAL_ERROR", "an unexpected processing error occurred", request, 500)


@router.get("/health", response_model=HealthResponse)
def health() -> HealthResponse:
    return HealthResponse(
        version=__version__,
        provider_mode="offline-rules",
        capabilities=[
            "resume.parse.txt",
            "resume.parse.pdf",
            "resume.parse.docx",
            "resume.structure.zh-en",
            "jd.extract.zh-en",
            "embedding.offline",
            "match.explainable",
            "suggestion.grounded",
        ],
    )


@router.post(
    "/resumes/parse",
    response_model=ResumeParseResponse,
    responses={422: {"model": ErrorResponse}},
)
def resume_parse(request: ResumeParseRequest) -> ResumeParseResponse:
    return parse_resume(request)


@router.post(
    "/jds/extract",
    response_model=JdExtractResponse,
    responses={422: {"model": ErrorResponse}},
)
def jd_extract(request: JdExtractRequest) -> JdExtractResponse:
    return extract_jd(request)


@router.post(
    "/embeddings/batch",
    response_model=EmbeddingBatchResponse,
    responses={422: {"model": ErrorResponse}},
)
def embeddings_batch(request: EmbeddingBatchRequest) -> EmbeddingBatchResponse:
    vectors = []
    for index, text in enumerate(request.texts):
        vector = embed_text(text, request.dimension)
        vectors.append(
            EmbeddingVector(index=index, vector=vector, norm=round(vector_norm(vector), 8))
        )
    return EmbeddingBatchResponse(vectors=vectors, dimension=request.dimension, algorithm=ALGORITHM)


@router.post(
    "/matches/score",
    response_model=MatchScoreResponse,
    responses={422: {"model": ErrorResponse}},
)
def match_score(request: MatchScoreRequest) -> MatchScoreResponse:
    return score_match(request)


@router.post(
    "/suggestions/generate",
    response_model=SuggestionGenerateResponse,
    responses={422: {"model": ErrorResponse}},
)
async def suggestions_generate(request: SuggestionGenerateRequest) -> SuggestionGenerateResponse:
    from app.services.provider_suggestions import generate_with_provider
    return await generate_with_provider(request)


app.include_router(router)

@app.post("/internal/v1/resumes/render")
def resume_render(content: dict[str, object]) -> dict[str, object]:
    from app.services.pdf_renderer import render_pdf
    return render_pdf(content)
