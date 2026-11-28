import { useState } from "react";
import {
  deleteProvider,
  PROTOCOLS,
  requestUrl,
  type ProtocolId,
  parseModelList,
  saveProvider,
  type ModelsOverview,
  type ProviderView,
} from "../api/models";

const KEY_TEXT = {
  SAVED: "已配置 Key",
  ENV: "Key 来自环境变量",
  NOT_REQUIRED: "不需要 Key",
  MISSING: "缺少 Key",
} as const;

/** 正在编辑的东西：一个已有的服务商，或者新加一个 */
type Editing = { isNew: boolean; id: string; name: string; protocol: ProtocolId; baseUrl: string; models: string; requiresKey: boolean; hasKey: boolean };

/**
 * 设置页。左边是栏目（现在只有“模型”），右边是内容。
 *
 * “模型”栏列出 settings.yaml 里的服务商（第一次启动时默认就有 DeepSeek，填上 API Key 就能用）；
 * 需要别家的，点“添加模型提供商”填地址、Key 和模型名。
 * 保存后服务商写进 settings.yaml、Key 写进 .credentials.yaml，输入框里的模型选择器马上就能选到它的模型。
 */
export function SettingsDialog({
  overview,
  onChange,
  onClose,
}: {
  overview: ModelsOverview;
  onChange: (o: ModelsOverview) => void;
  onClose: () => void;
}) {
  return (
    <div className="settings-backdrop" onMouseDown={onClose}>
      <div className="settings-dialog" onMouseDown={(e) => e.stopPropagation()}>
        <nav className="settings-nav">
          <h2>设置</h2>
          <div className="settings-nav-item active">模型</div>
        </nav>
        <section className="settings-body">
          <div className="settings-title">
            <h3>模型</h3>
            <button type="button" className="ghost" onClick={onClose} aria-label="关闭">
              ✕
            </button>
          </div>
          <p className="muted">填入各提供商的 API 密钥即可使用其模型。</p>
          <ModelProviders overview={overview} onChange={onChange} />
          <p className="hint">
            配置保存在 <code>{overview.settingsFile}</code>，Key 保存在 <code>{overview.credentialsFile}</code>
            （只有你自己能读）。直接用编辑器改这两个文件也一样，保存后下一次提问生效。
          </p>
        </section>
      </div>
    </div>
  );
}

function ModelProviders({ overview, onChange }: { overview: ModelsOverview; onChange: (o: ModelsOverview) => void }) {
  const [editing, setEditing] = useState<Editing>();

  const edit = (p: ProviderView) =>
    setEditing({
      isNew: false,
      id: p.id,
      name: p.name,
      protocol: "openai-completions",
      baseUrl: p.baseUrl ?? "",
      models: p.models.join(", "),
      requiresKey: p.requiresKey,
      hasKey: p.keySource === "SAVED",
    });

  const start = () =>
    setEditing({ isNew: true, id: "", name: "", protocol: "openai-completions", baseUrl: "", models: "", requiresKey: true, hasKey: false });

  return (
    <div className="provider-list">
      {overview.problem && <p className="error-text">{overview.problem}</p>}
      {overview.providers.map((p) =>
        editing && !editing.isNew && editing.id === p.id ? (
          <ProviderEditor key={p.id} value={editing} onCancel={() => setEditing(undefined)} onSaved={(o) => { onChange(o); setEditing(undefined); }} />
        ) : (
          <div className="provider-card" key={p.id}>
            <span>
              <strong>{p.name}</strong>
              <span className={`dot ${p.keySource === "MISSING" ? "bad" : "good"}`} />
              <span className={p.keySource === "MISSING" ? "error-text" : "ok-text"}>{KEY_TEXT[p.keySource]}</span>
              <span className="muted"> · {p.models.length} 个模型</span>
            </span>
            <button type="button" onClick={() => edit(p)}>
              编辑
            </button>
          </div>
        ),
      )}

      {editing?.isNew && (
        <ProviderEditor value={editing} onCancel={() => setEditing(undefined)} onSaved={(o) => { onChange(o); setEditing(undefined); }} />
      )}

      {!editing && (
        <button type="button" className="add-provider" onClick={start}>
          ＋ 添加模型提供商
        </button>
      )}
    </div>
  );
}

