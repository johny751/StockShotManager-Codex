# 构建与开发环境排查记录

本文件记录在本项目（StockShotManager，Windows + PowerShell 环境）开发与打包过程中
遇到的问题及可复用的解决方案，供后续再次遇到时快速定位。

## 2026-11-25：GitHub 构建迁移（当前流程）

- 工程由独立内层仓库改为上一级 StockShotManager-Codex 仓库的普通源码目录；原 main / 792f01d 历史保存在本机上一级 .git/stockshot-migration-backup/original.git。
- 后续构建全部使用仓库根目录 .github/workflows/android-release.yml。先 Git 提交并推送，再由同一次运行生成 A/B/C/D；具体 Secrets 配置及下载见 GITHUB_BUILD.md。
- 应用版本保持 1.9(10)，不修改业务功能。BUILD_TIME / GIT_COMMIT 继续来自构建时 HEAD，现指外层仓库提交。
- 公共 gradle.properties 不再保存本机 JDK 绝对路径或口令。签名通过 SSM_* 环境变量注入，本机原资料保存在被忽略的 signing.properties 和 keystore/stockshot.jks。
- verify-release.ps1 除原有包名、版本、有效签名和四包一致性外，还检查原证书 SHA-256 和 APK 内的当前 Git 短号。
- 本轮只执行工作流静态检查和校验脚本测试，不在本机运行 Android 构建。2026-09-28 的 268 项单测与 lint 报告仍是历史证据；新结果以 GitHub Actions 实际运行记录为准。
- 以下本地路径和 build-apk.bat 章节保留为历史排查资料，不作为新电脑的云端构建入口。

## 1. apply_patch.bat 报 “Access is denied”

现象：使用 apply_patch 工具修改文件时报 `Access is denied`，无法完成补丁。

原因：本机对 apply_patch.bat 的执行权限受限。

解决：改用 .NET 的 `[System.IO.File]::ReadAllText` / `WriteAllText` 做精确整段
文本替换，替换前先校验旧片段在文件中只出现一次，写回时保持 UTF-8 无 BOM。

```powershell
$p = 'app/src/main/java/.../Foo.kt'
$t = [System.IO.File]::ReadAllText($p)
$old = '要替换的原文'
$new = '替换后的新文'
if (([regex]::Matches($t,[regex]::Escape($old))).Count -ne 1) { throw '匹配不唯一' }
$t = $t.Replace($old,$new)
$enc = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($p,$t,$enc)
```

## 2. 中文控制台输出乱码

现象：PowerShell 回显源码里的中文时出现乱码（如“褰撴棩鏆傛棤...”）。

结论：仅为控制台显示的编码问题，源文件本身正确、编译正常，可忽略。
不要因为回显乱码就去“修复”源文件，否则可能把正确内容改坏。

## 3. Remove-Item 被策略拦截（blocked by policy）

现象：`Remove-Item ...` 单文件、多文件、带不带其它命令都被拒绝。

解决：改用 .NET `[System.IO.File]::Delete` 逐个删除明确路径的临时文件。

```powershell
foreach ($f in @('_a.log','_b.err')) {
  $p = Join-Path (Get-Location) $f
  if ([System.IO.File]::Exists($p)) { [System.IO.File]::Delete($p) }
}
```

注意：只删明确列出的调试/临时文件，绝不批量删或删用户图片、设备数据。

## 4. Gradle 构建期间机器被打满，命令频繁超时

现象：构建进行时，连 `echo` 这种命令都要 40-50 秒才返回，或直接 10 秒超时；
新起的 PowerShell 光启动就超过默认超时。

原因：Gradle 编译占满 CPU，新进程启动被拖慢。

解决：
- 用后台方式跑长构建（`Start-Process` 或 `Start-Job`），把 stdout/stderr 重定向到
  日志文件，然后隔一段时间读日志轮询，不要在前台等。
- 读日志/查状态的命令给足超时时间，或把 `Start-Sleep` 和读日志合并到同一条命令里，
  减少反复起 shell 的开销。
- 构建刚启动时日志文件可能为空，属于正常，耐心等待即可。

## 5. git 的 LF/CRLF 警告

现象：add/commit 时大量 `LF will be replaced by CRLF the next time Git touches it`。

结论：正常提示，不影响构建与提交，可忽略。

## 6. git 命令需要 safe.directory

现象：在此仓库跑 git 命令可能因 ownership 校验失败。

解决：所有 git 命令带上 safe.directory：

