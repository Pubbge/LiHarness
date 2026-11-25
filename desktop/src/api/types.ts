/**
 * 前后端共用的数据类型。
 *
 * AgentEvent 与后端推送的 SSE 事件一一对应：type 字段来自 SSE 的 `event:` 名，
 * 其余字段来自 `data:` 里的 JSON。后面每章新增一种事件，就在这里加一个分支。
 */
export type AgentEvent =
  | { type: "turn_started"; turnId: string; sessionId: string }
  | { type: "delta"; text: string }
  | { type: "done"; turnId: string; answer: string; model: string; totalTokens?: number | null }
  | { type: "error"; message: string; cancelled: boolean };

/** 后端连接信息，对应 Rust 的 BackendInfo */
export type BackendInfo = { baseUrl: string; token: string; pid: number };

/** 后端状态，对应 Rust 的 BackendStatus 枚举 */
export type BackendStatus =
  | { state: "starting" }
  | { state: "ready"; info: BackendInfo }
  | { state: "failed"; error: string }
  | { state: "stopped" };
