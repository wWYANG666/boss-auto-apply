from __future__ import annotations

import base64
import hashlib
import io
import os
from pathlib import Path
from typing import Any
from xml.sax.saxutils import escape

from pypdf import PdfReader
from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.pdfgen import canvas
from reportlab.platypus import Paragraph, SimpleDocTemplate, Spacer

TEMPLATE_VERSION = "clear-a4-v1"


def font_path() -> Path:
    candidates = [
        os.getenv("PDF_FONT_PATH", ""),
        "C:/Windows/Fonts/simhei.ttf",
        "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc",
    ]
    for name in candidates:
        if name and Path(name).is_file():
            return Path(name)
    raise ValueError("PDF_FONT_PATH must point to an embeddable Chinese TTF/TTC font")


def render_pdf(content: dict[str, Any]) -> dict[str, Any]:
    path = font_path()
    font_hash = hashlib.sha256(path.read_bytes()).hexdigest()
    font_name = "Resume-" + font_hash[:12]
    if font_name not in pdfmetrics.getRegisteredFontNames():
        pdfmetrics.registerFont(TTFont(font_name, str(path)))
    body = ParagraphStyle(
        "Body",
        fontName=font_name,
        fontSize=10,
        leading=15,
        spaceAfter=5,
        wordWrap="CJK",
        textColor=colors.HexColor("#22312e"),
    )
    title = ParagraphStyle("Name", parent=body, fontSize=21, leading=28, alignment=TA_CENTER, spaceAfter=8)
    contact = ParagraphStyle("Contact", parent=body, alignment=TA_CENTER, fontSize=9, leading=13)
    heading = ParagraphStyle(
        "Section",
        parent=body,
        fontSize=12,
        leading=18,
        textColor=colors.HexColor("#176b5b"),
        spaceBefore=12,
        keepWithNext=True,
    )
    story: list[Any] = []

    def paragraph(value: Any, style: ParagraphStyle = body) -> None:
        text = str(value or "").strip()
        if text:
            story.append(Paragraph(escape(text).replace("\n", "<br/>"), style))

    profile = content.get("profile") or {}
    paragraph(profile.get("name"), title)
    paragraph(profile.get("headline"), contact)
    paragraph(
        " | ".join(str(profile[k]) for k in ["phone", "email", "location", "website"] if profile.get(k)),
        contact,
    )
    if profile.get("summary"):
        paragraph("个人简介", heading)
        paragraph(profile["summary"])
    labels = {
        "PROJECT": "项目经历",
        "EDUCATION": "教育经历",
        "EXPERIENCE": "实习 / 工作经历",
        "SKILLS": "专业技能",
        "AWARDS": "奖项 / 证书",
        "OTHER": "其他",
    }
    for section in content.get("sections", []):
        if section.get("hidden") or section.get("visible") is False:
            continue
        kind = section.get("type", "OTHER")
        if kind == "SKILLS":
            items = [x for x in section.get("items", []) if isinstance(x, str) and x.strip()]
            if items:
                paragraph(section.get("heading") or labels[kind], heading)
                paragraph(" / ".join(items))
            continue
        entries = section.get("items", [section])
        entries = [
            x
            for x in entries
            if isinstance(x, dict) and not x.get("hidden") and x.get("visible") is not False
        ]
        entries = [
            x
            for x in entries
            if any(x.get(k) for k in ["title", "school", "company", "description", "text", "highlights"])
        ]
        if not entries:
            continue
        paragraph(section.get("heading") or labels.get(kind, kind), heading)
        for item in entries:
            paragraph(
                " | ".join(
                    str(item[k])
                    for k in ["title", "school", "company", "role", "major", "degree", "period"]
                    if item.get(k)
                )
            )
            paragraph(item.get("description") or item.get("text"))
            for line in item.get("highlights", []):
                if str(line).strip():
                    paragraph("- " + str(line))
            story.append(Spacer(1, 4))
    if not story:
        raise ValueError("Resume has no visible content to export")
    output = io.BytesIO()
    doc = SimpleDocTemplate(
        output,
        pagesize=A4,
        rightMargin=42,
        leftMargin=42,
        topMargin=38,
        bottomMargin=38,
        title="Resume",
        author="",
    )

    def invariant_canvas(*args: Any, **kwargs: Any) -> canvas.Canvas:
        kwargs["invariant"] = 1
        return canvas.Canvas(*args, **kwargs)

    doc.build(story, canvasmaker=invariant_canvas)
    data = output.getvalue()
    reader = PdfReader(io.BytesIO(data))
    return {
        "contentBase64": base64.b64encode(data).decode(),
        "sha256": hashlib.sha256(data).hexdigest(),
        "pageCount": len(reader.pages),
        "size": len(data),
        "fontSha256": font_hash,
        "templateVersion": TEMPLATE_VERSION,
    }