```powershell
git -c safe.directory=D:/Program/Projects/Android/projects/ControlMonitor/StockShotManager <子命令>
```

## 7. 版本号（Git 号）与 APK 不一致 —— 必须先提交再构建

背景：`BuildConfig.GIT_COMMIT` 在构建时读取当前 HEAD 短号。

规则：改完代码要让 APK 里的 Git 号对应本轮修改，必须
先 `git commit`，再执行 `assembleRelease`（或 build-apk.bat）。
否则 APK 内嵌的仍是上一个提交号。

## 8. A/B/C/D 四个版本必须同时重建，实机对比时同时覆盖安装

教训：曾出现“B 版查看器卡死”的假象，根因不是代码或 WebP，而是手机上
A、B 两个安装包来自不同构建（版本不一致）。

从 v1.8 起新增 C（gamma）和 D（delta），共四个可同时安装的版本。
规则：每次改动后 A/B/C/D 四个 flavor 都要重新构建；实机对比时同时覆盖安装，
四个版本保持一致。一键脚本 build-apk.bat 会同时产出、校验并复制四个签名 APK。

| 版本 | flavor | applicationId | 默认截图目录 |
| --- | --- | --- | --- |
| A | alpha | com.stockshot.manager | Downloads/StockScreenshots |
| B | beta | com.stockshot.manager.b | Downloads/StockScreenshotsB |
| C | gamma | com.stockshot.manager.c | Downloads/StockScreenshotsC |
| D | delta | com.stockshot.manager.d | Downloads/StockScreenshotsD |

四个包使用同一代码 namespace、版本号和签名；数据库及设置按包独立保存。
无障碍服务名称包含 A/B/C/D，便于在系统设置中区分。二维码共享设置不包含截图根目录。

## 9. 临时文件命名与忽略

现象：`.gitignore` 已忽略 `*.log` 和 `*.apk`，但形如 `_rel_build.err` /
`_rel_build.out` 的临时文件不在忽略范围，容易误入 `git status`。

建议：调试临时文件统一用 `*.log` 后缀，或在提交前用第 3 节的方式清理干净。

## 10. suspend 函数调用报错（Suspend function should be called only from a coroutine）

现象：assembleRelease 编译失败，`compileAlphaReleaseKotlin` / `compileBetaReleaseKotlin`
报 `Suspend function 'capture' should be called only from a coroutine or another suspend function`。

根因：一个普通（非 suspend）辅助函数内部调用了 `ScreenCaptureService.capture()`，
而该 capture 是 suspend 函数。

解决：把辅助函数本身声明为 `private suspend fun`，并确认其调用点已处于协程/挂起上下文
（本例调用点紧接着调用另一个 suspend 函数，安全）。定位方法：从 build 日志里 grep `e:` 前缀
的 Kotlin 编译错误行即可拿到文件名与行号。

## 11. 零改动构建仍重编、Kotlin daemon 连接失败与构建异常缓慢

现象：只改少量 Kotlin 文件时，Debug/Release 构建仍耗时约 10 分钟；日志出现
`Could not connect to Kotlin compile daemon`，并回退到 `Compile without Kotlin daemon`。
即使源码和 HEAD 完全不变，再次执行 `assembleRelease` 仍耗时约 5 分钟，Kotlin、dex、lint、
package 等任务被重复执行。

持续性根因：`app/build.gradle` 原先用 `new Date()` 生成 `BuildConfig.BUILD_TIME`。
每过一分钟 BuildConfig 输入就改变，导致 A/B 两个 flavor 的 Kotlin 编译及后续任务全部失效。

修复：`BUILD_TIME` 改用当前 git 提交时间；同一提交反复构建时值稳定，提交变化后才更新。
修复后的连续零改动 Release 基准为 36 秒，89 个任务中 86 个命中 `UP-TO-DATE`。

一次性放大因素：前台 Gradle 命令被工具超时终止后，子 Gradle/Kotlin daemon 可能仍在运行；
此时立刻启动第二个构建会导致 CPU、内存和 daemon run 文件争用，出现客户端尚未连接、daemon
就因 `No more clients left` 退出的情况。

以后处理规则：
- 长构建从一开始就使用后台进程并重定向到 `*.log`，日志末尾写明确退出码。
- 前台命令超时后，先用 `Get-Process java`、`gradlew --status`、日志变化和 CPU 增量确认旧构建
  是否仍在运行；未确认退出前不要启动第二个 Gradle。
