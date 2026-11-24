//! # 后端进程管家
//!
//! 这个文件负责 Java 后端进程的“一生”：
//!
//! 1. **回收**：上次应用崩溃留下的 Java 进程，先把它结束掉；
//! 2. **分配端口**：找一个当前没人用的本机端口；
//! 3. **生成令牌**：每次启动一个新的随机字符串，前端访问后端时要带上它；
//! 4. **启动**：`java -jar liharness-app.jar ...`，日志写进文件；
//! 5. **等待就绪**：反复请求 `/api/health`，直到返回 200；
//! 6. **写运行记录**：把 PID / 端口 / jar 路径写进 `runtime/backend.json`（不写令牌）；
//! 7. **退出**：应用关闭时请求 `/api/admin/shutdown`，等不及就强制结束。
//!
//! ## 读 Rust 代码的几个小提示
//!
//! - `use a::b::C;` 相当于 Java 的 `import a.b.C;`
//! - `fn foo(x: u16) -> String` 是函数：参数 x 的类型是 u16（0~65535 的无符号整数），返回 String
//! - `struct` 只定义字段；方法写在 `impl 类型名 { ... }` 里，可以分开写好几块 `impl`
//! - `&self` 相当于 Java 方法里的 `this`（只读借用）
//! - `Result<T, E>`：要么成功得到 T，要么失败得到 E。`?` 的意思是“如果失败，就直接把错误 return 出去”
//! - `Option<T>`：要么 `Some(值)`，要么 `None`（没有值），相当于 Java 的 `Optional`
//! - `let x = ...;` 定义变量，默认不可修改；要修改得写 `let mut x`
//! - 函数最后一行**没有分号**的表达式，就是返回值

// ───────────────────────── 引入（import） ─────────────────────────

use std::fs::{self, File, OpenOptions}; // fs：文件操作；File：文件；OpenOptions：用指定方式打开文件（比如追加）
use std::io::Write; // 让 File 有 write_all 方法（Rust 里方法来自 trait，要先 use 才能调用）
use std::net::TcpListener; // TCP 监听，用来找空闲端口
use std::path::{Path, PathBuf}; // Path 是路径的“只读视图”（类似 &str），PathBuf 是自己拥有的路径（类似 String）
use std::process::{Child, Command, Stdio}; // Command：构造要执行的命令；Child：启动后的子进程；Stdio：子进程的输入输出接到哪
use std::sync::Mutex; // 互斥锁：多个线程访问同一份数据时用
use std::thread; // 线程（这里主要用 thread::sleep 睡一会儿）
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH}; // 时间相关

use serde::{Deserialize, Serialize}; // serde：Rust 最常用的序列化库，类似 Java 的 Jackson

// ───────────────────────── 常量 ─────────────────────────
// const 是编译期常量，名字习惯全大写。Duration 表示一段时长。

/// 等待后端就绪最多多久。Spring Boot 冷启动一般几秒，留足余量。
const READY_TIMEOUT: Duration = Duration::from_secs(60);
/// 请求后端优雅关闭后，最多等多久再强制结束。
const SHUTDOWN_TIMEOUT: Duration = Duration::from_secs(5);
/// 启动失败（比如端口刚好被别人抢走）时最多重试几次。
const MAX_START_ATTEMPTS: u32 = 3;

// ───────────────────────── 数据类型 ─────────────────────────

/// 运行记录：写在 `<应用数据目录>/runtime/backend.json`。
///
/// 作用：如果这次应用崩溃了，下次启动时读这个文件，就知道上次留下的 Java 进程是哪个。
/// **注意：故意不包含令牌**，令牌绝不能写进磁盘。
///
/// `#[derive(...)]` 让编译器自动生成代码：
/// - `Debug`：可以用 `{:?}` 打印（类似 Java 的 toString）
/// - `Serialize` / `Deserialize`：可以转成 JSON / 从 JSON 读出来
///
/// `#[serde(rename_all = "camelCase")]`：JSON 里的字段名用驼峰，
/// 比如 Rust 的 `jar_path` 在 JSON 里写作 `jarPath`。
#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct RuntimeRecord {
    pid: u32,         // Java 进程号
    port: u16,        // 端口
    jar_path: String, // jar 的绝对路径：回收时用来确认“这个 PID 真的是我们的 Java 进程”
    started_at: u64,  // 启动时间（秒级时间戳），方便排查问题
}

