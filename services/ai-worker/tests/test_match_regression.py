import json
from pathlib import Path

import pytest

from app.models import MatchScoreRequest, RequirementInput
from app.services.matcher import score_match

SAMPLES = json.loads((Path(__file__).parent / "fixtures/match-regression.json").read_text("utf-8"))["samples"]


@pytest.mark.parametrize("sample", SAMPLES, ids=lambda sample: sample["id"])
def test_matching_regression(sample):
    result = score_match(MatchScoreRequest(
        resume_text=sample["resume"],
        requirements=[RequirementInput(text=sample["requirement"])]))
    item = result.items[0]
    if sample["expected"] == "missing":
        assert item.score == 0
    else:
        assert item.score >= 50
        assert item.evidence is not None