function ProviderEditor({
  value,
  onCancel,
  onSaved,
}: {
  value: Editing;
  onCancel: () => void;
  onSaved: (o: ModelsOverview) => void;
}) {
  const [v, setV] = useState(value);
  const [apiKey, setApiKey] = useState("");
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);
  const set = (patch: Partial<Editing>) => {
    setV({ ...v, ...patch });
    setError(undefined);
  };

  async function run(action: () => Promise<ModelsOverview>) {
    setBusy(true);
    setError(undefined);
    try {
      onSaved(await action());
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  function save() {
    const id = v.id.trim();
    if (!/^[a-z0-9][a-z0-9-]{0,31}$/.test(id)) return setError("标识只能用小写字母、数字和 -");
    const models = parseModelList(v.models);
    if (models.length === 0) return setError("至少填一个模型名");
    if (v.isNew && v.requiresKey && !apiKey.trim()) return setError("请填写 API Key");
    void run(() =>
      saveProvider(id, { name: v.name.trim() || id, protocol: v.protocol, baseUrl: v.baseUrl.trim(), models, apiKey: apiKey.trim() || undefined, requiresKey: v.requiresKey }),
    );
  }

  return (
    <div className="provider-editor">
      <strong>{v.isNew ? "添加模型提供商" : `编辑：${v.name}`}</strong>
      <label>
        标识（写在模型名前面，只能用小写字母、数字和 -）
        <input value={v.id} onChange={(e) => set({ id: e.target.value })} disabled={!v.isNew} placeholder="my-gateway" />
      </label>
      <label>
        名称
        <input value={v.name} onChange={(e) => set({ name: e.target.value })} placeholder="公司网关" />
      </label>
      <label>
        接口格式
        <select value={v.protocol} onChange={(e) => set({ protocol: e.target.value as ProtocolId })} disabled={!v.isNew}>
          {PROTOCOLS.map((p) => (
            <option key={p.id} value={p.id} disabled={!p.supported}>
              {p.label}（{p.path}）{p.supported ? "" : " · 暂不支持"}
            </option>
          ))}
        </select>
      </label>
      <label>
        接口地址
        <input value={v.baseUrl} onChange={(e) => set({ baseUrl: e.target.value })} placeholder="https://example.com/v1" />
        <span className="hint">
          写到 {PROTOCOLS.find((p) => p.id === v.protocol)?.path} 前面那一段就行，后面由程序拼上
          {requestUrl(v.baseUrl, v.protocol) && <>。实际请求：{requestUrl(v.baseUrl, v.protocol)}</>}
        </span>
      </label>
      {v.requiresKey && (
        <label>
          API Key{v.hasKey ? "（已保存，留空表示不修改）" : ""}
          <input
            type="password"
            value={apiKey}
            onChange={(e) => {
              setApiKey(e.target.value);
              setError(undefined);
            }}
            placeholder={v.hasKey ? "••••••••" : "粘贴 API Key"}
            autoComplete="off"
          />
        </label>
      )}
      <label>
        模型（多个用逗号隔开，名字以服务商文档为准）
        <input value={v.models} onChange={(e) => set({ models: e.target.value })} placeholder="例如 qwen-max, qwen-plus" />
      </label>
      <label className="inline">
        <input type="checkbox" checked={!v.requiresKey} onChange={(e) => set({ requiresKey: !e.target.checked })} />
        不需要 API Key（本机的 Ollama 等）
      </label>
      {error && <p className="error-text">{error}</p>}
      <div className="editor-actions">
        {!v.isNew && (
          <button type="button" className="danger" disabled={busy} onClick={() => void run(() => deleteProvider(v.id))}>
            删除
          </button>
        )}
        <button type="button" onClick={onCancel} disabled={busy}>
          取消
        </button>
        <button type="button" className="primary" onClick={save} disabled={busy}>
          保存
        </button>
      </div>
    </div>
  );
}