/// 交给前端的连接信息：前端拿到它，就能自己去访问后端。
///
/// `pub` 表示公开（默认是私有，只有本文件能用）。
/// `Clone`：可以复制一份（`.clone()`），因为同一份信息既要存在状态里，又要发给前端。
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct BackendInfo {
    pub base_url: String, // 形如 http://127.0.0.1:54321
    pub token: String,    // 本次启动的令牌（只在内存里）
    pub pid: u32,
}

/// 后端状态。Rust 的 enum 比 Java 的强：每个分支可以带不同的数据。
///
/// `#[serde(tag = "state")]` 让它序列化成带 `state` 字段的 JSON：
/// - `Starting`                → `{"state":"starting"}`
/// - `Ready { info }`          → `{"state":"ready","info":{...}}`
/// - `Failed { error }`        → `{"state":"failed","error":"..."}`
/// - `Stopped`                 → `{"state":"stopped"}`
///
/// 前端的 TypeScript 类型和它一一对应（见 App.tsx）。
#[derive(Debug, Clone, Serialize)]
#[serde(tag = "state", rename_all = "camelCase")]
pub enum BackendStatus {
    Starting,
    Ready { info: BackendInfo },
    Failed { error: String },
    Stopped,
}

/// 怎么启动后端：用哪个 java 程序、哪个 jar 文件。
pub struct LaunchSpec {
    pub java: PathBuf,
    pub jar: PathBuf,
}

impl LaunchSpec {
    /// 开发环境的启动方式：系统里的 `java` + 后端模块 target 目录下的 jar。
    ///
    /// 可以用环境变量覆盖：
    /// - `LIHARNESS_JAVA`：java 的完整路径（比如电脑上装了多个 JDK）
    /// - `LIHARNESS_JAR`：jar 的完整路径
    ///
    /// `Self` 就是 `LaunchSpec` 本身（在 impl 块里的简写）。
    pub fn dev() -> Self {
        // env::var_os 读环境变量，返回 Option：设置了就是 Some，没设置就是 None。
        // .map(PathBuf::from)：如果有值，把它转成 PathBuf。
        // .unwrap_or_else(|| ...)：如果是 None，就用 || 后面这个“匿名函数”算一个默认值。
        //   `|| 表达式` 是 Rust 的闭包（lambda），相当于 Java 的 `() -> 表达式`。
        let java = std::env::var_os("LIHARNESS_JAVA")
            .map(PathBuf::from)
            .unwrap_or_else(|| PathBuf::from("java"));

        let jar = std::env::var_os("LIHARNESS_JAR")
            .map(PathBuf::from)
            .unwrap_or_else(|| {
                // env!("CARGO_MANIFEST_DIR") 在**编译时**被替换成 src-tauri 目录的绝对路径。
                // 从 src-tauri 往上两级就是仓库根目录，再进入后端模块的 target 目录。
                // jar 名字来自后端 pom.xml 的 <finalName>${project.artifactId}</finalName>。
                PathBuf::from(env!("CARGO_MANIFEST_DIR"))
                    .join("../../backend/liharness-app/target/li-harness-app.jar")
            });

        // 字段名和变量名相同时可以简写：Self { java: java, jar: jar } → Self { java, jar }
        Self { java, jar }
    }
}

// ───────────────────────── 管家本体 ─────────────────────────

/// 后端管家。整个应用只有一个，交给 Tauri 保管（见 lib.rs 的 `app.manage`）。
///
/// 为什么字段要包一层 `Mutex`？
/// 后台启动线程会**写** child 和 status，前端调用 command 时会**读** status，
/// 两件事可能同时发生。Rust 的 Mutex 把数据“锁在里面”：不先 `.lock()` 就根本拿不到数据，
/// 所以不可能“忘记加锁”。
pub struct Backend {
    data_dir: PathBuf,            // 应用数据目录（日志、运行记录都在这下面）
    child: Mutex<Option<Child>>,  // 我们启动的 Java 子进程；还没启动时是 None
    status: Mutex<BackendStatus>, // 当前状态
}

