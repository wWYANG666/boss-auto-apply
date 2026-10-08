# GitHub 上传说明

本目录为独立的源码副本，不包含原项目的 Git 历史和本地业务数据。

## 包含内容

- 前端、Core API、AI Worker、Runner 和浏览器扩展源码。
- 数据库表结构迁移、解析规则词典、配置模板及合成测试夹具。
- 依赖清单与 lock 文件、构建脚本、CI 和使用文档。

## 已移除内容

- 原项目的 `.git`、旧提交历史和 stash。
- 数据库、数据库备份、简历 PDF、上传文件和业务导出文件。
- 浏览器 profiles、Cookie、平台 Token、设备绑定和实际密钥。
- 截图、日志、调试缓存、依赖目录和编译产物。
- 测试夹具中的个人信息、个人项目名称及公网演示地址。
- 生产招呼语代码中对个人项目的硬编码后备内容。

首次启动会创建新的本地数据目录，需要注册新账号；默认不初始化演示数据。
配置模板里的开发密码和测试中的合成联系方式不是可用的个人凭据。

## 验证结果

- 2026-10-08：简历解析回归测试 6 项通过，Core API 集成测试 23 项通过。
- 已扫描旧个人信息和常见凭据格式，并与本机保存的 4 个实际密钥逐项比对，无匹配。
- 交付目录已排除临时测试编译产物，文件清单中不包含运行数据或原 Git 历史。

上述验证覆盖本次脱敏相关改动；未重新执行真实平台操作或全项目端到端验收。

## 上传

目标仓库为 [wWYANG666/BOSS](https://github.com/wWYANG666/BOSS)。以下是从全新副本首次上传的步骤，每条 Git 命令都明确使用 `D:\Projects\CareerLens-public`，可以从任意终端目录执行。

只复制代码块内部的命令，不要把首尾的 Markdown 标记（` ```powershell ` 和 ` ``` `）粘贴进终端。

先初始化并检查仓库位置：

```powershell
git -C "D:\Projects\CareerLens-public" init -b main
git -C "D:\Projects\CareerLens-public" rev-parse --show-toplevel
```

确认第二条命令输出指向 `D:\Projects\CareerLens-public`（Git 也可能显示为 `D:/Projects/CareerLens-public`），再添加文件。如果输出是其他目录，停止操作并检查路径。

然后执行以下命令：

```powershell
git -C "D:\Projects\CareerLens-public" add .
git -C "D:\Projects\CareerLens-public" diff --cached --name-only
git -C "D:\Projects\CareerLens-public" diff --cached --check
git -C "D:\Projects\CareerLens-public" commit -m "Initial source-only release"
git -C "D:\Projects\CareerLens-public" remote add origin https://github.com/wWYANG666/BOSS.git
git -C "D:\Projects\CareerLens-public" push -u origin main
```

推送前确认暂存文件不包含 `.env`、`.runner-data/`、数据库、简历或浏览器资料。
使用 Git 凭据管理器登录，不把访问令牌写入远程地址。

请勿从原项目目录推送旧历史，也不要强制添加被 `.gitignore` 排除的运行文件。

## 启动

要求 Node.js 24、JDK 21、Python 3.11+。

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\start-dev.ps1
```

也可以双击 `启动CareerLens.bat`。真实平台模式需要自行配置密钥和登录，详见 README。
