"""Conservative field extraction over normalized document text.

Only observed text is copied into fields. Uncertain lines remain editable, and every
recognized field carries a source span for the import-review UI.
"""

from __future__ import annotations

import re
from typing import Any

from app.models import DocumentBlock, ResumeSection, SectionType
from app.services.text_utils import stable_id

YEAR = r"(?:19|20)\d{2}(?:\s*[./年-]\s*(?:0?[1-9]|1[0-2])(?:月)?)?"
PERIOD = re.compile(rf"{YEAR}\s*(?:[-–—~～]{{1,2}}|至|到|to)\s*(?:{YEAR}|至今|现在|present|current)", re.I)
EMAIL = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")
PHONE = re.compile(r"(?<!\d)(?:\+?86[- ]?)?1[3-9]\d(?:[- ]?\d){8}(?!\d)")
URL = re.compile(r"(?:https?://|www\.|github\.com/|gitee\.com/)[^\s|｜;；，,]+", re.I)
DEGREE = re.compile(
    r"博士研究生|硕士研究生|大学本科|本科|硕士|博士|学士|大专|专科|Bachelor(?:'s)?|Master(?:'s)?|Ph\.?D\.?|B\.?Sc\.?|M\.?Sc\.?",
    re.I,
)
SCHOOL = re.compile(r"[\u4e00-\u9fffA-Za-z·]{2,}(?:大学|学院|学校)")
EN_SCHOOL = re.compile(
    r"(?:[A-Z][\w'&.-]*\s+){1,5}(?:University|College|Institute)|University\s+of\s+(?:[A-Z][\w.-]*\s*){1,4}"
)
COMPANY = re.compile(
    r"[\u4e00-\u9fffA-Za-z0-9·()（）]{2,}(?:有限责任公司|股份有限公司|有限公司|集团|事务所|研究院|公司)"
)
ROLE = re.compile(
    r"[\u4e00-\u9fffA-Za-z+# /-]{0,28}(?:工程师|开发实习生|实习生|开发人员|负责人|独立开发|委员|成员|经理|"
    r"Engineer|Developer|Intern|Manager|Designer)",
    re.I,
)
BULLET = re.compile(r"^\s*(?:[-*•●▪·]|\d{1,2}[.)、](?!\d)|[一二三四五六七八九十]+[、.)])\s*")
ACTION = re.compile(
    r"^(?:负责|参与|使用|基于|实现|完成|设计|开发了|优化|主导|协助|搭建|负责了|Developed|Built|Implemented|Designed|Led|Worked|Used)",
    re.I,
)
LABELS = {
    "name": ["姓名", "Name", "Full Name"],
    "headline": [
        "求职意向",
        "求职方向",
        "求职岗位",
        "目标岗位",
        "应聘职位",
        "意向岗位",
        "Desired Position",
        "Objective",
    ],
    "email": ["邮箱", "电子邮箱", "电子邮件", "Email", "E-mail"],
    "phone": ["电话", "手机", "手机号", "联系电话", "Phone", "Mobile"],
    "wechat": ["微信", "微信号", "WeChat"],
    "location": ["所在城市", "现居地", "现居", "居住地", "所在地", "地址", "城市", "Location", "Address"],
    "website": ["个人主页", "个人网站", "GitHub", "Website", "Portfolio"],
    "school": ["毕业院校", "毕业学校", "学校", "院校", "School", "University"],
    "major": ["所学专业", "专业", "Major"],
    "degree": ["学历", "学位", "Degree"],
    "title": ["项目名称", "项目名", "Project Name"],
    "company": ["公司名称", "实习单位", "工作单位", "公司", "Company", "Employer"],
    "role": ["担任职务", "担任角色", "项目角色", "职位", "职务", "角色", "岗位", "Position", "Role"],
    "period": ["项目时间", "在校时间", "工作时间", "起止时间", "实习时间", "时间", "Dates"],
}
ALIASES = {alias.casefold(): key for key, aliases in LABELS.items() for alias in aliases}
LABEL = re.compile(
    r"(?<![\u4e00-\u9fffA-Za-z])(" 
    + "|".join(re.escape(x) for x in sorted(ALIASES, key=len, reverse=True))
    + r")\s*[:：]\s*",
    re.I,
)
HEADERS = {
    "个人简历",
    "简历",
    "基本信息",
    "个人信息",
    "联系方式",
    "教育背景",
    "教育经历",
    "专业技能",
    "自我评价",
    "本科",
    "硕士",
    "博士",
}


