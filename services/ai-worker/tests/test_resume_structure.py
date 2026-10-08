import base64
import io
import zipfile

from app.models import ResumeParseRequest
from app.services.parsers import parse_resume

# All resume fixtures below use synthetic identities and contact details.
# Format-valid phone numbers are retained for phone and WeChat parsing regressions.
CHINESE = """个人简历
姓名：张三 | 求职意向：Java 后端开发工程师
电话：138 0000 0000 | 邮箱：candidate@example.test
现居地：示例市 | 个人主页：https://portfolio.example.test
一、教育经历
2021.09 - 2025.06 | 示例理工大学 | 计算机科学与技术 | 本科
2018.09 - 2021.06 | 示例职业学院 | 软件技术 | 专科
工作经历
2024.06 - 2024.09 | 示例科技有限公司 | 后端开发实习生
- 使用 Spring Boot 实现订单接口。
2023.06 - 2023.09 | 示例网络有限公司 | Java 开发实习生
- 负责自动化测试。
项目经历
示例预约平台 | 后端负责人 | 2024.03 - 2024.06
- 基于 MySQL 实现预约记录管理。
示例博客系统 | 独立开发 | 2023.01 - 2023.05
- 完成文章发布与权限控制。
专业技能
Java、Spring Boot、MySQL、Redis
自我评价
关注工程质量，能够清晰描述项目中的个人贡献。
"""


def structured(text: str):
    return parse_resume(ResumeParseRequest(raw_text=text)).structured_content


def test_chinese_fields_and_multiple_entries():
    result = structured(CHINESE)
    assert result["profile"]["name"] == "张三"
    assert result["profile"]["phone"] == "13800000000"
    assert result["profile"]["email"] == "candidate@example.test"
    assert result["profile"]["headline"] == "Java 后端开发工程师"
    assert result["profile"]["location"] == "示例市"
    assert result["profile"]["website"] == "https://portfolio.example.test"
    assert "关注工程质量" in result["profile"]["summary"]
    sections = {s["type"]: s for s in result["sections"]}
    education = sections["EDUCATION"]["items"]
    assert len(education) == 2
    assert education[0]["school"] == "示例理工大学"
    assert education[0]["major"] == "计算机科学与技术"
    assert education[0]["degree"] == "本科"
    assert education[0]["period"] == "2021.09 - 2025.06"
    experience = sections["EXPERIENCE"]["items"]
    assert len(experience) == 2
    assert experience[0]["company"] == "示例科技有限公司"
    assert experience[0]["role"] == "后端开发实习生"
    assert experience[0]["highlights"] == ["使用 Spring Boot 实现订单接口。"]
    projects = sections["PROJECT"]["items"]
    assert len(projects) == 2
    assert projects[0]["title"] == "示例预约平台"
    assert projects[0]["role"] == "后端负责人"
    assert sections["SKILLS"]["items"] == ["Java", "Spring Boot", "MySQL", "Redis"]
    assert result["importReviewPending"] is True


def test_english_fields_and_source_spans():
    parsed = parse_resume(
        ResumeParseRequest(
            raw_text="""Jane Doe
Software Engineer
Email: jane@example.test | Location: Example City
Education
2018 - 2022 | Example University | Computer Science | Bachelor
Work Experience
2022.07 - Present | Example Inc | Backend Developer
Built API endpoints.
Projects
Project Name: Inventory Tool | Role: Developer | Dates: 2023.01 - 2023.03
Implemented stock tracking.
"""
        )
    )
    result = parsed.structured_content
    assert result["profile"]["name"] == "Jane Doe"
    assert result["profile"]["headline"] == "Software Engineer"
    assert result["sections"][0]["items"][0]["school"] == "Example University"
    assert result["sections"][0]["items"][0]["major"] == "Computer Science"
    assert result["sections"][1]["items"][0]["company"] == "Example Inc"
    assert result["sections"][2]["items"][0]["title"] == "Inventory Tool"
    for field in result["importAnalysis"]["fields"]:
        assert parsed.raw_text[field["charStart"] : field["charEnd"]] == field["quote"]


