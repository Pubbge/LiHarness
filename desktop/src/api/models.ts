import { apiFetch } from "./client";
import type { ProtocolId } from "./modelForm";

/** Key 的来源。界面上只显示状态，任何接口都不会返回 Key 本身 */
export type KeySource = "SAVED" | "ENV" | "NOT_REQUIRED" | "MISSING";

/** 你添加的一个模型服务商 */
export type ProviderView = {
  id: string;
  name: string;
  baseUrl: string | null;
  models: string[];
  keySource: KeySource;
  requiresKey: boolean;
};

/** 后端 GET /api/models 的返回，对应 ModelCase.Overview */
export type ModelsOverview = {
  currentModel: string | null;
  currentLabel: string | null;
  problem: string | null;
  providers: ProviderView[];
  settingsFile: string;
  credentialsFile: string;
};

/** 添加 / 编辑服务商的表单。apiKey 为空表示不修改已保存的 Key */
export type ProviderForm = {
  name: string;
  /** 接口格式。后端现在只认 openai-completions，以后加了 Anthropic 适配器再接收这个字段 */
  protocol: ProtocolId;
  baseUrl: string;
  models: string[];
  apiKey?: string;
  requiresKey: boolean;
};

/** 统一处理：成功返回最新的 ModelsOverview；失败抛出后端给的说明 */
async function call(path: string, init?: RequestInit): Promise<ModelsOverview> {
  const res = await apiFetch(path, init);
  const body = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(body.message ?? `请求失败：HTTP ${res.status}`);
  return body as ModelsOverview;
}

const json = (method: string, data: unknown): RequestInit => ({
  method,
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify(data),
});

export const getModels = () => call("/api/models");
export const selectModel = (model: string) => call("/api/models/current", json("PUT", { model }));
export const saveProvider = (id: string, form: ProviderForm) =>
  call(`/api/models/providers/${encodeURIComponent(id)}`, json("PUT", form));
export const deleteProvider = (id: string) =>
  call(`/api/models/providers/${encodeURIComponent(id)}`, { method: "DELETE" });

export { chatCompletionsUrl, parseModelList, PROTOCOLS, requestUrl, type ProtocolId } from "./modelForm";
