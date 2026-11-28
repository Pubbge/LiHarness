import { useEffect, useRef, useState } from "react";
import type { ModelsOverview } from "../api/models";

/**
 * 输入框右下角的模型选择器：点开后按服务商分组列出模型，当前用的打勾。
 * 选了以后写进 settings.yaml 的 defaultModel，下一次提问生效。
 */
export function ModelPicker({
  overview,
  onSelect,
  onOpenSettings,
}: {
  overview: ModelsOverview | undefined;
  onSelect: (model: string) => void;
  onOpenSettings: () => void;
}) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  // 点菜单外面就关掉
  useEffect(() => {
    if (!open) return;
    const close = (e: MouseEvent) => {
      if (!ref.current?.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener("mousedown", close);
    return () => document.removeEventListener("mousedown", close);
  }, [open]);

  const label = overview?.currentLabel ?? overview?.currentModel ?? "选择模型";

  return (
    <div className="model-picker" ref={ref}>
      {open && overview && (
        <div className="model-menu" role="menu">
          {overview.providers.map((p) => (
            <div key={p.id}>
              <div className="model-group">
                {p.name}
                {p.keySource === "MISSING" && <span className="error-text">（缺少 Key）</span>}
              </div>
              {p.models.map((m) => {
                const value = `${p.id}/${m}`;
                return (
                  <button
                    type="button"
                    key={value}
                    className="model-item"
                    onClick={() => {
                      setOpen(false);
                      onSelect(value);
                    }}
                  >
                    <span>{m}</span>
                    {value === overview.currentModel && <span aria-label="当前">✓</span>}
                  </button>
                );
              })}
            </div>
          ))}
          {overview.providers.length === 0 && <div className="model-group">还没有添加模型服务商，现在用的是 Mock</div>}
          <button
            type="button"
            className="model-item muted"
            onClick={() => {
              setOpen(false);
              onOpenSettings();
            }}
          >
            ＋ 管理模型服务商…
          </button>
        </div>
      )}
      <button type="button" className="model-current" onClick={() => setOpen((v) => !v)} title={overview?.problem ?? label}>
        {overview?.problem ? <span className="error-text">模型配置有问题</span> : label} <span aria-hidden>⌃</span>
      </button>
    </div>
  );
}