def _labels(line: str) -> dict[str, str]:
    matches = list(LABEL.finditer(line))
    return {
        ALIASES[match.group(1).casefold()]: line[
            match.end() : matches[i + 1].start() if i + 1 < len(matches) else len(line)
        ].strip(" |｜;；,，")
        for i, match in enumerate(matches)
    }


def _parts(line: str) -> list[str]:
    return [p.strip(" -–—:：") for p in re.split(r"[|｜;；\t]", line) if p.strip(" -–—:：")]


def table_row(cells: list[str]) -> str:
    result = []
    index = 0
    while index < len(cells):
        label = cells[index].strip(" :：").casefold()
        if label in ALIASES and index + 1 < len(cells):
            result.append(cells[index].strip(" :：") + ": " + cells[index + 1])
            index += 2
        else:
            result.append(cells[index])
            index += 1
    return " | ".join(result)


def structure_resume(raw: str, sections: list[ResumeSection], blocks: list[DocumentBlock]) -> dict[str, Any]:
    profile: dict[str, Any] = {}
    output: list[dict[str, Any]] = []
    evidence: list[dict[str, Any]] = []
    seen_spans: set[tuple[str, int]] = set()

    def put(
        target: dict[str, Any],
        field: str,
        value: str,
        path: str,
        quote: str,
        confidence: float = 0.85,
        overwrite: bool = False,
    ) -> None:
        value = value.strip(" |｜;；")
        if not value or target.get(field) == value or (target.get(field) and not overwrite):
            return
        target[field] = value
        start = raw.find(quote)
        span = (path + "/" + field, start)
        if span in seen_spans:
            return
        seen_spans.add(span)
        block = next((b for b in blocks if b.char_start <= start < b.char_end), None)
        evidence.append(
            {
                "path": path + "/" + field,
                "value": value,
                "quote": quote,
                "charStart": start,
                "charEnd": start + len(quote) if start >= 0 else -1,
                "page": block.page if block else None,
                "confidence": confidence,
            }
        )

    header_sections = [s for s in sections if s.type == SectionType.PROFILE]
    header = (
        "\n".join(s.text for s in header_sections) if header_sections else next(iter(raw.splitlines()), "")
    )
    for line in header.splitlines():
        labels = _labels(line)
        for field in ("name", "headline", "email", "phone", "wechat", "location", "website"):
            if field in labels:
                value = labels[field]
                if field == "phone":
                    value = re.sub(r"[() -]", "", value)
                put(profile, field, value, "/profile", line, 0.95)
    for field, pattern in (("email", EMAIL), ("phone", PHONE), ("website", URL)):
        match = pattern.search(header)
        if not match and field != "website":
            match = pattern.search(raw)
        if match:
            value = match.group()
            if field == "phone":
                value = re.sub(r"[- ]", "", value)
            put(profile, field, value, "/profile", match.group(), 0.95, overwrite=field == "email")
    phone_hits = list(PHONE.finditer(header))
    normalized_phones = [re.sub(r"[- ]", "", match.group()) for match in phone_hits]
    if (
        not profile.get("wechat")
        and len(normalized_phones) >= 2
        and normalized_phones[0] == normalized_phones[1]
    ):
        put(profile, "wechat", normalized_phones[1], "/profile", phone_hits[1].group(), 0.65)
    if not profile.get("name"):
        for line in header.splitlines()[:3]:
            candidate = line.strip()
            compact = re.sub(r"\s+", "", candidate)
            if (
                compact not in HEADERS
                and compact.casefold() not in ALIASES
                and re.fullmatch(r"[\u4e00-\u9fff]{2,4}", compact)
            ):
                put(profile, "name", compact, "/profile", line, 0.7)
                break
            if re.fullmatch(r"[A-Z][a-z'-]+(?:\s+[A-Z][a-z'-]+){1,3}", candidate) and not re.search(
                r"Engineer|Developer|Resume|Curriculum|Vitae|Science|Summary|Education|Skills", candidate
            ):
                put(profile, "name", candidate, "/profile", line, 0.7)
                break
    if not profile.get("headline"):
        for line in header.splitlines()[:10]:
            if len(line) <= 45 and not _labels(line) and ROLE.search(line):
                put(profile, "headline", line, "/profile", line, 0.7)
                break
    if not profile.get("summary"):
        for line in header.splitlines()[:8]:
            if (
                re.search(r"(?:\d{2}\s*岁|在职|离校|应届|求职|找工作|学生|男|女)", line)
                and not EMAIL.search(line)
                and not PHONE.search(line)
                and not _labels(line)
            ):
                put(profile, "summary", line, "/profile", line, 0.8)
                break

    for section in sections:
        kind = section.type.value
        if kind == "PROFILE":
            if section.heading and re.search(r"简介|总结|评价|summary|profile", section.heading, re.I):
                summary = "\n".join(
                    line
                    for line in section.text.splitlines()
                    if not _labels(line) and not EMAIL.search(line) and not PHONE.search(line)
                )
                put(profile, "summary", summary, "/profile", section.text, 0.8)
            continue
        index = len(output)
        items: list[Any] = []
        result = {
            "elementId": section.element_id,
            "type": kind,
            "heading": section.heading
            or {
                "EDUCATION": "教育经历",
                "EXPERIENCE": "工作 / 实习经历",
                "PROJECT": "项目经历",
                "SKILLS": "专业技能",
            }.get(kind, "其他信息"),
            "hidden": False,
            "items": items,
        }
        if kind == "SKILLS":
            if re.search(r"优势|亮点", str(result["heading"])):
                for raw_line in section.text.splitlines():
                    line = raw_line.strip()
                    if not line:
                        continue
                    if BULLET.match(line) or not items:
                        items.append(BULLET.sub("", line))
                    else:
                        items[-1] += line
                if items:
                    output.append(result)
                continue
            for line in section.text.splitlines():
                line = BULLET.sub("", line)
                for part in re.split(r"[,，、;；|｜]", line):
                    value = part.strip()
                    if value and value not in items:
                        items.append(value)
            if items:
                output.append(result)
            continue
        current: dict[str, Any] | None = None
        section_lines = [value for value in section.text.splitlines() if value.strip()]
        last_was_highlight = False
        for line_index, raw_line in enumerate(section_lines):
            line = raw_line.strip()
            if not line:
                continue
            labelled = _labels(line)
            period = PERIOD.search(line)
            prose = bool(BULLET.match(line) or ACTION.search(line))
            if any(key in labelled for key in ("title", "company", "school")):
                prose = False
            school = SCHOOL.search(line) or EN_SCHOOL.search(line)
            company = COMPANY.search(line)
            organization = company or (school if kind == "EXPERIENCE" else None)
            project_parts = _parts(PERIOD.sub("", line)) if kind == "PROJECT" else []
            next_has_period = line_index + 1 < len(section_lines) and bool(
                PERIOD.search(section_lines[line_index + 1])
            )
            if kind == "PROJECT" and next_has_period and len(line) < 90 and not BULLET.match(line):
                prose = False
            is_identity = (
                (kind == "EDUCATION" and (school or "school" in labelled))
                or (kind == "EXPERIENCE" and (organization or "company" in labelled))
                or (
                    kind == "PROJECT"
                    and (
                        "title" in labelled
                        or (
                            not prose
                            and len(line) < 90
                            and project_parts
                            and (
                                re.search(
                                    r"(?:系统|平台|工具|网站|小程序|项目|播放器|客户端)$",
                                    project_parts[0],
                                )
                                or next_has_period
                            )
                        )
                    )
                )
                or (
                    kind == "EXPERIENCE"
                    and period
                    and not prose
                    and (
                        ROLE.search(line)
                        or re.search(r"\b(?:Ltd|Inc|LLC|Corp|Engineer|Developer|Intern)\b", line)
                    )
                )
            )
            primary = "school" if kind == "EDUCATION" else "company" if kind == "EXPERIENCE" else "title"
            has_identity = bool(
                current
                and (current.get(primary) or (kind == "EXPERIENCE" and current.get("role")))
            )
            date_only = bool(period and not PERIOD.sub("", line).strip(" -–—|｜"))
            start_new = current is None or (
                has_identity
                and not prose
                and (is_identity or (date_only and current and current.get("period")))
            )
            if start_new:
                current = {
                    "elementId": stable_id("entry", section.element_id, str(len(items)), line),
                    "hidden": False,
                    "description": "",
                    "highlights": [],
                }
                items.append(current)
                last_was_highlight = False
            assert current is not None
            path = f"/sections/{index}/items/{len(items) - 1}"
            handled = False
            for field in ("school", "major", "degree", "title", "company", "role", "period"):
                if field in labelled:
                    put(current, field, labelled[field], path, line, 0.95)
                    handled = True
            if period and not prose:
                put(current, "period", period.group(), path, line, 0.9)
                handled = True
            remaining = PERIOD.sub("", line).strip(" |｜-–—")
            if not prose and kind == "EDUCATION":
                if school:
                    put(current, "school", school.group().strip(), path, line)
                    remaining = remaining.replace(school.group(), "")
                    handled = True
                degree = DEGREE.search(remaining)
                if degree:
                    put(current, "degree", degree.group(), path, line)
                    remaining = remaining.replace(degree.group(), "")
                    handled = True
                candidates = _parts(remaining)
                if current.get("school") and not labelled and candidates:
                    major = candidates[0].strip()
                    if len(major) < 65 and not re.search(
                        r"GPA|排名|绩点|奖学金|成绩|课程|[0-9]", major, re.I
                    ):
                        put(current, "major", major.removesuffix("专业"), path, line, 0.65)
                        handled = True
            elif not prose and kind in {"PROJECT", "EXPERIENCE", "AWARDS", "OTHER"}:
                parts = _parts(remaining)
                if organization and kind == "EXPERIENCE":
                    put(current, "company", organization.group(), path, line)
                    remaining = remaining.replace(organization.group(), "").strip(" |｜-–—")
                    parts = _parts(remaining)
                    handled = True
                elif kind == "EXPERIENCE" and re.search(r"\b(?:Ltd|Inc|LLC|Corp)\b", remaining):
                    if parts:
                        put(current, "company", parts.pop(0), path, line, 0.8)
                        remaining = " | ".join(parts)
                        handled = True
                if kind == "EXPERIENCE" and not labelled:
                    role_match = ROLE.search(remaining)
                    if role_match:
                        role = role_match.group().strip(" |｜-–—")
                        put(current, "role", role, path, line, 0.75)
                        tail = remaining[role_match.end():].strip(" ()（）|｜-–—")
                        if tail:
                            current["description"] = "\n".join(
                                filter(None, [current["description"], tail])
                            )
                        parts = []
                        handled = True
                if (
                    not current.get(primary)
                    and parts
                    and not labelled
                    and kind != "EXPERIENCE"
                ):
                    put(current, primary, parts.pop(0), path, line, 0.7)
                    handled = True
                if kind in {"EXPERIENCE", "PROJECT"} and parts and not labelled:
                    role = next((p for p in parts if ROLE.search(p)), "")
                    if role:
                        put(current, "role", role, path, line, 0.75)
                        handled = True
            if prose:
                current["highlights"].append(BULLET.sub("", line))
                last_was_highlight = True
            elif not handled:
                if last_was_highlight and current["highlights"]:
                    current["highlights"][-1] += line
                else:
                    current["description"] = "\n".join(filter(None, [current["description"], line]))
            else:
                last_was_highlight = False
            # Preserve full entry source, including details whose header mapping is ambiguous.
            current["sourceText"] = "\n".join(filter(None, [current.get("sourceText", ""), raw_line]))
        if items:
            output.append(result)
    return {
        "schemaVersion": "2.0",
        "profile": profile,
        "sections": output,
        "importReviewPending": True,
        "importAnalysis": {
            "fields": evidence,
            "recognizedFieldCount": len(evidence),
            "extractorVersion": "resume-fields-v1",
            "warnings": ["请核对自动识别的字段，尤其是未标注字段名的内容。"],
        },
    }
