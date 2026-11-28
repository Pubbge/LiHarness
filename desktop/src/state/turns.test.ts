import { describe, expect, it } from "vitest";
import { applyEvent, type ChatState } from "./turns";

/** 一个“刚发出问题、等着回答”的初始状态 */
function waiting(): ChatState {
  return {
    busy: true,
    items: [
      { kind: "user", text: "几点了" },
      { kind: "assistant", parts: [], status: "streaming" },
    ],
  };
}

const done = {
  type: "done",
  turnId: "t1",
  outcome: "COMPLETED",
  answer: "",
  notice: "",
  steps: 2,
  inputTokens: 30,
  outputTokens: 10,
  totalTokens: 40,
} as const;

describe("applyEvent", () => {
  it("文字和工具调用按到达顺序交错", () => {
    let s = waiting();
    s = applyEvent(s, { type: "turn_started", turnId: "t1", sessionId: "s1", model: "mock" });
    s = applyEvent(s, { type: "delta", text: "我查" });
    s = applyEvent(s, { type: "delta", text: "一下" });
    s = applyEvent(s, { type: "tool_call", callId: "c1", name: "get_time", arguments: "{}" });
    s = applyEvent(s, { type: "tool_result", callId: "c1", name: "get_time", ok: true, content: "10:00", durationMs: 3 });
    s = applyEvent(s, { type: "delta", text: "现在 10 点" });
    s = applyEvent(s, done);

    expect(s.busy).toBe(false);
    expect(s.items[1]).toMatchObject({
      status: "done",
      model: "mock",
      usage: { input: 30, output: 10, steps: 2 },
      parts: [
        { kind: "text", text: "我查一下" },
        { kind: "tool", callId: "c1", status: "ok", result: "10:00", durationMs: 3 },
        { kind: "text", text: "现在 10 点" },
      ],
    });
  });

  it("停止时，还在运行的工具卡片标记为已取消", () => {
    let s = applyEvent(waiting(), { type: "tool_call", callId: "c1", name: "run_command", arguments: "{}" });
    s = applyEvent(s, { type: "error", message: "已停止", cancelled: true });
    expect(s.items[1]).toMatchObject({ status: "error", error: "已停止", parts: [{ status: "cancelled" }] });
    expect(s.busy).toBe(false);
  });

  it("达到最大步数时显示提示", () => {
    const s = applyEvent(waiting(), { ...done, outcome: "MAX_STEPS", notice: "已达到单回合最大步数" });
    expect(s.items[1]).toMatchObject({ status: "done", notice: "已达到单回合最大步数" });
  });

  it("不修改传入的旧状态", () => {
    const before = waiting();
    applyEvent(before, { type: "delta", text: "x" });
    expect(before.items[1]).toMatchObject({ parts: [] });
  });
});
