/** Read bounded SSE frames, including CRLF split across network chunks. */
export async function readCopilotStream(
  body: ReadableStream<Uint8Array>,
  onFrame: (event: string, data: string) => boolean,
  signal?: AbortSignal,
): Promise<void> {
  const reader = body.getReader();
  const decoder = new TextDecoder();
  const limit = 2 * 1024 * 1024;
  let buffer = '';
  let lines: string[] = [];
  let frameSize = 0;
  let terminal = false;
  const abort = () => { void reader.cancel().catch(() => undefined); };
  signal?.addEventListener('abort', abort, { once: true });

  const dispatch = () => {
    let event = 'message';
    const data: string[] = [];
    for (const line of lines) {
      if (line.startsWith('event:')) event = line.slice(6).trim();
      if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
    }
    lines = []; frameSize = 0;
    if (data.length) terminal = onFrame(event, data.join('\n'));
  };

  const consume = (final: boolean) => {
    while (!terminal) {
      const newline = /\r\n|\r|\n/.exec(buffer);
      if (!newline || (!final && newline[0] === '\r' && newline.index === buffer.length - 1)) break;
      const line = buffer.slice(0, newline.index);
      buffer = buffer.slice(newline.index + newline[0].length);
      if (!line) dispatch();
      else { lines.push(line); frameSize += line.length; }
      if (frameSize > limit) throw new Error('AI response event exceeds the size limit.');
    }
    if (!terminal && frameSize + buffer.length > limit) throw new Error('AI response event exceeds the size limit.');
  };

  try {
    signal?.throwIfAborted();
    while (!terminal) {
      const { value, done } = await reader.read();
      signal?.throwIfAborted();
      buffer += done ? decoder.decode() : decoder.decode(value, { stream: true });
      consume(done);
      if (done) {
        if (!terminal && buffer) { lines.push(buffer); buffer = ''; }
        if (!terminal && lines.length) dispatch();
        break;
      }
    }
    if (!terminal) throw new Error('AI response stream ended before the final reply. Please retry.');
  } finally {
    signal?.removeEventListener('abort', abort);
    await reader.cancel().catch(() => undefined);
    reader.releaseLock();
  }
}
