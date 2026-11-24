# 插件架构：万物皆插件

> 本文是 LiHarness 后端的核心设计。第 04 章（插件微内核）与第 14 章（外部插件与热插拔）会把它一步步实现出来。

## 1. 目标

| 目标 | 含义 |
|---|---|
| **一切能力都是插件** | 模型适配器、工具、会话存储、护栏策略、审批通道、上下文压缩、MCP、Skills、子代理，**甚至 Agent 循环本身** |
| **内核极小** | 内核只做四件事：加载插件、解析依赖、管理生命周期、分发事件。内核里没有任何“智能体”逻辑 |
| **注册即可撤销** | 插件注册的服务、工具、事件监听、后台任务，卸载时**自动**全部撤销，不留残余 |
| **依赖自动编排** | 插件声明需要哪些服务；服务就绪才加载，服务消失则自动卸载，服务恢复再自动加载 |
| **框架无关** | 插件契约是纯 Java，不依赖 Spring；插件可以独立编译、独立分发 |

## 2. 四个核心概念

| 概念 | 是什么 | 例子 |
|---|---|---|
| **插件 Plugin** | 能力的载体，实现 `Plugin` 接口 | `llm-openai`、`tools-builtin`、`agent-loop` |
| **服务 Service** | 插件对外提供的、以**接口类型**为键的能力 | `ChatModel`、`ToolRegistry`、`SessionStore`、`ApprovalGate` |
| **扩展 Extension** | 注册进某个服务里的条目 | 往 `ToolRegistry` 里注册的一个 `Tool`；往 `GuardChain` 里注册的一条策略 |
| **事件 Event** | 插件之间松耦合通信的消息 | `TurnStarted`、`ToolCalled`、`ApprovalRequested` |

一个插件可以同时**提供服务**、**消费服务**、**注册扩展**、**订阅事件**。

## 3. 插件契约（`liharness-plugin-api`）

```java
/** 插件：一切能力的载体。 */
public interface Plugin {

    /** 全局唯一 id，如 "llm-openai" */
    String id();

    /** 必需依赖：这些服务全部就绪后，内核才会调用 apply */
    default List<Class<?>> inject() {
        return List.of();
    }

    /** 插件入口：通过 ctx 注册能力。抛出异常 → 插件进入 FAILED 状态 */
    void apply(PluginContext ctx) throws Exception;
}
```

```java
/** 内核传给每个插件的上下文。插件经由它注册的一切，都记在该插件名下。 */
public interface PluginContext {

    <T> T get(Class<T> service);                    // 必需服务（必须在 inject() 中声明）
    <T> Optional<T> find(Class<T> service);         // 可选服务

    <T> Registration provide(Class<T> service, T impl);              // 提供服务
    <E> Registration on(Class<E> eventType, Consumer<? super E> h);  // 订阅事件
    void emit(Object event);                                          // 发布事件

    void onDispose(AutoCloseable resource);         // 托管需要手动释放的资源（线程池、子进程、连接）
    <C> C config(Class<C> type);                    // 读取并校验本插件的配置
    Path dataDir();                                 // 插件私有数据目录
}
```

```java
/** 撤销句柄：注册了什么，close() 就撤销什么。 */
@FunctionalInterface
public interface Registration extends AutoCloseable {
    @Override
    void close();   // 不抛受检异常，方便批量回收
}
```

### 一个最小的工具插件

```java
public final class TimeToolPlugin implements Plugin {

    @Override
    public String id() {
        return "tool-time";
    }

    @Override
    public List<Class<?>> inject() {
        return List.of(ToolRegistry.class);
    }

    @Override
    public void apply(PluginContext ctx) {
        ctx.get(ToolRegistry.class).register(new GetTimeTool());
        // 不需要手动注销：插件卸载时，内核会撤销它注册过的一切
    }
}
```

### “自动撤销”是怎么做到的：作用域视图

`ctx.get(ToolRegistry.class)` 拿到的**不是**全局的那个 `ToolRegistry`，而是内核为当前插件生成的**作用域视图**：它把每一次 `register(...)` 返回的 `Registration` 记录在当前插件名下，插件卸载时逐个 `close()`。

服务提供者只要实现一个小接口，内核就知道怎么为它生成作用域视图：

```java
/** 服务可以选择实现它：为每个使用者生成一个“带作用域”的视图 */
public interface Scoped<T> {
    T scopedFor(PluginScope scope);
}
```

没实现 `Scoped` 的服务，`ctx.get` 直接返回原对象，使用者自己负责通过 `onDispose` 清理。

## 4. 生命周期

![插件生命周期](images/plugin-lifecycle.svg)

| 规则 | 说明 |
|---|---|
| 依赖就绪才加载 | 内核维护“服务 → 依赖它的插件”的反向索引；服务一出现，就检查等待它的插件 |
| 依赖消失则级联回退 | 提供 `ChatModel` 的插件被禁用 → 所有 `inject` 了 `ChatModel` 的插件撤销注册、回到 PENDING；服务重新出现时自动重新加载 |
| 撤销顺序 | 按注册的**逆序**撤销（后注册的先撤销），与 Java 的 try-with-resources 一致 |
| 失败隔离 | 一个插件 `apply` 失败只影响它自己和依赖它的插件，不影响内核与其他插件 |
| 配置变更 | 插件配置变化 → 卸载并以新配置重新加载（热更新） |

## 5. 内置服务目录

