from __future__ import annotations

import hashlib
import re
import unicodedata

_SPACE_RE = re.compile(r"[\t\u00a0\u3000 ]+")
_MULTI_NEWLINE_RE = re.compile(r"\n{3,}")


def clean_text(text: str) -> str:
    text = unicodedata.normalize("NFKC", text).replace("\r\n", "\n").replace("\r", "\n")
    lines = [_SPACE_RE.sub(" ", line).strip() for line in text.split("\n")]
    return _MULTI_NEWLINE_RE.sub("\n\n", "\n".join(lines)).strip()


def stable_id(prefix: str, *parts: str, length: int = 16) -> str:
    payload = "\x1f".join(parts).encode("utf-8", errors="replace")
    return f"{prefix}_{hashlib.sha256(payload).hexdigest()[:length]}"


def excerpt(text: str, limit: int = 360) -> str:
    value = _SPACE_RE.sub(" ", text.replace("\n", " ")).strip()
    if len(value) <= limit:
        return value
    return value[: max(1, limit - 1)].rstrip() + "…"


def detect_language(text: str) -> str:
    chinese = len(re.findall(r"[\u3400-\u9fff]", text))
    latin = len(re.findall(r"[A-Za-z]", text))
    if chinese and latin and min(chinese, latin) / max(chinese, latin) >= 0.08:
        return "mixed"
    return "zh" if chinese >= latin * 0.25 else "en"
