import { create } from "zustand";
import type { AgentEvent } from "../api/types";

/** 助手回答里的一段文字 */
export type TextPart = { kind: "text"; text: string };

/** 助手回答里的一次工具调用，显示成一张卡片 */
export type ToolPart = {
  kind: "tool";
  callId: string;
  name: string;
  args: string;
  status: "running" | "ok" | "error" | "cancelled";
  /** 最新的进度消息（比如命令输出的最后一行） */
  progress?: string;
  result?: string;
  durationMs?: number;
};

export type Part = TextPart | ToolPart;

/**
 * 一个助手回答。
 *
 * 为什么是 parts 列表而不是一个 text 字符串：一个回合里模型可能“先说一句 → 调工具 → 再说一句 → 再调工具”，
 * 文字和工具调用是交错出现的。按到达顺序存成列表，界面就能按真实顺序显示。
 */
export type AssistantItem = {
  kind: "assistant";
  turnId?: string;
  model?: string;
  parts: Part[];
  status: "streaming" | "done" | "error";
  /** 非正常结束的提示（达到最大步数、输出被截断） */
  notice?: string;
  error?: string;
  usage?: { input: number; output: number; steps: number };
};

export type ChatItem = { kind: "user"; text: string } | AssistantItem;

export type ChatState = { items: ChatItem[]; busy: boolean };

/** 回合结束时，还在 running 的工具卡片统一标成 cancelled，否则会一直转圈 */
function settleTools(parts: Part[]): Part[] {
  return parts.map((p) => (p.kind === "tool" && p.status === "running" ? { ...p, status: "cancelled" } : p));
}

/** 追加文字：最后一段是文字就接上，否则新开一段 */
function appendText(parts: Part[], text: string): Part[] {
  const last = parts[parts.length - 1];
  if (last && last.kind === "text") {
    return [...parts.slice(0, -1), { kind: "text", text: last.text + text }];
  }
  return [...parts, { kind: "text", text }];
}

/** 更新某个工具卡片 */
function updateTool(parts: Part[], callId: string, patch: Partial<ToolPart>): Part[] {
  return parts.map((p) => (p.kind === "tool" && p.callId === callId ? { ...p, ...patch } : p));
}

/**
 * 事件归一化：旧状态 + 一个事件 → 新状态。纯函数，所有后端事件都只走这里。
 */
export function applyEvent(state: ChatState, e: AgentEvent): ChatState {
  const items = [...state.items];
  const last = items[items.length - 1];
  if (!last || last.kind !== "assistant") return state; // 没有正在进行的回答，忽略
  let a: AssistantItem = last;

  switch (e.type) {
    case "turn_started":
      a = { ...a, turnId: e.turnId, model: e.model };
      break;
    case "delta":
      a = { ...a, parts: appendText(a.parts, e.text) };
      break;
    case "tool_call":
      a = { ...a, parts: [...a.parts, { kind: "tool", callId: e.callId, name: e.name, args: e.arguments, status: "running" }] };
      break;
    case "tool_progress":
      a = { ...a, parts: updateTool(a.parts, e.callId, { progress: e.message }) };
      break;
    case "tool_result":
      a = {
        ...a,
        parts: updateTool(a.parts, e.callId, { status: e.ok ? "ok" : "error", result: e.content, durationMs: e.durationMs }),
      };
      break;
    case "done":
      a = {
        ...a,
        status: "done",
        parts: settleTools(a.parts),
        notice: e.notice || undefined,
        usage: { input: e.inputTokens, output: e.outputTokens, steps: e.steps },
      };
      items[items.length - 1] = a;
      return { items, busy: false };
    case "error":
      a = { ...a, status: "error", parts: settleTools(a.parts), error: e.cancelled ? "已停止" : e.message };
      items[items.length - 1] = a;
      return { items, busy: false };
  }
  items[items.length - 1] = a;
  return { ...state, items };
}

type Store = ChatState & {
  /** 用户发送一句话：加一条用户消息和一个空的助手回答 */
  begin: (input: string) => void;
  /** 收到一个后端事件 */
  apply: (e: AgentEvent) => void;
};

export const useChat = create<Store>((set) => ({
  items: [],
  busy: false,
  begin: (input) =>
    set((s) => ({
      busy: true,
      items: [...s.items, { kind: "user", text: input }, { kind: "assistant", parts: [], status: "streaming" }],
    })),
  apply: (e) => set((s) => applyEvent(s, e)),
}));
