/**
 * i18n catalog guard.
 *
 * Runs under `npm run test:routes` (node --test tests/*.test.mjs) and therefore in CI.
 * It asserts the three ways the console has historically leaked untranslated text:
 *   1. a key referenced by t() that no language pack defines,
 *   2. zh/en packs drifting apart (missing key, empty value, mismatched placeholder),
 *   3. user-visible text hardcoded in a component instead of going through t().
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import ts from 'typescript';
import { srcRequire } from './srcRequire.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const SRC = path.resolve(HERE, '../src');
const LOCALES = path.join(SRC, 'locales');
const LANGS = ['zh', 'en'];
const CJK = /[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]/;

/**
 * Chinese text that is deliberately NOT translated. Every entry needs a reason.
 * `texts` matches a whole literal, `prefixes` matches its start (long config samples).
 */
const CJK_ALLOWLIST = [
  {
    file: 'components/ai/AiReportRenderer.tsx',
    texts: [
      '正常', '优秀', '异常', '警告', '需优化', '关注', '良好',
      '高', '高影响', '中', '中影响', '低', '低影响',
      '🔴 高影响', '🟠 中影响', '🟢 低影响',
      '名称', '耗时', '延迟', '状态', '影响',
    ],
    reason: 'Lookup keys matching AI-generated report content (status/impact colour maps, column-type detection). The report language is chosen by the backend and may differ from the UI language; translating these breaks colouring and column detection.',
  },
  {
    file: 'features/access-log/constants.ts',
    prefixes: [
      '# Fluent Bit DaemonSet 配置示例',
      '# Docker Compose sidecar 示例',
      '# Fluent Bit 本地采集配置',
      '# Fluent Bit 自定义路径配置',
    ],
    reason: 'Copy-paste collector config samples; the comments travel with the config file, not the UI.',
  },
  {
    file: 'features/copilot/components/ToolsTab.tsx',
    texts: [
      ' (目标服务: , 服务ID: , 使用 static://)',
      ' (目标服务: , namespace: , group: , 使用 lb://)',
      ' (目标服务: , 使用 lb://)',
    ],
    reason: 'serviceHint is concatenated into the description sent to the AI route generator. Prompt text, not UI copy.',
  },
  {
    file: 'features/layout/components/LanguageSwitcher.tsx',
    texts: ['中文', '语言 / Language'],
    reason: 'Language autonym and the intentionally bilingual popup heading.',
  },
  {
    file: 'features/plugin-bindings/utils.tsx',
    texts: ['流量控制', '安全防护', '弹性容错', '数据转换', '可观测性', '其他'],
    reason: 'Raw backend category values used as lookup keys in CATEGORY_LABEL_KEYS / CATEGORY_DESIGN. Only the labels resolved from them are localized.',
  },
  {
    file: 'features/strategies/components/AiSecurityFields.tsx',
    texts: ['请求或响应包含不被允许的内容，已被网关拦截。'],
    reason: 'blockMessage initialValue is persisted into the strategy config and returned by the gateway at runtime. Translating it would change stored data.',
  },
  {
    file: 'features/strategies/strategyPicker.ts',
    texts: [
      'JWT OAuth2 API Key API_KEY Basic HMAC 认证 鉴权',
      'QPS 限流 流量',
      'Token 令牌 配额 限流',
      'IP 黑名单 白名单',
    ],
    reason: 'Search aliases: users must find strategies by Chinese terms in either UI language.',
  },
  {
    file: 'features/topology/chartOption.ts',
    texts: ['静态服务'],
    reason: 'Compared against backend TrafficTopologyService.displayServiceType. Data comparison, not UI copy.',
  },
];

function walk(dir, acc = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, acc);
    else acc.push(p);
  }
  return acc;
}

function loadPacks() {
  const packs = {};
  const owners = {};
  for (const lang of LANGS) {
    packs[lang] = {};
    owners[lang] = new Map();
    const dir = path.join(LOCALES, lang);
    for (const f of fs.readdirSync(dir).filter((x) => x.endsWith('.json')).sort()) {
      const obj = JSON.parse(fs.readFileSync(path.join(dir, f), 'utf8'));
      packs[lang][f] = obj;
      for (const k of Object.keys(obj)) {
        if (!owners[lang].has(k)) owners[lang].set(k, []);
        owners[lang].get(k).push(f);
      }
    }
  }
  return { packs, owners };
}

const { packs, owners } = loadPacks();
const flat = Object.fromEntries(LANGS.map((l) => [l, Object.assign({}, ...Object.values(packs[l]))]));