| 服务接口 | 默认提供者插件 | 作用 |
|---|---|---|
| `ChatModel` | `llm-openai` / `llm-mock` | 调用大模型，流式返回 |
| `ToolRegistry` | `tools-core` | 工具注册表，输出发给模型的工具清单 |
| `GuardChain` | `guard-core` | 护栏策略链：输入 / 工具 / 输出三个阶段 |
| `ApprovalGate` | `approval-core` | 人工审批：发起请求、等待决定、超时 |
| `SessionStore` | `session-h2` | 会话与追加式事件日志 |
| `ContextAssembler` | `context-core` | 组装上下文：system、工具清单、技能、历史 |
| `Compactor` | `compaction-core` | 历史压缩 |
| `AgentRunner` | `agent-loop` | **Agent 循环本身**，可整体替换（如换成“先规划再执行”的循环） |
| `SkillCatalog` | `skills` | 技能目录（渐进式披露） |
| `DataSource` | 宿主（Spring） | H2 数据源，`session-h2` 使用 |
| `SecretStore` | 宿主（Spring） | 内存中的密钥，由桌面外壳从系统钥匙串推送 |
| 事件 | 内核自带 | `ctx.on(...)` 订阅、`ctx.emit(...)` 发布 |

> 把 Agent 循环也做成服务，意味着你可以写一个 `agent-plan-execute` 插件替换默认循环，或者针对不同会话启用不同的循环，**内核与其他插件都不用改**。

## 6. 内置插件与外部插件

| | 内置插件 | 外部插件 |
|---|---|---|
| 位置 | `backend/plugins/*` 模块，打包进 `liharness-app.jar` | `<应用数据目录>/plugins/*.jar` |
| 发现方式 | `java.util.ServiceLoader`（`META-INF/services`） | 扫描目录 + 读取插件清单 |
| ClassLoader | 应用 ClassLoader | **每个插件一个独立的 ClassLoader**，父加载器只暴露 `plugin-api` |
| 默认状态 | 启用 | **禁用**，需要用户在插件页面显式启用 |
| 热插拔 | 启用 / 禁用 | 安装 / 卸载 / 升级，无需重启 |

### 外部插件清单 `META-INF/liharness-plugin.properties`

```properties
id=acme-jira-tools
name=Jira 工具
version=1.2.0
api-version=1.0
description=查询和更新 Jira 工单
```

- `api-version`：与宿主 `plugin-api` 的版本做兼容性检查（主版本相同、次版本不高于宿主），不兼容直接拒绝加载；
- 插件类本身仍通过 `META-INF/services` 声明，一个 jar 恰好一个插件，`Plugin.id()` 必须与清单一致（第 14 章）。

> 类加载器隔离解决的是依赖冲突和可见性，**不是安全沙箱**：外部插件与应用拥有相同的权限。只安装信任的插件；需要隔离不可信代码时，用独立进程的 MCP 服务器（第 15 章）。

### 为什么每个外部插件一个 ClassLoader

1. **隔离依赖**：插件 A 用 Jackson 2.15，插件 B 用 2.17，互不冲突；
2. **可卸载**：插件卸载后，它的 ClassLoader 和加载的所有类可以被垃圾回收，实现真正的热插拔；
3. **限制可见性**：父加载器只暴露 `plugin-api` 包，插件拿不到内核和 Spring 的内部类。

## 7. 配置分层

插件配置（`ctx.config()`）从低到高三层，高层覆盖低层：

1. 插件代码里的默认值（`getOrDefault`）
2. `application.yml` 中的 `liharness.plugins.<id>.*`
3. 用户在设置页面保存的配置（H2 的 `settings` 表，第 12 章）

`liharness.plugins.<id>.enabled=false` 可以停用任何插件。密钥**不属于配置**：插件通过 `SecretStore` 服务按名字读取。

**配置在加载时校验**，不合法就让插件进入 FAILED 并给出明确原因，而不是带着错误配置运行。

## 8. 与 Spring Boot 的关系

```text
Spring Boot（liharness-app）
 ├── 创建 Kernel Bean，启动时加载全部插件
 ├── REST 控制器 → 通过 kernel 找到 AgentRunner、SessionStore 等服务
 ├── SSE 适配器 → 订阅 EventBus，把事件写给前端
 └── H2 DataSource → 作为 SessionStore 等服务的底层资源，通过内核提供给插件
```

插件看不到 Spring。需要数据库的插件，拿到的是 `plugin-api` 里定义的抽象（如 `SessionStore`），或者内核提供的 `javax.sql.DataSource` 服务，而不是 Spring 的 `JdbcTemplate`。

## 9. 设计取舍

| 取舍 | 选择 | 理由 |
|---|---|---|
| 服务以什么为键 | 接口 `Class` | 编译期类型安全；IDE 能跳转 |
| 同一接口多个提供者 | 按优先级取一个；需要多个的用“扩展”（如工具、策略） | 简单明确 |
| 依赖注入方式 | `inject()` 显式声明 + `ctx.get` | 不用注解扫描和反射魔法，易于理解和调试 |
| 插件分发格式 | 普通 jar + JSON 清单 | Java 开发者零学习成本 |
| 安全模型 | 外部插件默认禁用 + 权限声明 + 来源提示 | SecurityManager 在 JDK 17 已被弃用、后续版本已永久禁用，进程内无法强隔离；对不可信插件，将来可改为在独立子进程中运行 |
