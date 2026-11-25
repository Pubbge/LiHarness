import { useEffect, useRef, useState } from "react";
import { streamTurn } from "./api/client";
import { useChat } from "./state/turns";

/** 对话界面：消息列表 + 输入框 */
export function Chat() {
  const { items, busy, begin, apply } = useChat();
  const [input, setInput] = useState("");
  const bottomRef = useRef<HTMLDivElement>(null);

  // 有新内容时自动滚动到底部
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ block: "end" });
  }, [items]);

  async function send() {
    const text = input.trim();
    if (!text || busy) return; // 空内容或上一个回答还没结束，不发
    setInput("");
    begin(text);
    try {
      // sessionId 先固定为 default，第 12 章做多会话时再改
      await streamTurn({ sessionId: "default", input: text }, apply);
    } catch (err) {
      // 请求本身失败（后端没起来、网络错误……）也走同一个归一化函数
      apply({ type: "error", message: String(err), cancelled: false });
    }
  }

  return (
    <section className="chat">
      <div className="messages">
        {items.length === 0 && <p className="empty">发一句话试试，回答会一个字一个字地出现。</p>}
        {items.map((m, i) => (
          <div key={i} className={`bubble ${m.kind}${m.kind === "assistant" && m.status === "error" ? " error" : ""}`}>
            {m.text}
            {/* 回答还在生成时显示一个闪烁的光标 */}
            {m.kind === "assistant" && m.status === "streaming" && <span className="cursor">▍</span>}
          </div>
        ))}
        <div ref={bottomRef} />
      </div>
      <form
        className="composer"
        onSubmit={(e) => {
          e.preventDefault(); // 阻止表单默认的“提交后刷新页面”
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
        <button type="submit" disabled={busy || !input.trim()}>
          发送
        </button>
      </form>
    </section>
  );
}
