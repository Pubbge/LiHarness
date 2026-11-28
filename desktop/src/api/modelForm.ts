/** 设置页表单用的小工具：纯函数，不依赖后端，方便测试 */

/**
 * 接口格式（协议）。决定请求发到哪个路径、用什么格式。
 * supported=false 的表示后端还没有对应的适配器：先列出来，选不了。
 */
export const PROTOCOLS = [
  { id: "openai-completions", label: "OpenAI 兼容", path: "/chat/completions", supported: true },
  { id: "anthropic-messages", label: "Anthropic", path: "/v1/messages", supported: false },
] as const;

export type ProtocolId = (typeof PROTOCOLS)[number]["id"];

/** 请求实际发到的地址：和后端的规则一样，路径由程序拼上；用户贴了完整地址也认 */
export function requestUrl(baseUrl: string, protocol: ProtocolId = "openai-completions"): string {
  const path = PROTOCOLS.find((p) => p.id === protocol)?.path ?? "/chat/completions";
  let u = baseUrl.trim().replace(/\/+$/, "");
  if (u.endsWith(path)) u = u.slice(0, -path.length);
  return u ? `${u}${path}` : "";
}

/** 兼容旧名字：OpenAI 兼容格式的请求地址 */
export const chatCompletionsUrl = (baseUrl: string) => requestUrl(baseUrl, "openai-completions");

/** “deepseek-v4-flash, deepseek-v4-pro” → ["deepseek-v4-flash", "deepseek-v4-pro"]，去掉空的和重复的 */
export function parseModelList(text: string): string[] {
  return [...new Set(text.split(/[,，\n]/).map((s) => s.trim()).filter(Boolean))];
}
