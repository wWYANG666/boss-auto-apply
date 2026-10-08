from __future__ import annotations

import base64
import binascii
import hashlib
import io
import re
import zipfile
from dataclasses import dataclass
from pathlib import PurePath
from xml.etree import ElementTree

from app.models import (
    DocumentBlock,
    FileType,
    ParseMetadata,
    ResumeParseRequest,
    ResumeParseResponse,
    ResumeSection,
    SectionType,
)
from app.services.resume_structure import (
    ACTION,
    COMPANY,
    EN_SCHOOL,
    PERIOD,
    SCHOOL,
    structure_resume,
    table_row,
)
from app.services.skills import extract_skills
from app.services.text_utils import clean_text, stable_id

MAX_FILE_BYTES = 15 * 1024 * 1024


class DocumentParseError(ValueError):
    code = "DOCUMENT_PARSE_FAILED"


class UnsupportedDocumentType(DocumentParseError):
    code = "UNSUPPORTED_DOCUMENT_TYPE"


class ParserDependencyMissing(DocumentParseError):
    code = "PARSER_DEPENDENCY_MISSING"


@dataclass(slots=True)
class ExtractedDocument:
    pages: list[str]
    parser: str
    warnings: list[str]
    profile_photo: str | None = None


_MIME_TYPES = {
    "text/plain": FileType.TXT,
    "application/pdf": FileType.PDF,
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document": FileType.DOCX,
}
_EXTENSIONS = {".txt": FileType.TXT, ".md": FileType.TXT, ".pdf": FileType.PDF, ".docx": FileType.DOCX}


def _file_type(file_name: str, mime_type: str | None) -> FileType:
    extension = PurePath(file_name).suffix.casefold()
    if extension in _EXTENSIONS:
        return _EXTENSIONS[extension]
    if mime_type:
        normalized_mime = mime_type.split(";", 1)[0].strip().casefold()
        if normalized_mime in _MIME_TYPES:
            return _MIME_TYPES[normalized_mime]
    raise UnsupportedDocumentType("only TXT, PDF and DOCX documents are supported")


def _decode_base64(value: str) -> bytes:
    try:
        data = base64.b64decode(value, validate=True)
    except (binascii.Error, ValueError) as exc:
        raise DocumentParseError("contentBase64 is not valid base64") from exc
    if not data:
        raise DocumentParseError("uploaded document is empty")
    if len(data) > MAX_FILE_BYTES:
        raise DocumentParseError(f"uploaded document exceeds {MAX_FILE_BYTES} bytes")
    return data


def _extract_txt(data: bytes) -> ExtractedDocument:
    for encoding in ("utf-8-sig", "gb18030", "utf-16"):
        try:
            return ExtractedDocument([data.decode(encoding)], f"text:{encoding}", [])
        except UnicodeDecodeError:
            continue
    return ExtractedDocument(
        [data.decode("utf-8", errors="replace")],
        "text:utf-8-replacement",
        ["TEXT_ENCODING_FALLBACK"],
    )


def _extract_pdf(data: bytes) -> ExtractedDocument:
    try:
        from pypdf import PdfReader
    except ImportError as exc:  # pragma: no cover - exercised by minimal installations
        raise ParserDependencyMissing("PDF parsing requires the optional pypdf package") from exc

    try:
        reader = PdfReader(io.BytesIO(data))
        pages = [(page.extract_text() or "") for page in reader.pages]
        image_candidates = [image for page in reader.pages for image in page.images]
        photo = max(image_candidates, key=lambda image: len(image.data), default=None)
        profile_photo = None
        if photo is not None and len(photo.data) >= 5_000:
            extension = PurePath(photo.name).suffix.casefold()
            mime = "image/png" if extension == ".png" else "image/jpeg"
            profile_photo = f"data:{mime};base64,{base64.b64encode(photo.data).decode()}"
    except Exception as exc:  # pypdf exposes several version-specific exception classes
        raise DocumentParseError("the PDF could not be read") from exc
    if not pages:
        pages = [""]
    warnings = []
    if not any(page.strip() for page in pages):
        warnings.append("PDF_HAS_NO_TEXT_LAYER")
    return ExtractedDocument(pages, "pypdf", warnings, profile_photo)


