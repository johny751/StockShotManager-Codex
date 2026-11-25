# StockShotManager：换电脑继续开发

这是 StockShotManager v1.9(10) 的开发迁移包，Git 提交 792f01d，导出日期为 2026-11-25（北京时间）。

## 在另一台电脑的 Codex 中继续

1. 完整解压到一个短路径，例如 D:\Projects\StockShotManager-Codex。项目源码在解压目录的 StockShotManager 子文件夹。
2. 建议先运行 `powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Verify-Package.ps1`，校验原始文件。
3. 安装/准备 Git、JDK 17、Android SDK 34 与 Build Tools 34.0.0。执行 `tools\Configure-Environment.ps1 -JdkHome '你的JDK目录' -SdkHome '你的SDK目录'`，完整命令及验证方法见 docs/ENVIRONMENT.md。
4. 在新电脑的 Codex 中添加并打开 StockShotManager 项目目录，新建一个聊天。把 CONTINUE_IN_CODEX.md 的提示词发给 Codex，补上你现在想继续的功能或手机反馈。
5. Codex 读取这里的交接说明和会话文件后即可沿当前代码继续。无需先恢复旧聊天列表。

## 包含内容

| 路径 | 内容 |
| --- | --- |
| StockShotManager/ | 全部源码、资源、构建配置、AGENTS.md、BUILD_NOTES.md、RELEASE_NOTES.md、Gradle wrapper、签名文件、原 .git、_analyze.py 与四个最新版 APK |
| history/ | Git bundle、完整提交日志、原工作区状态、暂存及未暂存二进制补丁 |
| docs/ | 原需求文档、旧进度报告、当前状态交接、环境配置说明与旧路径映射 |
| references/ | 识别分析工具所需 scr1.png 及现有裁剪参考图片 |
| codex/ | 相关主/子聊天的可读消息、完整结构化记录、原始会话归档、引用附件、路径映射及导出报告 |
| evidence/ | 原单元测试/lint/发布记录及本次源码/APK校验结果 |
| tools/ | 新电脑环境配置、文件完整性校验、参考图分析、会话导出工具 |
| PACKAGE_MANIFEST.json / SHA256SUMS.txt | 包内文件清单、大小和 SHA-256 |

## 会话接续方式

本包同时提供可读 Markdown 和原始/结构化会话备份。它们可由新聊天直接读取，保存用户要求、实现过程及测试结果。

现有工具没有经过验证的桌面聊天跨电脑导入接口。原始 .jsonl/.zst 中部分历史使用分页基线，单独复制这些文件不能保证在桌面聊天列表恢复出完整旧聊天。因此按上面的“打开项目 + 新聊天读取交接文件”方法继续最可靠；详细来源和限制见 codex/README.md。包内未包含整套 Codex 全局状态数据库或账号登录文件。

## 最近开发记录入口

- [v1.8 功能需求：最高价、所选日期采集、备注和固定列](codex/chats/01a0e0d9-d0e9-7f92-b236-4271acc325fe/messages.md)
- [v1.8 交付及 v1.9 修复、行情备用源和诊断日志](codex/chats/01a0e29e-ae43-7d71-8a33-520bb8be1ec1/messages.md)
- [本次迁移执行记录的原始消息](codex/raw-messages/sessions/2026/11/24/rollout-2026-11-24T22-46-20-01a20de5-5e5f-7e30-8f07-f34b2a3e1d56_01a20e05-a317-7f22-be75-ffebc6f1ca04.jsonl.messages.md)

会话导出截止 2026-11-25 07:35:42（北京时间）。活动会话的数据库投影可能晚于原始记录；本次迁移聊天的过程消息从上面的原始消息入口读取。早期开发记录和所有子聊天都在 codex/README.md 的索引中。

## 构建与使用

源码当前保留原电脑路径。先校验、再配置路径，最后进入 StockShotManager 运行 `build-apk.bat nopause`；它同时构建和校验 A/B/C/D 四个签名 APK。修改功能后先本地提交再发布，让 APK 的 Git 号对应修改。

源码目录中的四个 `StockShotManager-{A|B|C|D}-release-latest.apk` 是 2026-09-28 已有的 v1.9 发布包，已在本次迁移重新校验。当前手机验收项和已知状态见 docs/MIGRATION_STATUS.md。

环境配置会有意修改快照的机器路径；因此配置之后原始 SHA-256 校验出现这些文件差异是正常的。原项目没有被修改。