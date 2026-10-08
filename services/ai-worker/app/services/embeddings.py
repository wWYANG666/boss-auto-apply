from __future__ import annotations

import hashlib
import math
import re
from collections import Counter

from app.services.skills import canonical_skill_ids
from app.services.text_utils import clean_text

ALGORITHM = "feature-hash-char-bigram-v1"
_LATIN_TOKEN_RE = re.compile(r"[a-z0-9+#.]{2,}")
_HAN_RE = re.compile(r"[\u3400-\u9fff]")


def tokenize(text: str) -> list[str]:
    normalized = clean_text(text).casefold()
    latin = _LATIN_TOKEN_RE.findall(normalized)
    han = _HAN_RE.findall(normalized)
    han_bigrams = ["".join(han[index : index + 2]) for index in range(max(0, len(han) - 1))]
    skills = [f"skill:{skill_id}" for skill_id in sorted(canonical_skill_ids(text))]
    # Canonical skill features receive extra weight without needing a model download.
    return latin + han + han_bigrams + skills + skills


def embed_text(text: str, dimension: int = 256) -> list[float]:
    counts = Counter(tokenize(text))
    vector = [0.0] * dimension
    for token, count in counts.items():
        digest = hashlib.sha256(token.encode("utf-8")).digest()
        index = int.from_bytes(digest[:4], "big") % dimension
        sign = -1.0 if digest[4] & 1 else 1.0
        vector[index] += sign * (1.0 + math.log(count))
    norm = math.sqrt(sum(value * value for value in vector))
    if norm:
        vector = [value / norm for value in vector]
    return vector


def vector_norm(vector: list[float]) -> float:
    return math.sqrt(sum(value * value for value in vector))


def cosine_similarity(left: list[float], right: list[float]) -> float:
    if len(left) != len(right):
        raise ValueError("vectors must have the same dimension")
    left_norm = vector_norm(left)
    right_norm = vector_norm(right)
    if not left_norm or not right_norm:
        return 0.0
    raw = sum(a * b for a, b in zip(left, right, strict=True)) / (left_norm * right_norm)
    # Signed hashing can yield small negative values; a matching score is kept in [0, 1].
    return max(0.0, min(1.0, raw))


def text_similarity(left: str, right: str, dimension: int = 256) -> float:
    return cosine_similarity(embed_text(left, dimension), embed_text(right, dimension))
