from __future__ import annotations

import io
import zipfile

import pytest
from fastapi.testclient import TestClient

from app.main import app


@pytest.fixture()
def client() -> TestClient:
    with TestClient(app, raise_server_exceptions=False) as test_client:
        yield test_client


@pytest.fixture()
def simple_docx() -> bytes:
    xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
    <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
      <w:body>
        <w:p><w:r><w:t>专业技能</w:t></w:r></w:p>
        <w:p><w:r><w:t>熟悉 SpringBoot、Postgres 与 Docker</w:t></w:r></w:p>
        <w:p><w:r><w:t>项目经历</w:t></w:r></w:p>
        <w:p><w:r><w:t>负责校园预约平台后端开发</w:t></w:r></w:p>
      </w:body>
    </w:document>"""
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        archive.writestr("[Content_Types].xml", "<Types />")
        archive.writestr("word/document.xml", xml)
    return buffer.getvalue()