/** Every literal key passed to t() / i18n.t() / <Trans i18nKey>, plus hardcoded CJK sites. */
function scanSources() {
  const keys = new Map();
  const cjk = [];
  const files = walk(SRC).filter((f) => /\.(ts|tsx)$/.test(f) && !f.startsWith(LOCALES + path.sep));
  for (const file of files) {
    const rel = path.relative(SRC, file).replace(/\\/g, '/');
    const sf = ts.createSourceFile(file, fs.readFileSync(file, 'utf8'), ts.ScriptTarget.Latest, true,
      file.endsWith('.tsx') ? ts.ScriptKind.TSX : ts.ScriptKind.TS);
    const at = (n) => `${rel}:${sf.getLineAndCharacterOfPosition(n.getStart(sf)).line + 1}`;
    const visit = (node) => {
      if (ts.isCallExpression(node)) {
        const ex = node.expression;
        const isT = (ts.isIdentifier(ex) && ex.text === 't') || (ts.isPropertyAccessExpression(ex) && ex.name.text === 't');
        const a = node.arguments[0];
        if (isT && a && (ts.isStringLiteral(a) || ts.isNoSubstitutionTemplateLiteral(a))) {
          if (!keys.has(a.text)) keys.set(a.text, []);
          keys.get(a.text).push(at(node));
        }
      }
      if (ts.isJsxAttribute(node) && node.name.getText(sf) === 'i18nKey' && node.initializer && ts.isStringLiteral(node.initializer)) {
        if (!keys.has(node.initializer.text)) keys.set(node.initializer.text, []);
        keys.get(node.initializer.text).push(at(node));
      }
      let text = null;
      if (ts.isStringLiteral(node) || ts.isNoSubstitutionTemplateLiteral(node)) text = node.text;
      else if (ts.isTemplateExpression(node)) text = node.head.text + node.templateSpans.map((s) => s.literal.text).join('');
      else if (ts.isJsxText(node)) text = node.text.trim();
      if (text && CJK.test(text)) cjk.push({ site: at(node), file: rel, text });
      ts.forEachChild(node, visit);
    };
    visit(sf);
  }
  return { keys, cjk, fileCount: files.length };
}

const { keys, cjk, fileCount } = scanSources();

test('locale packs cover the same files and keys in zh and en', () => {
  assert.deepEqual(Object.keys(packs.zh).sort(), Object.keys(packs.en).sort(), 'pack file sets differ');
  const zh = Object.keys(flat.zh).sort();
  const en = Object.keys(flat.en).sort();
  const onlyZh = zh.filter((k) => !(k in flat.en));
  const onlyEn = en.filter((k) => !(k in flat.zh));
  assert.deepEqual(onlyZh, [], `keys missing from en: ${onlyZh.join(', ')}`);
  assert.deepEqual(onlyEn, [], `keys missing from zh: ${onlyEn.join(', ')}`);
  assert.ok(zh.length > 2000, `expected a full catalog, got ${zh.length} keys`);
});

test('no key is defined twice across domain packs', () => {
  // i18n.ts merges packs with Object.assign, so a duplicate silently wins by import order.
  for (const lang of LANGS) {
    const dups = [...owners[lang]].filter(([, f]) => f.length > 1).map(([k, f]) => `${k} (${f.join(', ')})`);
    assert.deepEqual(dups, [], `duplicate ${lang} keys: ${dups.join('; ')}`);
  }
});

test('no key is defined twice inside one pack file', () => {
  // JSON.parse keeps the last occurrence, so the check above cannot see these.
  const dups = [];
  for (const lang of LANGS) {
    for (const [f, obj] of Object.entries(packs[lang])) {
      const raw = fs.readFileSync(path.join(LOCALES, lang, f), 'utf8');
      const seen = new Set();
      const expected = Object.keys(obj).length;
      let found = 0;
      for (const m of raw.matchAll(/^\s*"((?:[^"\\]|\\.)*)"\s*:/gm)) {
        found += 1;
        if (seen.has(m[1])) dups.push(`${lang}/${f}: ${m[1]}`);
        seen.add(m[1]);
      }
      assert.equal(found, expected, `${lang}/${f}: raw key count ${found} != parsed ${expected}`);
    }
  }
  assert.deepEqual(dups, [], `duplicate keys within a pack: ${dups.join('; ')}`);
});

test('no empty values and no untranslated en values', () => {
  const empty = Object.keys(flat.zh).filter((k) => !String(flat.zh[k]).trim() || !String(flat.en[k]).trim());
  assert.deepEqual(empty, [], `empty values: ${empty.join(', ')}`);
  const chineseEn = Object.keys(flat.en).filter((k) => CJK.test(String(flat.en[k])));
  assert.deepEqual(chineseEn, [], `en values still contain Chinese: ${chineseEn.join(', ')}`);
});

test('interpolation placeholders match between zh and en', () => {
  const ph = (s) => [...String(s).matchAll(/\{\{\s*([\w.]+)\s*(?:,[^}]*)?\}\}/g)].map((m) => m[1]).sort().join(',');
  const bad = Object.keys(flat.zh)
    .filter((k) => ph(flat.zh[k]) !== ph(flat.en[k]))
    .map((k) => `${k}: zh={${ph(flat.zh[k])}} en={${ph(flat.en[k])}}`);
  assert.deepEqual(bad, [], `placeholder mismatch: ${bad.join('; ')}`);
});

