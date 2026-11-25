# AGENTS.md — 开工必读

本工程现为上一级 StockShotManager-Codex 仓库中的普通源码目录。
仅处理 StockShotManager 及其必要的 .github 构建配置；不得改动其他安卓工程。
原独立仓库历史已备份到上一级 .git/stockshot-migration-backup/original.git，基线为 792f01d。
后续正式构建只在 GitHub Actions 运行，步骤见 GITHUB_BUILD.md；签名资料从 Actions Secrets 注入。
Windows + PowerShell 环境。下面是高频坑点摘要，详细说明见 BUILD_NOTES.md。

## 环境坑点（务必先看）
1. apply_patch 可能报 "Access is denied"：改用 .NET
   `[System.IO.File]::ReadAllText/WriteAllText` 做精确替换（UTF-8 无 BOM），
   替换前校验旧片段唯一。
2. Remove-Item 可能 "blocked by policy"：改用
   `[System.IO.File]::Delete` 逐个删明确路径的临时文件。只删调试临时文件，
   绝不批量删或删用户图片/设备数据。
3. 中文控制台回显乱码只是显示问题，源文件正确、编译正常，不要因此改源码。
4. Gradle 构建期间 CPU 打满，新起命令常 10s 超时：长构建用后台
   (Start-Process/Start-Job) 重定向到日志，隔段时间轮询，别在前台干等。
5. 所有 git 命令带 safe.directory：
   `git -c safe.directory=<当前外层仓库绝对路径> <子命令>`
6. 提交身份用 `-c user.name=codex -c user.email=codex@local`，不要改全局 git 配置。
7. LF/CRLF 警告正常，可忽略。

## 构建/发布规则
- 版本信息里的 Git 号来自 `BuildConfig.GIT_COMMIT`（构建时读 HEAD）：
  **先 git commit，再 assembleRelease**，否则 APK 内嵌的是旧提交号。
- A/B/C/D 四个 flavor 必须同时重建；实机对比时同时覆盖安装，保持同一版本。
  一键脚本 build-apk.bat 会同时产出四个签名 APK；自动化调用使用 `build-apk.bat nopause`。
- 包名：A=com.stockshot.manager，B=com.stockshot.manager.b，
  C=com.stockshot.manager.c，D=com.stockshot.manager.d。
- 默认截图目录分别为 Downloads/StockScreenshots、StockScreenshotsB、StockScreenshotsC、StockScreenshotsD。
  四个包保留共同的代码 namespace；无障碍服务标签按版本区分。

## 提交前清理
调试临时文件统一用 `*.log` 后缀（已被 .gitignore 忽略），
形如 `_xxx.err/.out` 的文件不在忽略范围，提交前按第 2 点清理干净。
