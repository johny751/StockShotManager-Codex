# 在新 Windows 电脑配置 StockShotManager

项目快照保持原文件内容，包括原电脑的 JDK/SDK 路径。先安装环境，再运行包内配置工具，工具仅更新你指定的项目目录，并保存修改前文件；不会修改系统全局环境变量或签名口令。

## 必需环境

| 组件 | 要求或项目版本 |
| --- | --- |
| Windows shell | Windows PowerShell 5.1 或 PowerShell 7 |
| Git | 安装 Windows 版 Git，并让 `git` 在 PATH 中可用 |
| 构建 JDK | JDK 17；原电脑使用 Eclipse Temurin 17.0.19+10，Windows x64 |
| Gradle | 8.5；项目自带 wrapper，无需单独安装 Gradle |
| Android Gradle Plugin | 8.2.2 |
| Kotlin / KSP | 1.9.22 / 1.9.22-1.0.17 |
| Android 平台 | API 34，SDK Manager 中安装 `platforms;android-34` |
| Android Build Tools | 34.0.0，包含 `aapt.exe`、`apksigner.bat` |
| Android Platform Tools | 实机安装/调试时需要，提供 `adb` |
| Python / Pillow | 仅运行参考图片分析工具时需要，Android 构建不需要 |

官方安装入口：[Git for Windows](https://git-scm.com/install/windows)、[Temurin JDK 17](https://adoptium.net/temurin/releases/?version=17)、[Android Studio / Android SDK](https://developer.android.com/studio)、[sdkmanager 说明](https://developer.android.com/tools/sdkmanager)、[Python for Windows](https://www.python.org/downloads/windows/)。Gradle wrapper 从 [Gradle 8.5 官方分发地址](https://services.gradle.org/distributions/gradle-8.5-bin.zip) 获取 Gradle。首次构建或测试可能需要联网下载 Maven 依赖和 Robolectric 的 Android 测试组件；项目已配置阿里云镜像及 Google/Maven Central 仓库。

Android Studio 的 SDK Manager 可安装所需组件。若已安装命令行工具，也可执行以下命令；请按实际 SDK 位置调整路径，按提示处理 SDK 许可：

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat" "platform-tools" "platforms;android-34" "build-tools;34.0.0"
```

`app/build.gradle` 中 Java `sourceCompatibility` / `targetCompatibility` 和 Kotlin `jvmTarget` 为 11，这是应用代码生成的字节码目标。运行 Gradle/AGP 的 Java 环境仍须 JDK 17；不要将构建 JDK 改为 11 或 8。

## 配置新电脑路径

在解压包根目录运行，替换 JDK 路径为实际安装目录。带空格的目录支持；由于原构建脚本使用 batch 变量展开，JDK、SDK 和项目路径不能含 `%` 或 `!`。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Configure-Environment.ps1 -JdkHome "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot" -SdkHome "$env:LOCALAPPDATA\Android\Sdk" -WhatIf
```

`-WhatIf` 会校验 JDK 17 可执行文件、API 34 平台、Build Tools 34.0.0 和配置行唯一性，并显示计划，不写文件、不创建备份。验证成功后执行实际配置：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Configure-Environment.ps1 -JdkHome "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot" -SdkHome "$env:LOCALAPPDATA\Android\Sdk"
```

默认配置包根目录下的 `StockShotManager`；项目移动到其他位置时增加 `-ProjectRoot "C:\Work\StockShotManager"`。工具更新 `gradle.properties` 的 `org.gradle.java.home`、`local.properties` 的 `sdk.dir`，以及 `build-apk.bat` 内部的 `JAVA_HOME` / `SDK_DIR`。单独设置外部 `JAVA_HOME` 不足以修正原构建脚本，因为脚本自身会重新赋值。

原文件按字节保存在项目的 `build\migration-config-backup\日期时间_唯一编号\` 中。`backup-manifest.json` 记录配置前哪些文件存在；工具会打印备份目录。已有配置行重复、JDK/SDK 缺失或版本不符时，工具在写入前报错，不会部分更新。输出采用 UTF-8 无 BOM；其他配置项与签名口令保持原样。写入失败会尝试从内存中的原文件恢复。配置后的两处已跟踪文件路径变化会出现在 `git status`，`local.properties` 和备份目录由原 `.gitignore` 忽略。

## 构建、测试和发布

先阅读项目的 `AGENTS.md`、`BUILD_NOTES.md` 和 `RELEASE_NOTES.md`。调用 `gradlew.bat` 时，wrapper 在读取项目 Gradle 配置之前就需要启动 Java，因此还需在当前 PowerShell 终端设置下面两项。请将 JDK 路径替换为实际安装路径；这些赋值只影响当前终端及其子进程，不修改系统全局环境，打开新终端后需重新设置。

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
Set-Location .\StockShotManager
.\gradlew.bat --version
```

四版单元测试与 Release lint：

```powershell
.\gradlew.bat testAlphaDebugUnitTest testBetaDebugUnitTest testGammaDebugUnitTest testDeltaDebugUnitTest lintAlphaRelease lintBetaRelease lintGammaRelease lintDeltaRelease --max-workers=2 --console=plain
```

四版签名 Release APK 发布：

```powershell
.\build-apk.bat nopause
```

脚本构建、检查 A/B/C/D 包名及版本、校验四包签名证书一致，并导出 `StockShotManager-{A|B|C|D}-release-latest.apk` 及同一时间戳副本。发布代码改动应先提交，再构建，因为 APK 的 `BuildConfig.GIT_COMMIT` 在构建时读取 HEAD；每轮 A/B/C/D 同时构建并安装，以免版本差异干扰实机对比。日常增量构建无需 `clean`，只在明确需要时使用 `build-apk.bat clean nopause`。

长构建建议在隐藏后台进程中重定向到 `*.log`，确认旧构建已结束后才启动新构建。新电脑的四版单元测试、lint、签名发布以及手机授权/采集/网络验收，应以新电脑的实际结果为准；包内旧报告仅记录原电脑验证。

## 运行保留的参考图片分析工具

`StockShotManager\_analyze.py` 保持原样，原文件中图片 PATH 指向旧电脑父目录。wrapper 在运行时将其指向包内 `references\scr1.png`，不修改原源码：

```powershell
python -m pip install Pillow
python .\tools\Analyze-Reference.py
python .\tools\Analyze-Reference.py --image "C:\Work\Reference Images\other.png"
```

分析工具可从任意工作目录执行；运行自定义项目副本时也可通过 `--script "C:\Work\StockShotManager\_analyze.py"` 指定原分析脚本。
