# ADR-0012：用户配置目录 `~/.liharness` + YAML + 模型注册表

- 状态：已采纳
- 日期：2026-11-27

## 背景

原来的计划是：第 04、05、06 章一直用 Mock，第 07 章才接真实模型，模型配置存在应用数据目录的 `settings.json` 里，界面上只有“Mock”和“我的模型”两个选项，API Key 由 Rust 存进系统钥匙串、启动时推给 Java。

问题：

- **太晚见到真实模型**：Agent Loop、工具的设计都要面对真实模型的行为（切块、思考、出错），一直对着 Mock 写，问题暴露得晚；
- **配置藏得太深**：应用数据目录在 macOS 上是 `~/Library/Application Support/<应用标识>`，用户找不到、不方便手改；
- **只能配一个模型**：加一家服务要改界面；
- **钥匙串的代价**：Java 要靠 Rust 转交 Key，开发模式下每次重新编译 macOS 都会弹授权框，用户也没法直接查看自己配了什么。

对照 DeepSeek Harness（dsh）：配置放在 `~/.dsh`，`settings.yaml` 里写 provider，Key 放单独的 `.credentials.yaml` 或环境变量，内置常用 provider，按注册表查找。

## 决定

1. 新增第 05 章“接入真实模型”，放在 Agent Loop 之前；原第 05–13 章顺延为第 06–14 章，第二阶段为第 15–20 章。
2. **配置目录**：默认 `~/.liharness`，可用 `--liharness.home` 或环境变量 `LIHARNESS_HOME` 改（`app.config.HarnessHome`）。`settings.yaml`、`.credentials.yaml`、`sessions/`、`skills/`、`mcp.json` 都放在这里；应用数据目录只放运行记录和日志。
3. **YAML**：SnakeYAML（`SafeConstructor`）解析成 Map，再用 Jackson（不认识的字段报错）转成 record。`mcp.json` 例外，沿用 MCP 生态通用的 JSON 格式。
4. **模型注册表**（领域服务 `LlmProviderRegistry`）：内置 provider 目录（mock、deepseek、openai、kimi、glm、qwen、ollama，模型列表为空 = 不限定）+ 用户在 `settings.yaml` 里按字段覆盖；模型写成 `provider/model`；适配器按**协议**注册（`ILlmAdapterFactory`：`openai-completions`、`mock`）；每个回合重新读配置（按修改时间缓存）。什么都没配时默认 `mock/mock`。
5. **Key**：先找 `.credentials.yaml`（权限 600，别人能读时警告），再找 provider 配置的 `apiKeyEnv` 环境变量。`settings.yaml` 里不放 Key。没有任何接口返回 Key；设置面板（第 08 章）只显示 Key 的来源。
6. 设置面板读写的就是这两个文件：只替换改动的顶层段落以保留注释，写入是原子的。

## 备选方案

- **继续用钥匙串**：更安全，但代价见上；作为 `ICredentialRepository` 的另一个实现留到以后（第 05 章练习 2），其他代码不用改。
- **配置写进 `application.yml`**：那是打包进程序的默认值，用户改不了。
- **JSON 配置**：不能写注释，手写容易漏逗号。

## 后果

好的：

- 第 05 章起就能用真实模型验证每一章；
- 加一家 OpenAI 兼容的服务只改配置；第二阶段的多模型管理建立在同一个注册表上；
- 配置可以备份、可以放进 dotfiles（`settings.yaml` 不含 Key）。

坏的：

- Key 是明文文件，安全性依赖文件权限；
- YAML 的规则多（缩进、Tab），所以错误信息必须指出文件和位置；
- 教程的章节号整体顺延，已有的笔记和 tag 名要跟着改。
