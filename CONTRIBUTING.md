# 参与贡献 LiHarness

谢谢你愿意参与！LiHarness 既是一个**软件项目**，也是一套**教程**，代码和文档的贡献同样欢迎。

## 可以贡献什么

| 类型 | 例子 |
|---|---|
| 🧩 新插件（第二阶段起） | 新的工具、技能、钩子；模型适配等内核能力请直接改 `liharness-core` |
| 📖 教程勘误 | 错别字、讲错的概念、跑不通的步骤、过时的依赖版本 |
| 🧭 踩坑记录 | Windows / Linux 上的环境问题与解决办法 |
| 🐛 Bug 修复 | 先开 Issue 描述复现步骤，再提 PR |
| 💡 设计讨论 | 涉及插件接口或架构的改动，先开 Issue 讨论，必要时补一份 ADR |

## 开发环境

见 [docs/guide/00-setup.md](docs/guide/00-setup.md)。简要来说：

- JDK 17（推荐 Temurin）
- Node.js 20+
- Rust stable（Tauri 外壳需要）
- 平台依赖按 [Tauri 前置条件](https://v2.tauri.app/start/prerequisites/) 安装

## 提交前自检

```bash
./mvnw -q verify                      # 后端编译 + 测试
cd desktop && npm run lint && npm run build
cd src-tauri && cargo clippy -- -D warnings
```

## 分支与提交规范

- 从 `main` 拉分支：`feat/xxx`、`fix/xxx`、`plugin/xxx`、`docs/xxx`
- 提交信息遵循 [Conventional Commits](https://www.conventionalcommits.org/zh-hans/)：

```text
feat(core): 工具执行前检查工作区边界
fix(app): SSE 连接断开后释放 Agent 回合
feat(plugin-llm-openai): 支持 reasoning_content 流式输出
docs(guide): 第 02 章补充 Windows 下的进程回收说明
```

- scope 取值：`core` / `app` / `plugin-api` / `desktop` / `guide` / `docs` / `ci`

## 架构约定（重要）

1. **内核不依赖 Spring。** `liharness-core` 和 `liharness-plugin-api` 是纯 Java；Spring Boot 只出现在 `liharness-app` 里。
2. **插件只能加能力，不能换内核。** 扩展点只有工具、提示词段落、钩子、事件订阅、技能（见 ADR-0007）。不要为插件开放替换模型适配、Agent 循环、会话存储的接口。
3. **`liharness-plugin-api` 是唯一的插件契约。** 插件只能依赖它。修改它要谨慎，并在 PR 里说明兼容性影响。
4. **插件注册的一切都要可撤销。** 每次注册都返回 `Registration`，由插件上下文统一回收，插件停用后不能留下任何东西。
5. **工具出错不中断 Agent 循环。** 把错误作为观察交给模型，让它自己修正。
6. **前端不执行命令。** 任何本机操作要么走后端（受护栏与审批约束），要么走 Tauri command（固定的、有限的能力）。

## 代码风格

- Java：4 空格缩进，遵循 [Google Java Style](https://google.github.io/styleguide/javaguide.html) 的命名约定；公开类型写 Javadoc
- 优先用 `record` 表示不可变数据，用 `sealed interface` 表示封闭的类型层次（JDK 17 可用）
- 不要吞异常；不要在日志里打印 API Key、令牌、完整请求头
- TypeScript：开启 `strict`；与后端事件对应的类型集中放在 `desktop/src/api/types.ts`

## 文档约定

- 教程章节统一结构：本章目标 → 为什么 → 知识点 → 跟着写 → 验收标准 → 常见坑 → 练习
- 代码块标注语言（`java`、`xml`、`rust`、`ts`、`bash`）
- 引用外部资料请用自己的话重写并附原始链接，不要大段复制

## 行为准则

参与本项目即表示你同意遵守 [行为准则](CODE_OF_CONDUCT.md)。
