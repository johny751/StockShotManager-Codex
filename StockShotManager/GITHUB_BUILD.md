# GitHub 构建与签名发布

StockShotManager 现在是现有 `StockShotManager-Codex` 仓库中的普通源码目录；仓库根目录的 `.github/workflows/android-release.yml` 负责云端构建。后续先提交并推送源码，再在 GitHub 构建。应用版本仍为 1.9（10），APK 内的 Git 号对应本次云端检出的外层仓库提交。

## 首次配置签名

在 GitHub 仓库打开 **Settings → Secrets and variables → Actions → New repository secret**，添加以下五项：

| Secret 名称 | 内容 |
| --- | --- |
| `SSM_KEYSTORE_BASE64` | 原 `keystore/stockshot.jks` 文件的 Base64 编码 |
| `SSM_STORE_PASSWORD` | 原 keystore 口令 |
| `SSM_KEY_ALIAS` | 原签名密钥别名 |
| `SSM_KEY_PASSWORD` | 原密钥口令 |
| `SSM_EXPECTED_CERT_SHA256` | 原发布证书的 SHA-256 指纹，64 位十六进制，可带冒号 |

本地原 keystore 与签名配置保留在忽略文件中，不能改用新建的密钥，否则无法覆盖安装原版。不要把口令、Base64 或私钥写进工作流、聊天、Issue 或源代码。Base64 只用于表示二进制内容，并非加密。

如需从 PowerShell 复制原文件的 Base64 到剪贴板，在 `StockShotManager` 目录执行以下命令，再粘贴到 Secret 的值输入框：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes((Join-Path $PWD 'keystore/stockshot.jks'))) | Set-Clipboard
```

口令与别名请从仅本地保留的签名配置读取。证书指纹应来自已验证的原发布 APK 或 keystore；迁移包中的 `evidence/migration-apk-verification.json` 也记录了旧包的校验结果。工作流必须同时匹配这份原证书指纹，不能只检查四包相互一致。

## 运行与下载

1. 完成 Git 提交并推送到 `main`。源码目录或工作流变动会自动触发构建。
2. 也可在仓库 **Actions → StockShotManager signed release → Run workflow**，选择 `main` 手动运行。
3. 等待运行成功，在该次运行页面的 **Artifacts** 下载 `StockShotManager-ABCD-release-运行号-提交号`。
4. 压缩包内有 `StockShotManager-A-release.apk` 至 `StockShotManager-D-release.apk`，以及含包名、版本、证书、文件 SHA-256 和提交信息的 `verification.txt`。四版应使用同一次运行产物。

工作流使用 Windows Server 2022、JDK 17、Gradle wrapper、Android SDK 34 和 Build Tools 34.0.0。它在一个 Gradle 调用中执行四版 Debug 单元测试、四版 Release lint 和 `assembleRelease`，随后检查四包包名、源码版本、Git 提交、有效签名以及原证书指纹。任一步失败都不会上传 APK；已生成的单测和 lint 报告仍会作为另一个 Artifact 保留 30 天。APK Artifact 同样保留 30 天，需要长期保存时自行下载归档。

签名文件仅在构建步骤从 Secrets 解码到 runner 临时目录，结束时删除，不作为 Artifact 上传。工作流只在 `main` 执行，不接受 PR 触发。所有外部 Actions 都固定到已核对的完整提交 SHA。

## 验证状态与故障定位

迁移包中的 268 项单测与四版 lint 是 2026-09-28 的历史证据，不能视为本次云端验证结果。只有本次 GitHub 运行的日志与 Artifacts 能证明本次构建通过。云端未连接手机，录屏/无障碍/存储授权、长备注行高和固定列操作、实际手机行情网络仍需实机验收。

出现 `Missing repository secret` 时补齐对应 Secret 后重新运行；证书不匹配时核对是否填入原签名资料，不要通过删除指纹检查绕过。下载单测/lint 报告查看实际失败位置；若 Kotlin 增量缓存出现历史记录中的异常，先根据日志确认，避免日常构建盲目 clean。

## 原历史备份

修复前内层 Git 的基线是 `792f01d1cf25054bbd0829c5a7fef505c85a8fb4`，原 Git 历史留在本机外层 `.git/stockshot-migration-backup/original.git`，不会随普通 push 上传。`_analyze.py` 保留本地未跟踪状态。

原迁移包的 `history/StockShotManager.bundle` 包含签名文件及旧口令配置，曾随外层仓库初始提交上传。停止跟踪该文件或新增忽略规则不能清除已发布的旧 Git 历史；如仓库公开，需要另外处理远端历史及签名暴露问题。此次目录修复和工作流配置不等于已经清除历史内容。

官方说明：[GitHub Actions Secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets)、[下载构建产物](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/download-workflow-artifacts)。