def test_docx_table_label_value_cells():
    document = """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
    <w:body><w:tbl><w:tr>
    <w:tc><w:p><w:r><w:t>姓名</w:t></w:r></w:p></w:tc>
    <w:tc><w:p><w:r><w:t>李四</w:t></w:r></w:p></w:tc>
    <w:tc><w:p><w:r><w:t>电话</w:t></w:r></w:p></w:tc>
    <w:tc><w:p><w:r><w:t>13900000000</w:t></w:r></w:p></w:tc>
    </w:tr></w:tbl>
    <w:p><w:r><w:t>教育背景</w:t></w:r></w:p>
    <w:tbl><w:tr>
    <w:tc><w:p><w:r><w:t>2020.09 - 2024.06</w:t></w:r></w:p></w:tc>
    <w:tc><w:p><w:r><w:t>示例大学</w:t></w:r></w:p></w:tc>
    <w:tc><w:p><w:r><w:t>软件工程</w:t></w:r></w:p></w:tc>
    <w:tc><w:p><w:r><w:t>本科</w:t></w:r></w:p></w:tc>
    </w:tr></w:tbl></w:body></w:document>"""
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w") as archive:
        archive.writestr("word/document.xml", document)
    result = parse_resume(
        ResumeParseRequest(
            file_name="resume.docx", content_base64=base64.b64encode(stream.getvalue()).decode()
        )
    ).structured_content
    assert result["profile"]["name"] == "李四"
    assert result["profile"]["phone"] == "13900000000"
    assert result["sections"][0]["items"][0]["major"] == "软件工程"


def test_unknown_text_not_invented_as_school_or_employer():
    result = structured("姓名：王五\n项目经历\n项目名称：示例测试工具\n尚未补充项目说明")
    assert result["profile"]["name"] == "王五"
    assert "school" not in result["sections"][0]["items"][0]
    assert "尚未补充项目说明" in result["sections"][0]["items"][0]["description"]


def test_pdf_style_inline_heading_keeps_first_project():
    result = structured("""姓名：赵六
项目经历 SAMPLE-CHAT 示例即时通信系统
2024.01 - 2024.06 | 后端负责人
- 基于 Spring Boot 实现消息接口
个人技能 Java、Spring Boot、MySQL
""")
    sections = {section["type"]: section for section in result["sections"]}
    project = sections["PROJECT"]["items"][0]
    assert project["title"] == "SAMPLE-CHAT 示例即时通信系统"
    assert project["period"] == "2024.01 - 2024.06"
    assert project["role"] == "后端负责人"
    assert project["highlights"] == ["基于 Spring Boot 实现消息接口"]
    assert sections["SKILLS"]["items"] == ["Java", "Spring Boot", "MySQL"]


def test_multi_page_style_resume_imports_every_visible_section():
    result = structured("""示例甲
离校,正在找工作 · 24 岁 · 男 · 本科 · 学生
13800000000
sample@example.test
13800000000
优势亮点 - 熟练使用 Java，能够使用 Spring Boot + Maven
完成后端项目开发。
- 熟悉 RESTful API 设计，能够独立完成接口开发。
项目经历 SAMPLE·CHAT 示例即时通信
2026/07- 至今
独立开发
项目在线演示地址: https://demo.example.test:18083
技术栈: Spring Boot、WebSocket、MySQL、Redis
1) 基于 WebSocket 实现单聊和群聊。
示例 SamplePlayer 播放器
2026/04-2026/06
独立开发
1) 基于 Electron 和 React 开发客户端。
基于 Spring Boot 开发的示例图书管理系统
2026/01-2026/04
独立开发
1) 完成借阅、归还和馆藏模块。
教育经历 示例大学工程学院 2022/09-2026/06
软件工程
本科
在校经历
班级生活委员 (2022/09 - 2026/06) 负责班级日常事务。
示例工程学院 - 学生事务部成员 (2022/09 - 2024/06) 收集学生需求。
资格证书 大学英语四级
语言能力 普通话(工作应用)
""")
    sections = {section["type"]: section for section in result["sections"]}
    assert result["profile"]["summary"].startswith("离校,正在找工作")
    assert result["profile"]["wechat"] == "13800000000"
    assert "location" not in result["profile"]
    assert "website" not in result["profile"]
    assert len(sections["SKILLS"]["items"]) == 2
    assert [item["title"] for item in sections["PROJECT"]["items"]] == [
        "SAMPLE·CHAT 示例即时通信",
        "示例 SamplePlayer 播放器",
        "基于 Spring Boot 开发的示例图书管理系统",
    ]
    assert [len(item["highlights"]) for item in sections["PROJECT"]["items"]] == [1, 1, 1]
    assert sections["EDUCATION"]["items"][0]["major"] == "软件工程"
    assert len(sections["EXPERIENCE"]["items"]) == 2
    assert sections["AWARDS"]["items"][0]["title"] == "大学英语四级"
    assert sections["OTHER"]["items"][0]["title"] == "普通话(工作应用)"
