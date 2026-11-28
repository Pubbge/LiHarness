import { describe, expect, it } from "vitest";
import { chatCompletionsUrl, parseModelList, requestUrl } from "./modelForm";

describe("chatCompletionsUrl", () => {
  it("只写到 /chat/completions 前面，后面由程序拼上；贴了完整地址也认", () => {
    expect(chatCompletionsUrl("https://api.deepseek.com/")).toBe("https://api.deepseek.com/chat/completions");
    expect(chatCompletionsUrl("https://x.example/v1/chat/completions")).toBe("https://x.example/v1/chat/completions");
  });
});

describe("requestUrl", () => {
  it("按协议拼不同的路径", () => {
    expect(requestUrl("https://api.anthropic.com", "anthropic-messages")).toBe("https://api.anthropic.com/v1/messages");
    expect(requestUrl("https://api.deepseek.com", "openai-completions")).toBe("https://api.deepseek.com/chat/completions");
  });
});

describe("parseModelList", () => {
  it("中英文逗号、换行都能分隔，去掉空的和重复的", () => {
    expect(parseModelList(" deepseek-v4-flash，deepseek-v4-pro,\n, deepseek-v4-flash ")).toEqual([
      "deepseek-v4-flash",
      "deepseek-v4-pro",
    ]);
  });
});