def _extract_docx(data: bytes) -> ExtractedDocument:
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            if archive.getinfo("word/document.xml").file_size > MAX_FILE_BYTES * 4:
                raise DocumentParseError("DOCX document XML exceeds the expanded size limit")
            xml = archive.read("word/document.xml")
    except (zipfile.BadZipFile, KeyError) as exc:
        raise DocumentParseError("the DOCX package is invalid") from exc

    try:
        root = ElementTree.fromstring(xml)
    except ElementTree.ParseError as exc:
        raise DocumentParseError("the DOCX document XML is invalid") from exc

    namespace = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"

    def paragraph_text(paragraph: ElementTree.Element) -> str:
        fragments: list[str] = []
        for node in paragraph.iter():
            if node.tag == f"{namespace}t" and node.text:
                fragments.append(node.text)
            elif node.tag == f"{namespace}tab":
                fragments.append(" | ")
            elif node.tag in {f"{namespace}br", f"{namespace}cr"}:
                fragments.append("\n")
        return "".join(fragments).strip()

    paragraphs: list[str] = []
    body = root.find(f"{namespace}body")
    for child in list(body) if body is not None else []:
        if child.tag == f"{namespace}tbl":
            for row in child.findall(f"{namespace}tr"):
                cells = [
                    "\n".join(filter(None, (paragraph_text(p) for p in cell.iter(f"{namespace}p"))))
                    for cell in row.findall(f"{namespace}tc")
                ]
                paragraphs.append(table_row(cells))
        else:
            text = paragraph_text(child)
            if text:
                paragraphs.append(text)
    warnings = [] if paragraphs else ["DOCX_HAS_NO_TEXT"]
    return ExtractedDocument(["\n".join(paragraphs)], "docx-xml", warnings)


_SECTION_RULES: tuple[tuple[SectionType, re.Pattern[str]], ...] = (
    (
        SectionType.EDUCATION,
        re.compile(r"^(教育(?:背景|经历|情况)?|学历(?:信息)?|education|academic background)$", re.I),
    ),
    (
        SectionType.EXPERIENCE,
        re.compile(
            r"^(工作(?:经历|经验)?|实习(?:经历|经验)?|在校经历|校园经历|工作/实习经历|实习与工作经历|"
            r"professional experience|internships?|experience|work experience|employment(?: history)?)$",
            re.I,
        ),
    ),
    (SectionType.PROJECT, re.compile(r"^(项目(?:经历|经验)?|projects?|project experience)$", re.I)),
    (
        SectionType.SKILLS,
        re.compile(
            r"^(专业技能|个人技能|技能专长|技能(?:清单)?|技术栈|优势亮点|个人优势|skills?|technical skills)$",
            re.I,
        ),
    ),
    (
        SectionType.AWARDS,
        re.compile(r"^(奖项|荣誉(?:奖项)?|证书|资格证书|awards?|honors?|certifications?)$", re.I),
    ),
    (SectionType.OTHER, re.compile(r"^(语言能力|语言水平|languages?)$", re.I)),
    (
        SectionType.PROFILE,
        re.compile(
            r"^(基本信息|个人信息|联系方式|自我评价|个人简介|个人总结|求职意向|profile|summary|objective)$",
            re.I,
        ),
    ),
)


def _heading_type(text: str) -> SectionType | None:
    candidate = re.sub(r"^\s*(?:[一二三四五六七八九十]+[、.)]|[0-9]+[、.)]?|[•●▪#]+)\s*", "", text)
    candidate = re.sub(r"[：:\-—|]", "", candidate).strip()
    if re.fullmatch(r"[\u4e00-\u9fff\s]+", candidate):
        candidate = re.sub(r"\s+", "", candidate)
    if len(candidate) > 32:
        return None
    for section_type, pattern in _SECTION_RULES:
        if pattern.fullmatch(candidate):
            return section_type
    return None


