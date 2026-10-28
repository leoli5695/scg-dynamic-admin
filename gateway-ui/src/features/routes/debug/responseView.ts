export interface ResponseView {
  answer: string;
  reasoning: string;
  tools: { name: string; arguments: string }[];
  usage: { input?: number; output?: number; total?: number } | null;
  formatted: string;
  modelResponse: boolean;
  complete: boolean;
}

const count = (value: unknown): number | undefined =>
  typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 ? value : undefined;
const text = (value: unknown): string => typeof value === 'string' ? value
  : Array.isArray(value) ? value.map(item => typeof item?.text === 'string' ? item.text : '').join('') : '';
interface ModelMessage {
  content?: unknown;
  reasoning_content?: unknown;
  reasoning?: unknown;
  tool_calls?: { index?: number; function?: { name?: unknown; arguments?: unknown } }[];
}
interface ModelChunk {
  choices?: { index?: number; delta?: ModelMessage; message?: ModelMessage }[];
  type?: string;
  usage?: Record<string, unknown>;
  message?: { usage?: Record<string, unknown> };
  response?: { usage?: Record<string, unknown> };
  delta?: string | { text?: string; thinking?: string };
  content?: unknown[];
  output?: { content?: unknown }[];
}

/** Read the display projection only. Raw output is preserved separately, without changing metering. */
export function responseView(raw: string, streaming: boolean): ResponseView {
  const view: ResponseView = { answer: '', reasoning: '', tools: [], usage: null, formatted: raw, modelResponse: false, complete: false };
  const tools = new Map<string, { name: string; arguments: string }>();
  const anthropicUsage: Record<string, unknown> = {};
  const usage = (value: Record<string, unknown> | undefined, anthropic = false) => {
    if (!value || typeof value !== 'object') return;
    let input = count(value.prompt_tokens ?? value.input_tokens);
    const output = count(value.completion_tokens ?? value.output_tokens);
    if (anthropic && input !== undefined) input += (count(value.cache_read_input_tokens) ?? 0) + (count(value.cache_creation_input_tokens) ?? 0);
    const total = count(value.total_tokens) ?? (input !== undefined && output !== undefined ? input + output : undefined);
    if (input !== undefined || output !== undefined || total !== undefined) view.usage = { input, output, total };
  };
  const accept = (root: ModelChunk) => {
    if (!root || typeof root !== 'object') return;
    if (Array.isArray(root.choices)) {
      view.modelResponse = true;
      for (const choice of root.choices) {
        if (!choice || typeof choice !== 'object') continue;
        const message = choice.delta ?? choice.message;
        if (!message) continue;
        // The first choice is the primary answer; other choices remain in the raw response.
        if ((choice.index ?? 0) !== 0) continue;
        view.answer += text(message.content);
        view.reasoning += text(message.reasoning_content ?? message.reasoning);
        for (const [position, call] of (Array.isArray(message.tool_calls) ? message.tool_calls : []).entries()) {
          if (!call?.function) continue;
          const key = String(call.index ?? position);
          const tool = tools.get(key) ?? { name: '', arguments: '' };
          tool.name += text(call.function.name);
          tool.arguments += text(call.function.arguments);
          tools.set(key, tool);
        }
      }
      usage(root.usage);
    } else if (root.type === 'message_start') {
      view.modelResponse = true;
      Object.assign(anthropicUsage, root.message?.usage);
      usage(anthropicUsage, true);
    } else if (root.type === 'message_delta') {
      view.modelResponse = true;
      Object.assign(anthropicUsage, root.usage);
      usage(anthropicUsage, true);
    } else if (root.type === 'content_block_delta') {
      view.modelResponse = true;
      if (typeof root.delta === 'object') {
        view.answer += text(root.delta?.text);
        view.reasoning += text(root.delta?.thinking);
      }
    } else if (root.type === 'message_stop') {
      view.modelResponse = true; view.complete = true;
    } else if (root.type === 'response.output_text.delta') {
      view.modelResponse = true; view.answer += text(root.delta);
    } else if (root.type === 'response.reasoning_summary_text.delta') {
      view.modelResponse = true; view.reasoning += text(root.delta);
    } else if (root.type === 'response.completed') {
      view.modelResponse = true; view.complete = true; usage(root.response?.usage);
    } else if (Array.isArray(root.content) && root.type === 'message') {
      view.modelResponse = true; view.answer = text(root.content); usage(root.usage, true);
    } else if (Array.isArray(root.output)) {
      view.modelResponse = true;
      view.answer = root.output.map(item => text(item.content)).join('');
      usage(root.usage);
    }
  };
  if (streaming) {
    for (const frame of raw.replace(/\r\n?/g, '\n').split('\n\n')) {
      const data = frame.split('\n').filter(line => line.startsWith('data:')).map(line => line.slice(5).replace(/^ /, '')).join('\n').trim();
      if (data === '[DONE]') { view.complete = true; continue; }
      if (!data) continue;
      try { accept(JSON.parse(data)); } catch { /* A live frame may still be arriving. */ }
    }
  } else {
    try { const parsed = JSON.parse(raw); view.formatted = JSON.stringify(parsed, null, 2); accept(parsed); }
    catch { /* Plain text, HTML and partially received JSON keep their original content. */ }
  }
  view.tools = [...tools.values()];
  return view;
}
