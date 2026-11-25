# StockShotManager

StockShotManager 安卓工程位于 [StockShotManager/](StockShotManager/)，当前应用版本为 **1.9（10）**。A/B/C/D 四个版本可同时安装，各自保存数据库、设置和截图目录。

## 在 GitHub 构建

先按照 [GitHub 构建说明](StockShotManager/GITHUB_BUILD.md) 配置原签名的 Actions Secrets。提交并推送到 main 后，工作流会运行四版单元测试和 Release lint，再同时构建、验证四个签名 APK。在 Actions 的成功运行页面下载包含 A/B/C/D 的 Artifact。

## 开发与迁移资料

- [工程规则](StockShotManager/AGENTS.md)、[构建记录](StockShotManager/BUILD_NOTES.md)、[版本说明](StockShotManager/RELEASE_NOTES.md)。
- docs、evidence 和 START_HERE.md 保留迁移时的历史资料；其中测试和 lint 是 2026-09-28 的证据，不能替代当前 Actions 验证。
- PACKAGE_MANIFEST.json 和 SHA256SUMS.txt 描述原迁移包快照，目录整理与后续代码修改后不再代表当前工作区。
- 原独立仓库基线为 792f01d1cf25054bbd0829c5a7fef505c85a8fb4，完整历史留在原电脑的本地 Git 备份中，不作为子模块上传。当前 StockShotManager 是普通源码目录。