_INLINE_HEADINGS: tuple[tuple[SectionType, re.Pattern[str]], ...] = (
    (
        SectionType.EDUCATION,
        re.compile(r"^\s*(?:教育背景|教育经历|教育情况|学历信息)\s*[:：|｜—-]?\s*(.*)$", re.I),
    ),
    (
        SectionType.EXPERIENCE,
        re.compile(
            r"^\s*(?:工作经历|工作经验|实习经历|实习经验|在校经历|校园经历|工作/实习经历|实习与工作经历)"
            r"\s*[:：|｜—-]?\s*(.*)$",
            re.I,
        ),
    ),
    (
        SectionType.PROJECT,
        re.compile(
            r"^\s*(?:项目经历|项目经验|项目实践|个人项目|主要项目|项目作品|"
            r"Projects?(?!\s+Name\s*:))\s*[:：|｜—-]?\s*(.*)$",
            re.I,
        ),
    ),
    (
        SectionType.SKILLS,
        re.compile(
            r"^\s*(?:专业技能|个人技能|技能专长|技能清单|技术栈|优势亮点|个人优势|Skills?)"
            r"\s*[:：|｜—-]?\s*(.*)$",
            re.I,
        ),
    ),
    (
        SectionType.AWARDS,
        re.compile(
            r"^\s*(?:奖项|荣誉奖项|证书|资格证书|Awards?|Honors?)\s*[:：|｜—-]?\s*(.*)$",
            re.I,
        ),
    ),
    (
        SectionType.OTHER,
        re.compile(r"^\s*(?:语言能力|语言水平|Languages?)\s*[:：|｜—-]?\s*(.*)$", re.I),
    ),
    (
        SectionType.PROFILE,
        re.compile(
            r"^\s*(?:自我评价|个人简介|个人总结|Summary|Profile)\s*[:：|｜—-]?\s*(.*)$",
            re.I,
        ),
    ),
)


def _inline_heading(text: str) -> tuple[SectionType | None, str | None, str]:
    candidate = re.sub(r"^\s*(?:[一二三四五六七八九十]+[、.)]|[0-9]+[、.)]?|[•●▪#]+)\s*", "", text)
    for section_type, pattern in _INLINE_HEADINGS:
        match = pattern.match(candidate)
        if match:
            heading = candidate[:match.start(1)].strip(" :：|｜-—")
            return section_type, heading, match.group(1).strip()
    return None, None, text


def _build_blocks(pages: list[str], document_hash: str) -> tuple[str, list[DocumentBlock]]:
    blocks: list[DocumentBlock] = []
    output_parts: list[str] = []
    cursor = 0
    for page_number, page in enumerate(pages, start=1):
        page_text = clean_text(page)
        for line in (value.strip() for value in page_text.splitlines()):
            if not line:
                continue
            if output_parts:
                cursor += 1
            start = cursor
            output_parts.append(line)
            cursor += len(line)
            blocks.append(
                DocumentBlock(
                    evidence_id=stable_id("ev", document_hash, str(page_number), str(start), line),
                    page=page_number,
                    text=line,
                    char_start=start,
                    char_end=cursor,
                    bbox=None,
                )
            )
    return "\n".join(output_parts), blocks


