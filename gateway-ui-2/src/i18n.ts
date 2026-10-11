import { createI18n } from 'vue-i18n'

/**
 * Domain-split language packs live at locales/<lang>/<domain>.json with FLAT keys
 * (e.g. "common.submit", "login.chip.ha"). We merge every pack of a language into
 * one flat object and resolve keys LITERALLY via `messageResolver` — this mirrors
 * i18next semantics exactly (i18next treats dotted keys as literal strings, so a
 * leaf key may safely coexist with longer keys that share it as a prefix).
 *
 * NOTE: vue-i18n's `flatJson` option is NOT used: it nests keys by "." and, on a
 * prefix collision (a leaf that is also a branch), silently DROPS the longer key
 * ("Ignore object flatten" warnings). The literal resolver avoids that data loss.
 *
 * The packs were authored for i18next, whose interpolation syntax is {{name}} and
 * whose braces are otherwise literal. vue-i18n uses {name} for interpolation and
 * treats {, }, @, | as special. `adaptValue` bridges the two:
 *   1. {{word}}            -> protected token (a real interpolation)
 *   2. any remaining { or } -> vue-i18n literal escape {'{'} / {'}'}
 *   3. token                -> {word}
 * The locale set contains no @ or | (verified), so only braces need handling.
 */
const modules = import.meta.glob('./locales/*/*.json', { eager: true }) as Record<
  string,
  { default: Record<string, string> }
>

const OPEN = '\u0000'
const CLOSE = '\u0001'

function adaptValue(value: string): string {
  if (typeof value !== 'string') return value
  // 1. Protect real i18next interpolations {{word}} (simple identifiers only).
  let out = value.replace(/\{\{(\w+)\}\}/g, (_m, name: string) => `${OPEN}${name}${CLOSE}`)
  // 2. Escape any remaining literal braces so vue-i18n renders them verbatim.
  out = out.replace(/\{/g, "{'{'}").replace(/\}/g, "{'}'}")
  // 3. Restore protected interpolations as vue-i18n named params.
  out = out.replace(new RegExp(`${OPEN}(\\w+)${CLOSE}`, 'g'), '{\$1}')
  return out
}

function buildMessages(): Record<string, Record<string, string>> {
  const messages: Record<string, Record<string, string>> = {}
  for (const path in modules) {
    const m = path.match(/\.\/locales\/([^/]+)\/[^/]+\.json$/)
    if (!m) continue
    const lang = m[1]
    if (!messages[lang]) messages[lang] = {}
    const pack = modules[path].default
    for (const key in pack) {
      messages[lang][key] = adaptValue(pack[key])
    }
  }
  return messages
}

const STORAGE_KEY = 'i18nextLng'

/** Detect the initial locale: persisted choice first, then the browser language. */
function detectLocale(): string {
  const stored = typeof localStorage !== 'undefined' ? localStorage.getItem(STORAGE_KEY) : null
  if (stored) return stored.startsWith('zh') ? 'zh' : 'en'
  const nav = typeof navigator !== 'undefined' ? navigator.language || 'en' : 'en'
  return nav.startsWith('zh') ? 'zh' : 'en'
}

const i18n = createI18n({
  legacy: false,
  globalInjection: true,
  locale: detectLocale(),
  fallbackLocale: 'en',
  missingWarn: false,
  fallbackWarn: false,
  messages: buildMessages(),
  // Resolve dotted keys as literal flat strings (i18next semantics). Falls back to
  // null so vue-i18n applies its missing-key / fallback-locale handling.
  messageResolver: (obj, path) => {
    if (obj != null && typeof obj === 'object' && Object.prototype.hasOwnProperty.call(obj, path)) {
      return (obj as Record<string, string>)[path]
    }
    return null
  },
})

export default i18n
