# 优化建议模块

本项目使用 Android Feedback Kit 1.2.0 的正式 AAR，来源为用户提供的 android_shared_module/feedback/1.2.0 发布包。

- 文件：feedback-kit-1.2.0.aar
- SHA-256：4c55a583a2641dfb68a90700a0759adec3e4ab1562dc5ccef7cbbc06e2896ade
- 原发布日期：2026-11-13（Asia/Shanghai）
- 默认存储：应用私有数据库与截图目录，A/B/C/D 独立。
- 通过模块 Manifest 合并私有 Activity 和各自 applicationId 的附件 Provider，不添加权限。
- 设置页提供查看、新建、备份和确认后恢复；备份使用独立 ZIP，不包含股票截图数据。

采用随工程保存的固定 AAR，使 GitHub 构建无需访问开发机绝对路径。更新模块时同时核对发布包 SHA-256、兼容性说明和备份契约。
