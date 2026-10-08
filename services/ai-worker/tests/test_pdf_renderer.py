import base64
import io
from pathlib import Path

from pypdf import PdfReader

from app.services.pdf_renderer import render_pdf

CONTENT = {
    "schemaVersion": "2.0",
    "profile": {
        "name": "导出验收",
        "headline": "Java 后端开发",
        "email": "qa@example.test",
        "summary": "专注真实项目证据与可靠的接口设计。",
    },
    "sections": [
        {
            "type": "PROJECT",
            "heading": "项目经历",
            "items": [
                {
                    "title": "预约服务",
                    "period": "2025.01 - 2025.06",
                    "highlights": [
                        "基于 Spring Boot 与 MySQL 实现预约接口。",
                        "使用自动化测试验证并发请求与重复提交。",
                    ],
                },
                {"title": "多语言文档工具", "description": "支持中文段落和 English text，导出后可提取文本。"},
            ],
        },
        {"type": "SKILLS", "items": ["Java", "Spring Boot", "MySQL"]},
        {"type": "OTHER", "hidden": True, "items": [{"description": "HIDDEN_CONTENT"}]},
    ],
}


def test_pdf_has_text_and_stable_bytes() -> None:
    first = render_pdf(CONTENT)
    second = render_pdf(CONTENT)
    assert first["sha256"] == second["sha256"]
    reader = PdfReader(io.BytesIO(base64.b64decode(first["contentBase64"])))
    text = "\n".join(page.extract_text() for page in reader.pages)
    assert "导出验收" in text and "多语言文档工具" in text
    assert "HIDDEN_CONTENT" not in text
    assert first["pageCount"] == 1


if __name__ == "__main__":
    out = Path("../../tmp/pdfs")
    out.mkdir(parents=True, exist_ok=True)
    result = render_pdf(CONTENT)
    (out / "resume-export-qa.pdf").write_bytes(base64.b64decode(result["contentBase64"]))
    print(result["sha256"], result["pageCount"])