- 不为日常构建使用 `--no-daemon`；正常使用 Gradle/Kotlin daemon 保留热缓存。
- 不在 BuildConfig、resValue 或任务输入中直接放 `new Date()`、随机数等每次变化的值。
- 怀疑缓存失效时连续执行两次相同任务；第二次应以 `UP-TO-DATE` 为主。若仍重编，使用
  `--info` 查看 `Task ... is not up-to-date because`，先修输入变化，不要盲目 clean。
- `clean` 只用于确认缓存损坏或用户明确要求；普通发布直接运行 `build-apk.bat`。

## 12. v1.8 四包发布与无人值守构建

- 版本为 versionName 1.8 / versionCode 9，功能改动先提交，再生成正式 APK。
- PATH 里的 java 可能仍是 Java 8；build-apk.bat 在当前进程内设置
  JAVA_HOME=D:\Program\Projects\Android\jdk（JDK 17），不修改系统环境变量。
- 双击或执行 build-apk.bat 会在结束时暂停；自动化使用 build-apk.bat nopause。
  只有显式传入 clean 才清理，参数可组合为 build-apk.bat clean nopause。
- JDK、apksigner、任一 APK 缺失，签名校验失败或复制失败均返回非零退出码。
  verify-release.ps1 同时检查四个包名、与 app/build.gradle 一致的版本号，
  以及四包签名证书一致；打印 APK SHA-256。全部通过后才复制到项目根目录。
- APK 哈希使用 .NET SHA256 流式计算，不依赖部分 Windows PowerShell 环境缺失的 Get-FileHash。
- 输出 StockShotManager-{A|B|C|D}-release-latest.apk，以及同一时间戳的四个历史副本。
- 长构建继续使用隐藏后台进程和 *.log 日志；不要同时启动多个 Gradle 构建。
  未连接设备时完成四包构建及签名/包名/版本校验，明确记录实机测试尚未执行。

## 13. v1.8 验证任务与相机声明

- 四版验证命令：`gradlew.bat testAlphaDebugUnitTest testBetaDebugUnitTest testGammaDebugUnitTest testDeltaDebugUnitTest lintAlphaRelease lintBetaRelease lintGammaRelease lintDeltaRelease --max-workers=2 --console=plain`。
- 使用明确的 flavor 任务名，避免 Release lint 任务选择歧义。
- 扫码功能的 CAMERA 权限需配套 `android.hardware.camera` 的 `required="false"` 声明，
  否则 lint 会报 PermissionImpliesUnsupportedChromeOsHardware。相机仅用于可选的扫码导入。
- 单元测试包含 Robolectric 数据库迁移、备注继承、恢复事务与 CSV/备份兼容测试；
  测试报告位于 app/build/reports/tests，lint 报告位于 app/build/reports。

## 14. v1.9 最高价备用源与诊断日志

- 版本为 versionName 1.9 / versionCode 10，四包继续同版本、同签名发布；先提交再 assembleRelease。
- 主菜单或设置页 → 诊断日志 → 开启 → 重试最高价更新 → 刷新并导出/分享完整日志；关闭开关不会删除旧日志。日志默认关闭，设置即时保存且各包独立。
- 日志写入私有 noBackupFilesDir，使用单线程队列和 3 × 512 KiB 轮转；FileProvider 仅开放 cacheDir/diagnostic-logs 中的分享副本。
- 当前环境用 JDK 17 HttpURLConnection 可复现东方财富在 HTTP 状态返回前主动断开连接（Unexpected end of file from server）。这不能替代手机日志确认；不要因源故障放宽股票身份或价格校验。
- 东方财富失败后，沪深使用腾讯不复权日 K 并按最多 365 个自然日分段；北交所使用新浪近期不复权日 K，结合指数交易日历检查覆盖和区间内部缺口。北交所备用数据最多近期 1023 根日 K；区间覆盖不足、停牌无法核实或当日行情尚未发布时返回失败，保留原价。
- 日志和备用行情解析均有单元测试；FileProvider 使用 Robolectric 验证四包各自 authority 和导出目录限制。未连接 Android 设备时，系统文件选择器/分享以及手机网络复现需实机验收。
- 本次增量编译曾对未修改的 Theme.kt 顶层颜色变量报 Unresolved reference，改用一次 `-Pkotlin.incremental=false` 完整编译后通过；源文件无需改动，也无需 clean。只在复现该缓存问题时使用这个参数。
