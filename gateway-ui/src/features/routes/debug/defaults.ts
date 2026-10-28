import type { RouteResponse } from '../../../types/route';

const firstValue = (value: string): string => {
  try {
    const parsed: unknown = JSON.parse(value);
    if (Array.isArray(parsed)) return String(parsed[0] ?? '');
  } catch { /* Plain predicate arguments are not JSON. */ }
  return value.split(',')[0].trim();
};

/** Defaults describe the incoming request, before StripPrefix/RewritePath. */
export function debugDefaults(route: RouteResponse) {
  const predicates = route.predicates ?? [];
  const paths = predicates.find((p) => p.name === 'Path')?.args ?? {};
  const pattern = paths.pattern || paths.patterns || paths._genkey_0 || Object.values(paths)[0] || '/';
  const ai = route.category === 'AI';
  let path = firstValue(pattern).replace(/\{[^}]+\}/g, '1');
  if (path.includes('*')) {
    path = path.slice(0, path.indexOf('*'));
    if (ai) path += path.endsWith('/v1/') ? 'chat/completions' : 'v1/chat/completions';
  }
  if (!path.startsWith('/')) path = '/';
  const methods = predicates.find((p) => p.name === 'Method')?.args ?? {};
  const method = firstValue(methods.methods || methods._genkey_0 || Object.values(methods)[0] || (ai ? 'POST' : 'GET')).toUpperCase();
  const headers: Record<string, string> = {};
  for (const predicate of predicates) {
    const args = predicate.args ?? {};
    if (predicate.name === 'Host') {
      const host = args.patterns || args.pattern || args._genkey_0;
      if (host) headers.Host = firstValue(host).replace(/\*+/g, 'debug').replace(/\{[^}]+\}/g, 'demo');
    }
    if (predicate.name === 'Header') {
      const name = args.header || args.name || args._genkey_0;
      const value = args.regexp || args._genkey_1 || '';
      if (name) headers[name] = /^[\w .:/-]+$/.test(value) ? value : '';
    }
  }
  const mapping = route.aiUpstreams?.[0]?.modelMapping;
  const model = Object.keys(mapping ?? {})[0] || route.modelPredicates?.[0]?.value || 'gpt-4o-mini';
  const body = ai ? JSON.stringify({ model, messages: [{ role: 'user', content: 'Hello' }], stream: true }, null, 2)
    : ['GET', 'HEAD'].includes(method) ? '' : '{}';
  if (body) headers['Content-Type'] = 'application/json';
  return { method, path, body, headers: JSON.stringify(headers, null, 2) };
}

export function parseDebugHeaders(text: string): Record<string, string> {
  const value: unknown = JSON.parse(text || '{}');
  if (!value || typeof value !== 'object' || Array.isArray(value)
    || Object.values(value).some((entry) => typeof entry !== 'string')) {
    throw new Error('headers');
  }
  return value as Record<string, string>;
}
