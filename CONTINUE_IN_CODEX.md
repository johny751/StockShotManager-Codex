# 给新电脑 Codex 的接续提示词

在 Codex 中打开本包的 StockShotManager 子目录后，复制下面这一段作为新聊天的第一条消息：

```text
请继续此迁移包中的 StockShotManager 安卓项目，全程用中文交流。

先读取当前目录 AGENTS.md、BUILD_NOTES.md、RELEASE_NOTES.md，以及上一级目录的 START_HERE.md、docs/MIGRATION_STATUS.md、docs/ENVIRONMENT.md、docs/FILE_PATH_MAP.md、codex/README.md 的会话索引。根据索引读取相关聊天 Markdown 与完整 JSON 记录，重点核对 2026-09-27～28 的最高价/备注/固定列实现及 v1.9 修复。

当前预期版本为 1.9(10)，main 的 HEAD 为 792f01d1cf25054bbd0829c5a7fef505c85a8fb4。先用当前源码及 Git 状态验证，不要依赖旧进度报告。_analyze.py 是原有未跟踪工具，请保留。只处理 StockShotManager 工程。

确认新电脑 JDK 17、Android SDK 34 / Build Tools 34.0.0 的实际路径。需要时使用上一级 tools/Configure-Environment.ps1 配置，不沿用旧电脑 D:\Program\Projects\Android 的路径。参考图在上一级 references/scr1.png。

改功能后按仓库规则先本地 Git 保存，再同时构建 A/B/C/D 签名 Release APK，并核对版本、包名和签名。已随包保留原签名资料。现有 268 项测试与四版 lint 结果是 2026-09-28 的历史证据；有修改时按修改范围重新验证。

当前需要继续的内容/手机反馈：
【在这里写本次具体需求，或把测试反馈粘贴在这里。若是手机仍无法更新最高价，请提供程序诊断日志。】
```

这是可编辑的接续提示词，不代表存在尚未完成的新功能指令。当前源码/验收状态详见 docs/MIGRATION_STATUS.md。