def _build_sections(blocks: list[DocumentBlock], document_hash: str) -> list[ResumeSection]:
    if not blocks:
        return []
    groups: list[tuple[SectionType, str | None, list[DocumentBlock]]] = []
    current_type = SectionType.PROFILE
    current_heading: str | None = None
    current_blocks: list[DocumentBlock] = []
    inferred_context = False
    for block in blocks:
        heading_type = _heading_type(block.text)
        heading_label: str | None = block.text if heading_type is not None else None
        remainder = ""
        if heading_type is None:
            heading_type, heading_label, remainder = _inline_heading(block.text)
        if (
            current_type == SectionType.PROJECT
            and heading_type == SectionType.SKILLS
            and heading_label
            and re.fullmatch(r"技术栈", heading_label, re.I)
        ):
            heading_type, heading_label, remainder = None, None, block.text
        if heading_type is not None:
            inferred_context = False
            if current_blocks:
                groups.append((current_type, current_heading, current_blocks))
            current_type = heading_type
            current_heading = heading_label
            current_blocks = []
            if remainder:
                offset = block.text.find(remainder)
                current_blocks.append(block.model_copy(update={
                    "text": remainder,
                    "char_start": block.char_start + max(0, offset),
                    "char_end": block.char_start + max(0, offset) + len(remainder),
                    "evidence_id": stable_id(
                        "ev", document_hash, str(block.page), str(block.char_start), remainder
                    ),
                }))
        else:
            inferred = None
            if (
                (current_type == SectionType.PROFILE or inferred_context)
                and not ACTION.search(block.text)
                and PERIOD.search(block.text)
            ):
                if SCHOOL.search(block.text) or EN_SCHOOL.search(block.text):
                    inferred = SectionType.EDUCATION
                elif COMPANY.search(block.text):
                    inferred = SectionType.EXPERIENCE
            if inferred is not None and inferred != current_type:
                if current_blocks:
                    groups.append((current_type, current_heading, current_blocks))
                current_type, current_heading, current_blocks = inferred, None, []
                inferred_context = True
            current_blocks.append(block)
    if current_blocks:
        groups.append((current_type, current_heading, current_blocks))
    if not groups:
        groups.append((SectionType.OTHER, None, blocks))

    sections: list[ResumeSection] = []
    for index, (section_type, heading, section_blocks) in enumerate(groups):
        content = "\n".join(block.text for block in section_blocks).strip()
        if not content and heading:
            content = heading
        sections.append(
            ResumeSection(
                element_id=stable_id("section", document_hash, str(index), section_type.value, heading or ""),
                type=section_type,
                heading=heading,
                text=content,
                evidence_ids=[block.evidence_id for block in section_blocks],
                confidence=0.98 if heading else 0.72,
            )
        )
    return sections


def parse_resume(request: ResumeParseRequest) -> ResumeParseResponse:
    file_type = _file_type(request.file_name, request.mime_type)
    warnings: list[str] = []
    if request.raw_text is not None:
        extracted = ExtractedDocument([request.raw_text], "provided-text", [])
        digest_source = request.raw_text.encode("utf-8")
    else:
        assert request.content_base64 is not None
        data = _decode_base64(request.content_base64)
        digest_source = data
        if file_type == FileType.TXT:
            extracted = _extract_txt(data)
        elif file_type == FileType.PDF:
            extracted = _extract_pdf(data)
        else:
            extracted = _extract_docx(data)

    warnings.extend(extracted.warnings)
    sha256 = hashlib.sha256(digest_source).hexdigest()
    document_id = stable_id("doc", sha256)
    raw_text, blocks = _build_blocks(extracted.pages, sha256)
    if not raw_text:
        warnings.append("NO_TEXT_EXTRACTED")
    sections = _build_sections(blocks, sha256)
    structured_content = structure_resume(raw_text, sections, blocks)
    if extracted.profile_photo:
        structured_content["profile"]["photoDataUrl"] = extracted.profile_photo
    return ResumeParseResponse(
        structured_content=structured_content,
        document_id=document_id,
        file_name=request.file_name,
        file_type=file_type,
        raw_text=raw_text,
        blocks=blocks,
        sections=sections,
        skills=extract_skills(raw_text),
        warnings=list(dict.fromkeys(warnings)),
        metadata=ParseMetadata(
            parser=extracted.parser,
            page_count=max(1, len(extracted.pages)),
            char_count=len(raw_text),
            sha256=sha256,
        ),
    )