impl Backend {
    /// 构造函数。Rust 没有 `new` 关键字，习惯上写一个叫 new 的关联函数。
    pub fn new(data_dir: PathBuf) -> Self {
        Self {
            data_dir,
            child: Mutex::new(None),
            status: Mutex::new(BackendStatus::Starting),
        }
    }

    /// 读取当前状态。
    /// `.lock()` 加锁，返回 Result（锁可能“中毒”：持锁线程 panic 了），`.unwrap()` 表示“出错就直接崩”，
    /// 这里不会出错，所以简单处理。`.clone()` 复制一份返回，锁在这一行结束时自动释放。
    pub fn status(&self) -> BackendStatus {
        self.status.lock().unwrap().clone()
    }

    /// 运行记录文件的位置：`<数据目录>/runtime/backend.json`
    fn record_path(&self) -> PathBuf {
        self.data_dir.join("runtime").join("backend.json")
    }
}

// ───────────────────────── 启动 ─────────────────────────

impl Backend {
    /// 启动后端，返回最终状态（同时保存在 self.status 里）。
    ///
    /// 这个函数会阻塞最多几十秒，所以 lib.rs 里是在后台线程调用它的。
    pub fn start(&self, spec: &LaunchSpec) -> BackendStatus {
        // match 相当于加强版 switch：把 Result 的两种情况分别转成状态。
        let status = match self.try_start(spec) {
            Ok(info) => BackendStatus::Ready { info },
            Err(error) => BackendStatus::Failed { error },
        };
        // `*xxx = 值`：通过锁拿到里面的数据并整体替换。
        *self.status.lock().unwrap() = status.clone();
        status
    }

    /// 真正干活的启动流程。任何一步失败都返回 `Err(中文原因)`，最终会显示在窗口里。
    fn try_start(&self, spec: &LaunchSpec) -> Result<BackendInfo, String> {
        // 1. jar 必须存在。canonicalize() 把 "../../xxx" 这样的相对路径变成绝对路径，
        //    文件不存在时会返回错误。
        //    .map_err(...)：把系统给的错误类型转成我们自己的中文 String。
        //    最后的 ? ：如果是 Err，整个函数立即返回这个错误。
        let jar = spec.jar.canonicalize().map_err(|e| {
            format!(
                "找不到后端 jar：{}（{e}）。请先在仓库根目录执行 ./mvnw package -DskipTests",
                spec.jar.display()
            )
        })?;

        // 2. 结束上次遗留的 Java 进程（如果有）
        self.reap_stale();

        // 3. 确保日志目录存在（create_dir_all 相当于 mkdir -p，已存在不会报错）
        fs::create_dir_all(self.data_dir.join("logs"))
            .map_err(|e| format!("创建日志目录失败：{e}"))?;

        // 4. 生成本次启动的令牌
        let token = new_token();
        let mut last_err = String::new();

        // 5. 最多尝试 MAX_START_ATTEMPTS 次。`1..=3` 表示 1、2、3（包含 3）。
        for attempt in 1..=MAX_START_ATTEMPTS {
            // 每次重试都换一个新端口：失败的常见原因就是端口在“选好之后、Java 绑定之前”被别人占了
            let port = pick_free_port().map_err(|e| format!("分配端口失败：{e}"))?;

            match self.spawn_and_wait(spec, &jar, port, &token) {
                Ok((child, info)) => {
                    // 启动成功：写运行记录（不含令牌）
                    let record = RuntimeRecord {
                        pid: child.id(),
                        port,
                        jar_path: jar.display().to_string(),
                        started_at: now_secs(),
                    };
                    write_record_atomic(&self.record_path(), &record)
                        .map_err(|e| format!("写入运行记录失败：{e}"))?;

                    // 把子进程句柄存起来，退出时要用它结束进程
                    *self.child.lock().unwrap() = Some(child);
                    return Ok(info);
                }
                // 失败：记下原因，进入下一次循环
                Err(e) => last_err = format!("第 {attempt} 次启动失败：{e}"),
            }
        }
        Err(last_err)
    }

