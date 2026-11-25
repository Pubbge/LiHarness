import { invoke } from "@tauri-apps/api/core";
// 注意：fetch 从 Tauri HTTP 插件导入，不是浏览器自带的那个。
// 请求由 Rust 进程发出，不涉及浏览器跨域，后端不需要开放 CORS；
// 能访问哪些地址由 capabilities/default.json 里的白名单决定（只允许 127.0.0.1）。
import { fetch } from "@tauri-apps/plugin-http";
import { readSse } from "./sse";
import type { AgentEvent, BackendInfo, BackendStatus } from "./types";

/** 向 Rust 查询后端的地址和令牌 */
async function backend(): Promise<BackendInfo> {
  const status = await invoke<BackendStatus>("backend_status");
  if (status.state !== "ready") throw new Error("后端尚未就绪");
  return status.info;
}

/**
 * 所有后端请求的唯一入口：自动拼地址、自动带令牌。
 * 令牌只在这里出现一次，以后加多少接口都不会忘记带。
 */
export async function apiFetch(path: string, init: RequestInit = {}): Promise<Response> {
  const { baseUrl, token } = await backend();
  const headers = new Headers(init.headers);
  headers.set("Authorization", `Bearer ${token}`);
  return fetch(baseUrl + path, { ...init, headers });
}

/** 发起一个回合：后端推来的每个事件都会调用一次 onEvent */
export async function streamTurn(
  body: { sessionId: string; input: string },
  onEvent: (e: AgentEvent) => void,
  signal?: AbortSignal,
): Promise<void> {
  const res = await apiFetch("/api/agent/stream", {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "text/event-stream" },
    body: JSON.stringify(body),
    signal,
  });
  if (!res.ok) throw new Error(`请求失败：HTTP ${res.status}`);
  // SSE 的 event 名作为 type，再和 data 里的 JSON 字段合并成一个事件对象
  await readSse(res, (m) => onEvent({ type: m.event, ...JSON.parse(m.data) } as AgentEvent));
}
