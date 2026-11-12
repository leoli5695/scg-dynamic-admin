import { readFileSync, existsSync } from 'node:fs';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import ts from 'typescript';

/**
 * The page harnesses transpile one src module and stub the dependencies they
 * assert on. Anything else they import (pure constants, helpers) has to load for
 * real, resolved the way the bundler resolves it — otherwise adding an import to
 * a page breaks every harness that renders it.
 */
export function srcRequire(entryUrl) {
  const entryFile = fileURLToPath(entryUrl);
  const cache = new Map();

  const resolve = (fromFile, spec) => {
    const base = path.resolve(path.dirname(fromFile), spec);
    for (const c of [base, `${base}.ts`, `${base}.tsx`, path.join(base, 'index.ts'), path.join(base, 'index.tsx')]) {
      if (existsSync(c)) return c;
    }
    throw new Error(`Cannot resolve '${spec}' from ${fromFile}`);
  };

  const load = (file) => {
    if (cache.has(file)) return cache.get(file);
    if (file.endsWith('.css')) return {};
    const compiled = ts.transpileModule(readFileSync(file, 'utf8'), {
      compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX, esModuleInterop: true },
    }).outputText;
    const exports = {};
    cache.set(file, exports);
    const localRequire = createRequire(file);
    new Function('require', 'exports', compiled)(
      (id) => (id.startsWith('.') ? load(resolve(file, id)) : localRequire(id)),
      exports,
    );
    return exports;
  };

  return (spec) => (spec.startsWith('.') ? load(resolve(entryFile, spec)) : createRequire(entryFile)(spec));
}