import { create } from "zustand";
import type { AgentEvent } from "../api/types";

/** 对话列表里的一项：用户的一句话，或助手的一个回答 */
export type ChatItem =
  | { kind: "user"; text: string }
  | { kind: "assistant"; turnId?: string; text: string; status: "streaming" | "done" | "error"; model?: string };

export type ChatState = { items: ChatItem[]; busy: boolean };

/**
 * 事件归一化：旧状态 + 一个事件 → 新状态。
 *
 * 所有后端事件都只走这一个函数，界面只根据状态渲染。
 * 它是“纯函数”：不改传进来的对象、不发请求、不碰界面，所以测试非常简单（见 turns.test.ts）。
 * 后面加新事件类型时，只需要在这里加一个 case。
 */
export function applyEvent(state: ChatState, e: AgentEvent): ChatState {
  const items = [...state.items]; // 复制一份，不改旧数组（React 靠“新对象”判断需要重新渲染）
  const last = items[items.length - 1];
  // 事件总是作用于最后一个“助手回答”
  const current = last && last.kind === "assistant" ? last : undefined;

  // switch 里 TypeScript 会自动收窄 e 的类型：case "delta" 里 e.text 合法，e.answer 会报错
  switch (e.type) {
    case "turn_started":
      if (current) items[items.length - 1] = { ...current, turnId: e.turnId };
      return { ...state, items };
    case "delta":
      if (current) items[items.length - 1] = { ...current, text: current.text + e.text };
      return { ...state, items };
    case "done":
      if (current) items[items.length - 1] = { ...current, status: "done", model: e.model };
      return { items, busy: false };
    case "error":
      if (current)
        items[items.length - 1] = {
          ...current,
          status: "error",
          text: current.text + (e.cancelled ? "\n（已停止）" : `\n出错：${e.message}`),
        };
      return { items, busy: false };
  }
}

type Store = ChatState & {
  /** 用户发送一句话：加一条用户消息和一条空的助手回答（等着被 delta 填充） */
  begin: (input: string) => void;
  /** 收到一个后端事件 */
  apply: (e: AgentEvent) => void;
};

/**
 * 全局状态（zustand）。任何组件用 useChat() 就能读到同一份对话状态。
 * zustand 比 Redux 简单得多：create 里返回初始状态和修改状态的方法即可。
 */
export const useChat = create<Store>((set) => ({
  items: [],
  busy: false,
  begin: (input) =>
    set((s) => ({
      busy: true,
      items: [...s.items, { kind: "user", text: input }, { kind: "assistant", text: "", status: "streaming" }],
    })),
  apply: (e) => set((s) => applyEvent(s, e)),
}));
