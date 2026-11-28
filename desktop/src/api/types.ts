/**
 * 前后端共用的数据类型。
 *
 * AgentEvent 与后端推送的 SSE 事件一一对应：type 字段来自 SSE 的 `event:` 名，
 * 其余字段来自 `data:` 里的 JSON。后端对应的代码在 app/agent/SseTurnListener.java。
 */
export type AgentEvent =
  | { type: "turn_started"; turnId: string; sessionId: string; model: string }
  | { type: "delta"; text: string }
  | { type: "tool_call"; callId: string; name: string; arguments: string }
  | { type: "tool_progress"; callId: string; message: string }
  | { type: "tool_result"; callId: string; name: string; ok: boolean; content: string; durationMs: number }
  | {
      type: "done";
      turnId: string;
      /** COMPLETED / MAX_STEPS / MAX_TOKENS */
      outcome: string;
      answer: string;
      /** 非正常完成时的提示，比如“达到最大步数” */
      notice: string;
      steps: number;
      inputTokens: number;
      outputTokens: number;
      totalTokens: number;
    }
  | { type: "error"; message: string; cancelled: boolean; kind?: string };

/** 后端连接信息，对应 Rust 的 BackendInfo */
export type BackendInfo = { baseUrl: string; token: string; pid: number };

/** 后端状态，对应 Rust 的 BackendStatus 枚举 */
export type BackendStatus =
  | { state: "starting" }
  | { state: "ready"; info: BackendInfo }
  | { state: "failed"; error: string }
  | { state: "stopped" };
