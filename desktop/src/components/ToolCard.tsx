import { useState } from "react";
import type { ToolPart } from "../state/turns";

const STATUS_TEXT: Record<ToolPart["status"], string> = {
  running: "运行中",
  ok: "完成",
  error: "失败",
  cancelled: "已取消",
};

/**
 * 一次工具调用的卡片：默认只显示一行摘要，点击展开参数和结果。
 *
 * 为什么默认折叠：一个回合可能调用十几次工具，全部展开会把真正的回答挤得看不见。
 * 用户关心的是“它做了什么”，细节需要时再看。
 */
export function ToolCard({ part }: { part: ToolPart }) {
  const [open, setOpen] = useState(false);
  return (
    <div className={`tool-card ${part.status}`}>
      <button type="button" className="tool-head" onClick={() => setOpen(!open)}>
        <span className="tool-icon">{part.status === "running" ? "⏳" : open ? "▾" : "▸"}</span>
        <code className="tool-name">{part.name}</code>
        <span className="tool-summary">{summarizeArgs(part.args)}</span>
        <span className="tool-status">
          {STATUS_TEXT[part.status]}
          {part.durationMs !== undefined && ` · ${part.durationMs}ms`}
        </span>
      </button>
      {part.status === "running" && part.progress && <div className="tool-progress">{part.progress}</div>}
      {open && (
        <div className="tool-body">
          <div className="tool-label">参数</div>
          <pre>{prettyJson(part.args)}</pre>
          {part.result !== undefined && (
            <>
              <div className="tool-label">结果</div>
              <pre>{part.result}</pre>
            </>
          )}
        </div>
      )}
    </div>
  );
}

/** 参数摘要：取第一个字符串参数的值，比如 read_file 显示路径，run_command 显示命令 */
function summarizeArgs(args: string): string {
  try {
    const obj = JSON.parse(args) as Record<string, unknown>;
    const first = Object.values(obj).find((v) => typeof v === "string") as string | undefined;
    if (!first) return "";
    return first.length > 60 ? first.slice(0, 60) + "…" : first;
  } catch {
    return "";
  }
}

function prettyJson(s: string): string {
  try {
    return JSON.stringify(JSON.parse(s), null, 2);
  } catch {
    return s;
  }
}
