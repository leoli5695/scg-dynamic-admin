export interface RawFrame {
  event: string;
  id?: string;
  data: string;
  value?: unknown;
  kind: 'json' | 'text' | 'partial' | 'done';
  usage: boolean;
  contentType?: 'answer' | 'reasoning' | 'tool';
  preview?: string;
}

/** Split presentation frames only; the caller keeps the exact wire text for the source view. */
export function rawFrames(raw: string): RawFrame[] {
  const frames: RawFrame[] = [];
  const normalized = raw.replace(/\r\n?/g, '\n');
  const blocks = normalized.split('\n\n');
  blocks.forEach((block, index) => {
    if (!block.trim()) return;
    let event = 'data';
    let id: string | undefined;
    const lines: string[] = [];
    for (const line of block.split('\n')) {
      if (line.startsWith('event:')) event = line.slice(6).trim();
      else if (line.startsWith('id:')) id = line.slice(3).trim();
      else if (line.startsWith('data:')) lines.push(line.slice(5).replace(/^ /, ''));
    }
    if (!lines.length) return;
    const data = lines.join('\n');
    if (data.trim() === '[DONE]') { frames.push({ event, id, data, kind: 'done', usage: false }); return; }
    try {
      const value = JSON.parse(data);
      const usage = value !== null && typeof value === 'object'
        && (value.usage != null || value.response?.usage != null || value.message?.usage != null);
      const delta = value?.choices?.[0]?.delta ?? value?.choices?.[0]?.message ?? value?.delta;
      const answer = typeof delta?.content === 'string' ? delta.content : typeof delta?.text === 'string' ? delta.text : typeof value?.delta === 'string' ? value.delta : '';
      const reasoning = typeof delta?.reasoning_content === 'string' ? delta.reasoning_content : typeof delta?.thinking === 'string' ? delta.thinking : '';
      const contentType = answer ? 'answer' : reasoning ? 'reasoning' : Array.isArray(delta?.tool_calls) && delta.tool_calls.length ? 'tool' : undefined;
      const snippet = answer;
      frames.push({ event: event === 'data' && typeof value?.type === 'string' ? value.type : event,
        id, data, value, kind: 'json', usage, contentType, preview: snippet.length > 70 ? snippet.slice(0,70) + '…' : snippet });
    } catch {
      frames.push({ event, id, data, kind: index === blocks.length - 1 && !normalized.endsWith('\n\n') ? 'partial' : 'text', usage: false });
    }
  });
  return frames;
}

export function jsonTokens(value: unknown): { text: string; kind: string }[] {
  let formatted: string;
  try { formatted = JSON.stringify(value, null, 2) ?? String(value); }
  catch { return [{ text: String(value), kind: '' }]; }
  // Tokenize JSON for React text nodes. Never interpret upstream strings as HTML.
  return formatted.split(/("(?:\\.|[^"\\])*"\s*:|"(?:\\.|[^"\\])*"|\b(?:true|false|null)\b|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)/g)
    .filter(Boolean).map(text => ({ text, kind: text.startsWith('"') ? text.trimEnd().endsWith(':') ? 'key' : 'string'
      : text === 'null' ? 'null' : text === 'true' || text === 'false' ? 'boolean' : /^-?\d/.test(text) ? 'number' : '' }));
}