    /// 启动 Java 子进程，并等待它的健康检查通过。
    ///
    /// 返回值是一个“元组” `(Child, BackendInfo)`：一次返回两个值。
    fn spawn_and_wait(
        &self,
        spec: &LaunchSpec,
        jar: &Path,
        port: u16,
        token: &str,
    ) -> Result<(Child, BackendInfo), String> {
        // ── 日志重定向 ──
        // Java 的 stdout 和 stderr 都追加写到 logs/backend.log。
        // 不重定向的话，打包后的应用没有控制台，日志就丢了；出问题时也没处可查。
        let log_path = self.data_dir.join("logs").join("backend.log");
        let log = OpenOptions::new()
            .create(true) // 文件不存在就创建
            .append(true) // 追加写，不覆盖上次的日志
            .open(&log_path)
            .map_err(|e| format!("打开日志文件失败：{e}"))?;
        // stdout 和 stderr 各需要一个文件句柄，try_clone 复制一个指向同一文件的句柄
        let log_err = log.try_clone().map_err(|e| e.to_string())?;

        // ── 组装命令：java -jar xxx.jar --参数... ──
        // 这些参数和环境变量，正是第 01 章里约定好的 Java 侧启动方式。
        let mut cmd = Command::new(&spec.java);
        cmd.arg("-jar")
            .arg(jar)
            .arg("--spring.profiles.active=standalone") // 托管模式
            .arg("--server.address=127.0.0.1") // 只监听本机，局域网其他电脑访问不到
            .arg(format!("--server.port={port}")) // format! 类似 String.format，{port} 会被替换成变量值
            .arg(format!("--liharness.data-dir={}", self.data_dir.display()))
            // 令牌走环境变量而不是命令行参数：命令行参数用 `ps` 就能看到，环境变量看不到
            .env("LIHARNESS_TOKEN", token)
            // 把我们自己（Tauri 进程）的 PID 告诉 Java，Java 的看门狗发现我们没了就自行退出
            .env("LIHARNESS_PARENT_PID", std::process::id().to_string())
            .stdin(Stdio::null()) // 后端不需要从键盘读输入
            .stdout(Stdio::from(log))
            .stderr(Stdio::from(log_err));

        // #[cfg(windows)]：这段代码只在 Windows 上编译，macOS 上会被完全忽略。
        // 作用：启动 java.exe 时不要弹出一个黑色的控制台窗口。
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            const CREATE_NO_WINDOW: u32 = 0x0800_0000;
            cmd.creation_flags(CREATE_NO_WINDOW);
        }

        // ── 启动 ──
        // spawn() 启动后立即返回，不等进程结束。启动失败最常见的原因：找不到 java 命令。
        let mut child = cmd.spawn().map_err(|e| {
            format!(
                "无法启动 {}：{e}（是否安装了 JDK 17？）",
                spec.java.display()
            )
        })?;

        // ── 等待就绪 ──
        let base_url = format!("http://127.0.0.1:{port}");
        let deadline = Instant::now() + READY_TIMEOUT; // Instant 是单调时钟，适合算超时

        while Instant::now() < deadline {
            // try_wait()：不阻塞地问一句“你退出了吗？”
            // 如果 Java 已经退出（端口被抢、配置错误、令牌没传……），没必要傻等 60 秒。
            // `if let 模式 = 值`：值符合这个模式时才执行，并顺便把里面的东西取出来（这里取出退出码 exit）。
            if let Ok(Some(exit)) = child.try_wait() {
                return Err(format!(
                    "后端进程提前退出（{exit}），详见日志 {}",
                    log_path.display()
                ));
            }
            if health_ok(&base_url, token) {
                let info = BackendInfo {
                    base_url,
                    token: token.to_string(), // &str → String：复制一份自己拥有的字符串
                    pid: child.id(),
                };
                return Ok((child, info));
            }
            thread::sleep(Duration::from_millis(300)); // 每 300 毫秒问一次
        }

