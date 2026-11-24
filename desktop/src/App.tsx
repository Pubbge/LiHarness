import { useEffect, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { listen, type UnlistenFn } from "@tauri-apps/api/event";
import "./App.css";

/** 后端连接信息，对应 Rust 的 BackendInfo（字段名已被 serde 转成驼峰） */
type BackendInfo = { baseUrl: string; token: string; pid: number };

/**
 * 后端状态，和 Rust 的 BackendStatus 枚举一一对应。
 * 这种写法叫“可辨识联合类型”：根据 state 字段的值，TypeScript 能自动推断出其他字段。
 * 比如 state 是 "ready" 时，才能访问 info。
 */
type BackendStatus =
  | { state: "starting" }
  | { state: "ready"; info: BackendInfo }
  | { state: "failed"; error: string }
  | { state: "stopped" };

export default function App() {
  const [status, setStatus] = useState<BackendStatus>({ state: "starting" });

  useEffect(() => {
    // disposed：组件是否已经卸载。开发模式下 React 的 StrictMode 会故意把 effect 执行两遍
    // （挂载 → 卸载 → 再挂载），用来检查清理是否干净。
    let disposed = false;
    let unlisten: UnlistenFn | undefined;

    (async () => {
      // 1. 先订阅后端发来的状态事件（Rust 里 handle.emit("backend://status", ...)）
      const off = await listen<BackendStatus>("backend://status", (e) => setStatus(e.payload));
      // 订阅是异步的，完成时组件可能已经卸载了：那就立刻取消订阅
      if (disposed) {
        off();
        return;
      }
      unlisten = off;

      // 2. 再主动查一次当前状态（调用 Rust 的 backend_status command）
      //    为什么“先订阅、再查询”？如果反过来，后端恰好在“查询之后、订阅之前”启动完成，
      //    那个事件就错过了，界面会一直停在“正在启动”。
      const current = await invoke<BackendStatus>("backend_status");
      if (!disposed) setStatus(current);
    })();

    // 清理函数：组件卸载时执行
    return () => {
      disposed = true;
      unlisten?.();
    };
  }, []);

  return (
    <main className="shell">
      <h1>LiHarness</h1>
      <StatusCard status={status} />
    </main>
  );
}

/** 根据状态显示不同的提示。注意：界面上不显示令牌，令牌只留在内存里，下一章发请求时才用。 */
function StatusCard({ status }: { status: BackendStatus }) {
  switch (status.state) {
    case "starting":
      return <p className="status starting">⏳ 正在启动后端…</p>;
    case "ready":
      return (
        <p className="status ready">
          ✅ 后端已就绪 · {status.info.baseUrl.replace("http://", "")} · PID {status.info.pid}
        </p>
      );
    case "failed":
      return <p className="status failed">❌ 后端启动失败：{status.error}</p>;
    case "stopped":
      return <p className="status">后端已停止</p>;
  }
}
