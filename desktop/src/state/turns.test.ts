import { describe, expect, it } from "vitest";
import { applyEvent, type ChatState } from "./turns";

/** 一个“刚发出问题、等着回答”的初始状态 */
function waiting(): ChatState {
  return {
    busy: true,
    items: [
      { kind: "user", text: "hi" },
      { kind: "assistant", text: "", status: "streaming" },
    ],
  };
}

describe("applyEvent", () => {
  it("拼接 delta，done 后结束", () => {
    let s = waiting();
    s = applyEvent(s, { type: "turn_started", turnId: "t1", sessionId: "s1" });
    s = applyEvent(s, { type: "delta", text: "你" });
    s = applyEvent(s, { type: "delta", text: "好" });
    s = applyEvent(s, { type: "done", turnId: "t1", answer: "你好", model: "demo" });
    expect(s.items[1]).toMatchObject({ text: "你好", status: "done", turnId: "t1", model: "demo" });
    expect(s.busy).toBe(false);
  });

  it("出错时保留已有文字并标记错误", () => {
    let s = applyEvent(waiting(), { type: "delta", text: "一半" });
    s = applyEvent(s, { type: "error", message: "网络断了", cancelled: false });
    expect(s.items[1]).toMatchObject({ status: "error", text: "一半\n出错：网络断了" });
    expect(s.busy).toBe(false);
  });

  it("不修改传入的旧状态", () => {
    const before = waiting();
    applyEvent(before, { type: "delta", text: "x" });
    expect(before.items[1]).toMatchObject({ text: "" });
  });
});
