# 架构决策记录（ADR）

ADR 记录“**当时为什么这么决定**”。半年后回头看代码，你会忘了为什么插件不能依赖 Spring、为什么令牌不写进运行记录；ADR 就是写给那时候的你和新加入的同事看的。

| 编号 | 标题 | 状态 |
|---|---|---|
| [0001](0001-tauri-shell-java-backend.md) | 桌面形态：Tauri 2 外壳 + Java 后端子进程 | 已采纳 |
| [0002](0002-loopback-http-sse-token.md) | 前后端通信：本机 HTTP（REST + SSE）+ 启动令牌 | 已采纳 |
| [0003](0003-framework-agnostic-microkernel.md) | 后端结构：与框架无关的插件微内核，Spring Boot 只做应用层 | 已废弃（被 0007 取代） |
| [0004](0004-java17-spring-boot-341.md) | 版本基线：JDK 17 + Spring Boot 3.4.1 | 已采纳 |
| [0005](0005-rust-process-manager.md) | Rust 进程管家：端口、运行记录、遗留进程回收、优雅退出 | 已采纳 |
| [0006](0006-h2-append-only-event-log.md) | 存储：H2 文件库 + 追加式会话事件日志 | 已废弃（被 0010 取代） |
| [0007](0007-fixed-core-extension-points.md) | 固定内核 + 扩展点插件，插件不替换底层设施 | 已采纳 |
| [0008](0008-two-phase-delivery.md) | 分两阶段交付：先做最小可用 Harness，再做可扩展平台 | 已采纳 |
| [0009](0009-external-orchestration-via-adapters.md) | 外部框架（Spring AI、Google ADK）通过适配器接入，不进入内核 | 已采纳 |
| [0010](0010-jsonl-sessions.md) | 会话存储改用 JSONL 文件 | 已采纳 |
| [0011](0011-pull-based-model-stream.md) | 模型端口用“拉取式流 + 细粒度片段”，领域层拼装，取消由关闭流实现 | 已采纳 |
| [0012](0012-config-home-and-model-registry.md) | 用户配置目录 `~/.liharness` + YAML + 模型注册表；Key 放 `.credentials.yaml` 或环境变量 | 已采纳 |

## 新增 ADR

复制下面的模板，编号递增：

```markdown
# ADR-XXXX：标题

- 状态：提议 / 已采纳 / 已废弃（被 ADR-YYYY 取代）
- 日期：YYYY-MM-DD

## 背景
## 决定
## 备选方案
## 后果（好的和坏的）
```