test('every key referenced by t() exists in both languages', () => {
  const missing = [...keys.keys()].filter((k) => !(k in flat.zh) || !(k in flat.en)).sort();
  const detail = missing.map((k) => `${k} <- ${keys.get(k).join(', ')}`);
  assert.deepEqual(missing, [], `${missing.length} referenced keys are undefined:\n${detail.join('\n')}`);
});

/**
 * Keys built at runtime (`t(\`prefix.${value}\`)`) are invisible to the static check
 * above. For every family whose value set is a closed list, assert full coverage —
 * this is how the trace column chooser shipped showing "trace.traceId" verbatim.
 */
const loadSrc = (rel) => {
  const url = new URL(`../src/${rel}`, import.meta.url);
  return srcRequire(url)(`./${path.basename(rel)}`);
};
const { ALL_COLUMNS, COLUMN_LABEL_KEYS } = loadSrc('features/trace/utils.ts');
const { HISTORY_RANGE_OPTIONS } = loadSrc('features/access-log/constants.ts');
const { WORKBENCH_TYPES } = loadSrc('features/routes/configuration.ts');

test('runtime-built key families are fully covered', () => {
  const missing = [];
  const need = (key) => {
    for (const lang of LANGS) if (!(key in flat[lang])) missing.push(`${lang}:${key}`);
  };

  for (const col of ALL_COLUMNS) {
    assert.ok(COLUMN_LABEL_KEYS[col], `trace column '${col}' has no entry in COLUMN_LABEL_KEYS`);
    need(COLUMN_LABEL_KEYS[col]);
  }
  for (const range of HISTORY_RANGE_OPTIONS) need(`access_log.time_${range}`);
  for (const type of WORKBENCH_TYPES) need(`routes.workbench.${type}`);
  for (const kind of ['edit', 'create']) {
    for (const flavour of ['ai', 'normal']) {
      need(`routes.${kind}_${flavour}`);
      need(`services.${kind}_${flavour}`);
    }
  }
  // SOURCE_TYPES in copilot/components/KnowledgeTab.tsx (module-private)
  for (const s of ['runbook', 'incident', 'doc', 'feedback']) need(`copilot.kb_type_${s}`);
  for (const s of ['small', 'medium', 'large', 'xlarge', 'custom']) need(`instance.spec_${s}`);
  for (const c of ['traffic', 'security', 'resilience', 'transform', 'observability', 'misc', 'ai']) need(`strategy.picker_category_${c}`);
  for (const p of ['Path', 'Host', 'Method', 'Header']) {
    need(`predicate.${p}.name`);
    need(`predicate.${p}.detail`);
  }
  // AiUsageResult.Status + settlement statuses written by TokenUsageCollectorFilter
  for (const s of ['CONFIRMED', 'ESTIMATED', 'UNKNOWN', 'NOT_BILLED', 'NOT_REQUIRED', 'LEGACY', 'PENDING', 'FAILED', 'APPLIED', 'RECONCILED']) {
    need(`tokenUsage.status.${s}`);
  }
  // Reasons added by gateway-admin TraceGraphAnalysis
  for (const r of ['INVALID_GATEWAY_HEADERS', 'NO_SPANS', 'GRAPH_LIMIT', 'INVALID_SPANS', 'MISSING_PARENTS',
    'DUPLICATE_SPANS', 'CYCLE', 'SUMMARY_OR_UNKNOWN_CAPTURE', 'UNFINISHED_ASYNC_SPANS',
    'SPAN_LIMIT_REACHED', 'MISSING_SPAN_IDS']) {
    need(`trace.integrity_${r}`);
  }
  for (const f of ['answer', 'reasoning', 'tool']) need(`routes.debug_frame_${f}`);

  assert.deepEqual(missing, [], `${missing.length} runtime-family keys undefined: ${missing.join(', ')}`);
});

test('no hardcoded Chinese user-visible text outside the allowlist', () => {
  const usedEntries = new Set();
  const leaks = [];
  for (const hit of cjk) {
    const entry = CJK_ALLOWLIST.find((a) => a.file === hit.file
      && ((a.texts || []).includes(hit.text) || (a.prefixes || []).some((p) => hit.text.startsWith(p))));
    if (entry) usedEntries.add(entry);
    else leaks.push(`${hit.site} ${JSON.stringify(hit.text.slice(0, 90))}`);
  }
  assert.deepEqual(leaks, [], `${leaks.length} hardcoded Chinese strings:\n${leaks.join('\n')}`);
  const stale = CJK_ALLOWLIST.filter((a) => !usedEntries.has(a)).map((a) => a.file);
  assert.deepEqual(stale, [], `stale allowlist entries: ${stale.join(', ')}`);
  assert.ok(fileCount > 200, `expected to scan the whole src tree, scanned ${fileCount}`);
});