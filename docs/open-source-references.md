# 开源参考与复用记录

当前未发现完整的历史源码复用记录，以下是设计参考，不能声称已经复制对应实现。
尚未直接引入源码，精确commit/tag暂未固定；实际引入时必须先固定版本并审阅该版本LICENSE。

| 来源 | 用途 | 当前复用状态 |
|---|---|---|
| https://github.com/AmruthPillai/Reactive-Resume | 动态章节、模板、文件产物 | 设计参考，未直接引入 |
| https://github.com/xitanggg/open-resume | 解析与字段回填 | 设计参考，未直接引入 |
| https://github.com/jsonresume/jsonresume.org/tree/master/packages/schema | 交换格式 | 计划通过转换器适配 |
| https://github.com/xllinbupt/MCP2skill | 猎聘工具工作流 | 待平台契约核对 |
| https://github.com/jiyangnan/AgentMesh-JobAgent | 预览、确认、平台分工 | 设计参考 |
| https://github.com/czc6666/czc-good-job | BOSS关键词轮换、详情读取、超时/空轮恢复、限额识别和简历发送交互 | 设计参考；复用设计思想，未复制源码 |
| https://github.com/JimmyMa22/boss-auto-apply | CDP浏览器连接、无人值守搜索和个性化招呼语 | 设计参考，未直接引入 |
| https://github.com/srbhr/Resume-Matcher | 无JD简历体检、JD定向优化、离线/在线Provider双模式 | 设计参考，未直接引入 |
| https://github.com/TechImmigrants/cv-builder | 多维度评分、优先级问题与修复建议 | 设计参考，未直接引入 |
| https://github.com/JingeW/Resume_agent | JD相关内容选择、规则后备、禁止编造 | 设计参考，未直接引入 |
| https://github.com/NI3singh/AI-Resume-Updater | 建议绑定字段、技能子集和数字事实校验 | 设计参考，未直接引入 |

依赖包以package-lock.json、pom.xml与Python依赖文件为准。新增源码复用另记文件范围、修改点、来源版本及许可证要求。
