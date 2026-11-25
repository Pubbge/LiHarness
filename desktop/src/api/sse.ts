/** 一条解析好的 SSE 事件：event 是事件名，data 是原始字符串（通常是 JSON） */
export type SseMessage = { event: string; data: string };

/**
 * 把响应体的字节流还原成一条条 SSE 事件。
 *
 * 为什么不用浏览器自带的 EventSource？它只能发 GET、不能加请求头，
 * 而我们要 POST 发问题、还要带令牌，所以自己解析。
 *
 * SSE 的格式：每个事件由若干行组成，事件之间用一个空行分隔。
 *   event: delta
 *   data: {"text":"你"}
 *   （空行）
 */
export async function readSse(response: Response, onMessage: (m: SseMessage) => void): Promise<void> {
  if (!response.body) throw new Error("响应没有内容");
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  // 网络传来的数据块是任意切分的：一块里可能有半个事件，也可能有好几个事件。
  // 所以先攒在 buffer 里，遇到空行才算凑齐了一个完整事件。
  let buffer = "";

  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    // stream: true：一个汉字是 3 个字节，可能被切在两个数据块里；
    // 解码器会把残缺的字节留到下一次，而不是解成乱码。
    buffer += decoder.decode(value, { stream: true });
    buffer = buffer.replace(/\r\n/g, "\n"); // 统一换行符

    let boundary: number;
    while ((boundary = buffer.indexOf("\n\n")) >= 0) {
      const block = buffer.slice(0, boundary); // 一个完整事件
      buffer = buffer.slice(boundary + 2); // 剩下的留着继续攒

      let event = "message"; // SSE 标准：没写 event 时默认叫 message
      const data: string[] = [];
      for (const line of block.split("\n")) {
        if (line.startsWith(":")) continue; // 冒号开头是注释，常用作心跳
        if (line.startsWith("event:")) event = line.slice(6).trim();
        else if (line.startsWith("data:")) data.push(line.slice(5).replace(/^ /, ""));
      }
      // 一个事件可以有多行 data:，按标准用换行拼起来
      if (data.length > 0) onMessage({ event, data: data.join("\n") });
    }
  }
}
