# 旧路径与迁移包的对应关系

会话与源码快照保留原文，里面的绝对路径属于旧电脑。在新电脑上按以下对应关系读取；不必修改原始会话记录。

| 旧记录中的路径 | 本包中的位置 |
| --- | --- |
| D:\Program\Projects\Android\projects\ControlMonitor\StockShotManager | 包根下 StockShotManager/，这是继续开发的独立 Git 仓库 |
| 父目录 股票截图管理系统_需求文档_v20.md | docs/股票截图管理系统_需求文档_v20.md |
| 父目录 PROJECT_PROGRESS_REPORT.md | docs/PROJECT_PROGRESS_REPORT.md，仅作历史参考 |
| 父目录 scr1.png、_crop_full.png、_crop_mid.png | references/ 下同名文件 |
| 旧电脑 D:\Program\Projects\Android\jdk、sdk | 用新电脑实际安装目录，按 ENVIRONMENT.md 运行配置工具 |
| C:\Users\HD\.codex\attachments 下的会话附件 | codex/attachments/；准确来源映射在 codex/attachments-map.json |
| C:\Users\HD\.codex\sessions 下的原始记录 | codex/raw-rollouts/；解压 JSONL 在 codex/raw-decompressed/，可读事件消息在 codex/raw-messages/ |
| 旧 Codex worktree 中 StockShotManager 的路径 | 当前源码以 StockShotManager/ 的 main / 792f01d 为准，旧 worktree 路径仅描述历史环境 |

codex/chats/<chat-id>/messages.md 按原会话顺序保留用户与助手原文；同目录 turns.jsonl、items.jsonl 等文件保留完整结构化历史。先从 codex/README.md 的会话表定位需要的记录。

本包父目录不是原 ControlMonitor 工程。读取包中的 docs/codex 资料不代表需要改动或补建 ControlMonitor；StockShotManager 自身没有对父项目模块的构建依赖。