        // 超时了：把这个没用的进程结束掉，再报错。
        // `let _ = 表达式;` 表示“故意忽略返回值”（kill 失败也无所谓）。
        let _ = child.kill();
        let _ = child.wait(); // 等它真正退出，回收进程资源，避免留下“僵尸进程”
        Err(format!(
            "等待后端就绪超时（{} 秒）",
            READY_TIMEOUT.as_secs()
        ))
    }
}

// ───────────────────────── 退出 ─────────────────────────

impl Backend {
    /// 应用退出时调用：先请求优雅关闭，超时再强制结束，最后删除运行记录。
    /// 重复调用是安全的：第二次调用时 child 已经是 None，直接返回。
    pub fn stop(&self) {
        // std::mem::replace(位置, 新值)：把新值放进去，同时把旧值拿出来。
        // 这里把状态换成 Stopped，同时拿到旧状态，用来取出令牌去调关闭接口。
        let previous = std::mem::replace(&mut *self.status.lock().unwrap(), BackendStatus::Stopped);

        // .take()：把 Option 里的子进程取出来，原地留下 None。
        // `let Some(x) = ... else { return; };`：取得到就继续，取不到（None）就直接 return。
        let Some(mut child) = self.child.lock().unwrap().take() else {
            return; // 从来没启动成功过，没什么要停的
        };

        // 如果之前是就绪状态，先礼貌地请求后端自己关闭（Spring 会等进行中的请求完成）
        if let BackendStatus::Ready { info } = previous {
            request_shutdown(&info.base_url, &info.token);
        }

        // 最多等 SHUTDOWN_TIMEOUT，每 100 毫秒检查一次是否已经退出
        let deadline = Instant::now() + SHUTDOWN_TIMEOUT;
        loop {
            match child.try_wait() {
                Ok(Some(_)) => break, // 已经退出了，结束循环
                // `if 条件` 写在分支后面叫“守卫”：还没退出、且没超时 → 睡一会儿继续等
                Ok(None) if Instant::now() < deadline => thread::sleep(Duration::from_millis(100)),
                // 其他情况（超时，或查询出错）：强制结束。
                // 只结束我们自己启动、手里有句柄的这个进程，不会误伤别的 Java 程序。
                _ => {
                    let _ = child.kill();
                    let _ = child.wait();
                    break;
                }
            }
        }

        // 正常退出后删除运行记录：下次启动看到“没有记录”，就知道上次是正常退出的
        let _ = fs::remove_file(self.record_path());
    }
}

// ───────────────────────── 回收遗留进程 ─────────────────────────

impl Backend {
    /// 读取上次的运行记录：如果那个 PID 还活着，并且确认它运行的是我们的 jar，就结束它。
    ///
    /// 为什么要“确认是我们的 jar”？PID 会被操作系统复用。
    /// 上次的 Java 进程早就没了，同一个 PID 现在可能是你的浏览器——不检查就会误杀。
    fn reap_stale(&self) {
        let path = self.record_path();

        // 读不到文件 → 上次是正常退出（或第一次运行），什么都不用做
        let Ok(text) = fs::read_to_string(&path) else {
            return;
        };

        // serde_json::from_str::<类型>(文本)：把 JSON 解析成指定类型（类似 Jackson 的 readValue）
        if let Ok(record) = serde_json::from_str::<RuntimeRecord>(&text) {
            // 查这个 PID 的完整命令行；进程不存在时返回 None
            if let Some(cmdline) = process_command_line(record.pid) {
                // 命令行里包含我们的 jar 路径，才是我们的进程
                if cmdline.contains(&record.jar_path) {
                    kill_pid(record.pid);
                }
            }
        }

        // 不管处理结果如何，旧记录都删掉
        let _ = fs::remove_file(&path);
    }
}

// ───────────────────────── 工具函数 ─────────────────────────
// 不带 self 的普通函数，不属于任何类型。没有 pub，只在本文件内使用。

