import { useEffect, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { listen, type UnlistenFn } from "@tauri-apps/api/event";
import type { BackendStatus } from "./api/types";
import { getModels, selectModel, type ModelsOverview } from "./api/models";
import { Chat } from "./Chat";
import { SettingsDialog } from "./components/SettingsDialog";
import "./App.css";

export default function App() {
  const [status, setStatus] = useState<BackendStatus>({ state: "starting" });
  // 模型设置：输入框的模型选择器和设置页共用一份，任何一边改了另一边马上看到
  const [models, setModels] = useState<ModelsOverview>();
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [modelError, setModelError] = useState<string>();
  const ready = status.state === "ready";

  // 后端就绪后读一次；窗口重新获得焦点时再读一次（你可能刚用编辑器改过 settings.yaml）
  useEffect(() => {
    if (!ready) return;
    const load = () => getModels().then(setModels).catch((e) => setModelError(String(e)));
    load();
    window.addEventListener("focus", load);
    return () => window.removeEventListener("focus", load);
  }, [ready]);

  async function choose(model: string) {
    try {
      setModels(await selectModel(model));
      setModelError(undefined);
    } catch (e) {
      setModelError(e instanceof Error ? e.message : String(e));
    }
  }

  useEffect(() => {
    // disposed：组件是否已经卸载。开发模式下 React 的 StrictMode 会故意把 effect 执行两遍
    // （挂载 → 卸载 → 再挂载），用来检查清理是否干净。
    let disposed = false;
    let unlisten: UnlistenFn | undefined;

    (async () => {
      // 1. 先订阅后端发来的状态事件（Rust 里 handle.emit("backend://status", ...)）
      const off = await listen<BackendStatus>("backend://status", (e) => setStatus(e.payload));
      if (disposed) {
        off();
        return;
      }
      unlisten = off;
      // 2. 再主动查一次当前状态。先订阅、再查询，才不会错过“启动完成”的那一刻
      const current = await invoke<BackendStatus>("backend_status");
      if (!disposed) setStatus(current);
    })();

    return () => {
      disposed = true;
      unlisten?.();
    };
  }, []);

  return (
    <main className="shell">
      <header className="topbar">
        <h1>LiHarness</h1>
        <div className="topbar-right">
          <StatusBadge status={status} />
          {ready && (
            <button type="button" className="icon-button" onClick={() => setSettingsOpen(true)}>
              ⚙ 设置
            </button>
          )}
        </div>
      </header>
      {/* 后端就绪后才显示对话界面 */}
      {ready ? (
        <Chat models={models} modelError={modelError} onSelectModel={choose} onOpenSettings={() => setSettingsOpen(true)} />
      ) : (
        <Splash status={status} />
      )}
      {settingsOpen && models && (
        <SettingsDialog overview={models} onChange={setModels} onClose={() => setSettingsOpen(false)} />
      )}
    </main>
  );
}

/** 顶栏右侧的小状态标签 */
function StatusBadge({ status }: { status: BackendStatus }) {
  switch (status.state) {
    case "starting":
      return <span className="badge starting">启动中</span>;
    case "ready":
      return (
        <span className="badge ready" title={`PID ${status.info.pid}`}>
          已连接 · {status.info.baseUrl.replace("http://", "")}
        </span>
      );
    case "failed":
      return <span className="badge failed">启动失败</span>;
    case "stopped":
      return <span className="badge">已停止</span>;
  }
}

/** 后端还没就绪时，占满主区域的提示。注意：界面上从不显示令牌 */
function Splash({ status }: { status: BackendStatus }) {
  switch (status.state) {
    case "starting":
      return <p className="status starting">⏳ 正在启动后端…</p>;
    case "failed":
      return <p className="status failed">❌ 后端启动失败：{status.error}</p>;
    default:
      return <p className="status">后端已停止</p>;
  }
}
