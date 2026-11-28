import { useEffect, useRef, useState } from "react";
import { streamTurn } from "./api/client";
import type { ModelsOverview } from "./api/models";
import { ModelPicker } from "./components/ModelPicker";
import { ToolCard } from "./components/ToolCard";
import { useChat, type AssistantItem } from "./state/turns";

/** 对话界面：消息列表 + 输入框（右下角可以切换模型） */
export function Chat({
  models,
  modelError,
  onSelectModel,
  onOpenSettings,
}: {
  models: ModelsOverview | undefined;
  modelError: string | undefined;
  onSelectModel: (model: string) => void;
  onOpenSettings: () => void;
}) {
  const { items, busy, begin, apply } = useChat();
  const [input, setInput] = useState("");
  const bottomRef = useRef<HTMLDivElement>(null);

  // 有新内容时自动滚动到底部
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ block: "end" });
  }, [items]);

  async function send() {
    const text = input.trim();
    if (!text || busy) return;
    setInput("");
    begin(text);
    try {
      await streamTurn({ sessionId: "default", input: text }, apply);
    } catch (err) {
      apply({ type: "error", message: err instanceof Error ? err.message : String(err), cancelled: false });
    }
  }

  return (
    <section className="chat">
      <div className="messages">
        {items.length === 0 && <p className="empty">试试问“现在几点了”，看看它怎么调用工具。</p>}
        {items.map((m, i) =>
          m.kind === "user" ? (
            <div key={i} className="bubble user">
              {m.text}
            </div>
          ) : (
            <AssistantBubble key={i} item={m} />
          ),
        )}
        <div ref={bottomRef} />
      </div>
      <form
        className="composer"
        onSubmit={(e) => {
          e.preventDefault();
          void send();
        }}
      >
        <input
          value={input}
          onChange={(e) => setInput(e.currentTarget.value)}
          placeholder="问点什么…"
          disabled={busy}
          autoFocus
        />
        <ModelPicker overview={models} onSelect={onSelectModel} onOpenSettings={onOpenSettings} />
        <button type="submit" disabled={busy || !input.trim()}>
          发送
        </button>
      </form>
      {modelError && <p className="error-text composer-error">{modelError}</p>}
    </section>
  );
}

/** 一个助手回答：按顺序显示文字段落和工具卡片，最后是提示、错误和用量 */
function AssistantBubble({ item }: { item: AssistantItem }) {
  return (
    <div className={`bubble assistant${item.status === "error" ? " error" : ""}`}>
      {item.parts.map((p, i) =>
        p.kind === "text" ? (
          <div key={i} className="text">
            {p.text}
          </div>
        ) : (
          <ToolCard key={p.callId} part={p} />
        ),
      )}
      {item.status === "streaming" && <span className="cursor">▍</span>}
      {item.notice && <div className="notice">⚠️ {item.notice}</div>}
      {item.error && <div className="error-text">{item.error}</div>}
      {item.usage && (
        <div className="meta">
          {item.model} · {item.usage.steps} 步 · 输入 {item.usage.input} / 输出 {item.usage.output} tokens
        </div>
      )}
    </div>
  );
}