/// 找一个空闲端口：绑定 127.0.0.1 的 0 号端口，操作系统会自动分配一个没人用的端口。
/// 拿到端口号后，listener 在函数结束时被自动释放（Rust 离开作用域自动清理，不需要 close）。
///
/// 注意：从“释放”到“Java 绑定”之间有极短的空档，端口可能被别人抢走，
/// 所以 try_start 里有重试。
fn pick_free_port() -> std::io::Result<u16> {
    let listener = TcpListener::bind("127.0.0.1:0")?;
    Ok(listener.local_addr()?.port())
}

/// 生成令牌：两个随机 UUID 拼起来（去掉横线），64 个十六进制字符。
/// v4 UUID 用的是操作系统提供的安全随机数，别人猜不到。
fn new_token() -> String {
    format!(
        "{}{}",
        uuid::Uuid::new_v4().simple(),
        uuid::Uuid::new_v4().simple()
    )
}

/// 当前时间的秒级时间戳
fn now_secs() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0) // 系统时间早于 1970 年（几乎不可能）时用 0
}

/// 原子写入：先写临时文件并确保落盘，再用 rename 一步替换正式文件。
///
/// 为什么不直接写？如果写到一半断电或崩溃，文件只写了一半，下次读出来是坏的 JSON。
/// rename 在同一个磁盘上是原子操作：要么是旧文件，要么是完整的新文件，不会出现半个文件。
fn write_record_atomic(path: &Path, record: &RuntimeRecord) -> std::io::Result<()> {
    // path.parent()：上一级目录（Option，根目录没有上一级）
    if let Some(dir) = path.parent() {
        fs::create_dir_all(dir)?;
    }
    let tmp = path.with_extension("json.tmp"); // backend.json → backend.json.tmp
    let json = serde_json::to_vec_pretty(record).map_err(std::io::Error::other)?;

    // 用一对花括号包起来：块结束时 file 被自动关闭，然后才 rename
    {
        let mut file = File::create(&tmp)?;
        file.write_all(&json)?;
        file.sync_all()?; // 强制写到磁盘，而不是停留在操作系统的缓存里
    }
    fs::rename(&tmp, path)
}

/// 创建一个访问本机后端用的 HTTP 客户端
fn http_client() -> reqwest::blocking::Client {
    reqwest::blocking::Client::builder()
        .timeout(Duration::from_secs(2)) // 本机请求 2 秒足够
        // 很多公司电脑设置了 HTTP_PROXY 环境变量，HTTP 客户端会把请求发给代理，
        // 连 127.0.0.1 也不例外，结果健康检查永远失败。访问本机必须绕过代理。
        .no_proxy()
        .build()
        .expect("创建 HTTP 客户端失败") // expect：出错就崩溃并打印这句话（这里不会出错）
}

/// 健康检查：带着令牌请求 /api/health，返回 200 就算就绪。
/// 任何错误（连不上、超时、401）都当作“还没好”，返回 false。
fn health_ok(base_url: &str, token: &str) -> bool {
    http_client()
        .get(format!("{base_url}/api/health"))
        .bearer_auth(token) // 加请求头 Authorization: Bearer <令牌>
        .send()
        .map(|r| r.status().is_success()) // 成功拿到响应：看状态码是不是 2xx
        .unwrap_or(false) // 请求本身失败：false
}

/// 请求后端优雅关闭。不关心结果：失败了 stop() 里还有强制结束兜底。
fn request_shutdown(base_url: &str, token: &str) {
    let _ = http_client()
        .post(format!("{base_url}/api/admin/shutdown"))
        .bearer_auth(token)
        .send();
}

// ───────────────────────── 跨平台：查进程命令行、结束进程 ─────────────────────────
// #[cfg(unix)] 的版本在 macOS / Linux 上编译，#[cfg(windows)] 的版本在 Windows 上编译。
// 两个版本函数名一样，调用方不用关心当前是什么系统。

/// 查询某个 PID 的完整命令行（macOS / Linux）：执行 `ps -p <pid> -o command=`
#[cfg(unix)]
fn process_command_line(pid: u32) -> Option<String> {
    let out = Command::new("ps")
        .arg("-p")
        .arg(pid.to_string())
        .arg("-o")
        .arg("command=") // 只输出命令行，不要表头
        .output() // 执行并等待结束，拿到输出
        .ok()?; // Result → Option；失败就返回 None
    if !out.status.success() {
        return None; // ps 返回非 0：进程不存在
    }
    // 输出是字节，转成字符串（遇到非法 UTF-8 用替换字符，不报错），去掉首尾空白
    let text = String::from_utf8_lossy(&out.stdout).trim().to_string();
    if text.is_empty() { None } else { Some(text) }
}

