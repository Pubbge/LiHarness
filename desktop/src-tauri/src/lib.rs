//! 桌面应用的入口逻辑（main.rs 只是调用这里的 `run()`）。
//!
//! 这里做三件事：
//! 1. 应用启动时：创建“后端管家”，在后台线程里启动 Java 后端，启动完成后通知前端；
//! 2. 提供一个 command `backend_status`，前端随时可以查询后端状态；
//! 3. 应用退出时：停掉 Java 后端。

// 声明子模块：告诉编译器“同目录下有个 backend.rs，把它编译进来”。
// 类似 Java 里一个包下面的另一个类文件，只是 Rust 要显式声明。
mod backend;

// 引入 backend.rs 里公开（pub）的三个类型
use backend::{Backend, BackendStatus, LaunchSpec};
// Emitter：提供 emit（向前端发事件）；Manager：提供 path()、manage()、state()；RunEvent：运行期事件
use tauri::{Emitter, Manager, RunEvent};

/// 前端查询后端状态。
///
/// `#[tauri::command]` 把普通函数变成前端可以调用的接口：
/// 前端写 `invoke("backend_status")` 就会调到这里，返回值自动转成 JSON。
///
/// 参数 `backend: tauri::State<'_, Backend>`：Tauri 自动把之前 `manage` 进去的 Backend 传进来，
/// 相当于 Spring 的依赖注入。`'_` 是生命周期标记，这里照抄即可，表示“借用，由编译器推断多久”。
#[tauri::command]
fn backend_status(backend: tauri::State<'_, Backend>) -> BackendStatus {
    backend.status()
}

/// `#[cfg_attr(mobile, ...)]`：只在编译手机版时生效，桌面版可以忽略。模板自带，保留即可。
#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        // 模板自带的插件：用系统默认程序打开链接 / 文件。保留。
        .plugin(tauri_plugin_opener::init())
        // setup：窗口创建前执行一次，适合做初始化
        // `|app| { ... }` 是闭包（lambda），app 是应用对象
        .setup(|app| {
            // 系统规定的“应用数据目录”，Tauri 根据 tauri.conf.json 的 identifier 算好：
            // macOS：~/Library/Application Support/com.pubbge.liharness/
            // Windows：%APPDATA%\com.pubbge.liharness\
            // 末尾的 ? ：获取失败就让 setup 返回错误，应用启动失败
            let data_dir = app.path().app_data_dir()?;

            // 把 Backend 交给 Tauri 保管（全局单例），之后在任何地方用 state::<Backend>() 取出
            app.manage(Backend::new(data_dir));

            // 启动后端最多要等 60 秒，不能卡住窗口，所以放到新线程里。
            // handle 是应用的“遥控器”，可以复制一份带进线程。
            let handle = app.handle().clone();

            // thread::spawn(move || {...})：开一个新线程执行闭包。
            // move 表示把闭包里用到的变量（handle）的所有权“搬进”线程，
            // 因为线程可能比当前函数活得更久，不能只是借用。
            std::thread::spawn(move || {
                let backend = handle.state::<Backend>();
                let status = backend.start(&LaunchSpec::dev());
                // 启动结束（成功或失败），向前端广播事件 backend://status，事件内容就是状态 JSON。
                // 发送失败（比如窗口已经关了）也无所谓，所以用 let _ 忽略。
                let _ = handle.emit("backend://status", status);
            });

            Ok(()) // setup 成功。() 是“空值”，类似 Java 的 void
        })
        // 注册 command：列在这里的函数前端才能 invoke
        .invoke_handler(tauri::generate_handler![backend_status])
        // 模板写的是 .run(context)，一步到位。我们拆成 build + run 两步，
        // 这样 run 可以接收一个回调，拿到“应用即将退出”之类的运行期事件。
        .build(tauri::generate_context!())
        .expect("构建 Tauri 应用失败")
        .run(|app, event| {
            // 每个运行期事件都会到这里；我们只关心 Exit：应用退出前的最后时刻
            if let RunEvent::Exit = event {
                let backend = app.state::<Backend>();
                // stop() 里用了阻塞式 HTTP 客户端，不适合直接在 Tauri 的事件线程里跑，
                // 所以开一个线程执行它，并且**等它执行完**再让应用退出。
                // thread::scope 保证：scope 结束前，里面开的线程一定都结束了；
                // 因此线程里可以放心借用 backend，不需要 move。
                std::thread::scope(|s| {
                    s.spawn(|| backend.stop());
                });
            }
        });
}