/// 结束进程（macOS / Linux）：先温柔地 TERM，等 5 秒还不退出再 KILL
#[cfg(unix)]
fn kill_pid(pid: u32) {
    // SIGTERM：相当于“请你退出”，Java 会执行关闭钩子，Spring 优雅关闭
    let _ = Command::new("kill")
        .arg("-TERM")
        .arg(pid.to_string())
        .status();
    // 每 100 毫秒检查一次，最多 50 次 = 5 秒。`0..50` 是 0 到 49（不含 50）
    for _ in 0..50 {
        if process_command_line(pid).is_none() {
            return; // 已经退出
        }
        thread::sleep(Duration::from_millis(100));
    }
    // SIGKILL：强制结束，进程没有机会做任何清理
    let _ = Command::new("kill")
        .arg("-KILL")
        .arg(pid.to_string())
        .status();
}

/// 查询某个 PID 的完整命令行（Windows）：用 PowerShell 查询 Win32_Process
#[cfg(windows)]
fn process_command_line(pid: u32) -> Option<String> {
    use std::os::windows::process::CommandExt;
    let script = format!("(Get-CimInstance Win32_Process -Filter 'ProcessId={pid}').CommandLine");
    let out = Command::new("powershell")
        .args(["-NoProfile", "-NonInteractive", "-Command"])
        .arg(script)
        .creation_flags(0x0800_0000) // 不弹黑窗口
        .output()
        .ok()?;
    let text = String::from_utf8_lossy(&out.stdout).trim().to_string();
    if text.is_empty() { None } else { Some(text) }
}

/// 结束进程（Windows）：taskkill /T 连同子进程一起结束，/F 强制
#[cfg(windows)]
fn kill_pid(pid: u32) {
    use std::os::windows::process::CommandExt;
    let _ = Command::new("taskkill")
        .args(["/PID", &pid.to_string(), "/T", "/F"])
        .creation_flags(0x0800_0000)
        .status();
}

// ───────────────────────── 单元测试 ─────────────────────────
// #[cfg(test)]：只在 `cargo test` 时编译。在 desktop/src-tauri 目录执行 `cargo test` 即可运行。

#[cfg(test)]
mod tests {
    use super::*; // 引入上面这个文件里的所有东西

    #[test]
    fn picks_a_usable_port() {
        let port = pick_free_port().unwrap();
        assert!(port > 0);
        // 刚选出来的端口应该能再次绑定（说明确实空闲）
        assert!(TcpListener::bind(("127.0.0.1", port)).is_ok());
    }

    #[test]
    fn token_is_long_and_random() {
        let a = new_token();
        let b = new_token();
        assert_eq!(a.len(), 64);
        assert_ne!(a, b); // 两次生成的令牌不一样
    }

    #[test]
    fn record_is_written_atomically_without_token() {
        // 在系统临时目录下建一个测试专用目录
        let dir = std::env::temp_dir().join(format!("liharness-test-{}", std::process::id()));
        let path = dir.join("runtime").join("backend.json");
        let record = RuntimeRecord {
            pid: 123,
            port: 4567,
            jar_path: "/tmp/app.jar".into(),
            started_at: 1,
        };
        write_record_atomic(&path, &record).unwrap();

        let text = fs::read_to_string(&path).unwrap();
        assert!(text.contains("\"jarPath\"")); // 字段名是驼峰
        assert!(!text.to_lowercase().contains("token")); // 绝对不能有令牌
        assert!(!path.with_extension("json.tmp").exists()); // 临时文件已被 rename 掉

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn status_serializes_with_state_tag() {
        let s = BackendStatus::Failed { error: "x".into() };
        let json = serde_json::to_string(&s).unwrap();
        assert_eq!(json, r#"{"state":"failed","error":"x"}"#);
    }
}
