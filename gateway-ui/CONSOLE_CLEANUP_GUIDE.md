# Gateway UI - Console.log 清理指南

> **状态（截至 2026-10-28）**：本文是一次性清理计划，主体**已落地**——
> 方案1（构建期剥离）与方案2（统一 logger）**均已实现**，方案3（ESLint `no-console`）**仍未做**。
> 本文不随代码更新，下方"现状分析 / 需要清理的文件清单 / 执行计划"中的文件路径与数量是计划撰写时的旧快照，
> 现已全部失效（`src/pages/`、`src/utils/api.ts` 均不存在）。请按各节标注的实际状态阅读。

## 现状分析

> ⚠️ **本节已过期。** 撰写时列出的文件在重构后都不存在了：
> - `src/utils/api.ts` → 不存在（API 层现为 `src/api/*.ts` + `src/api/http.ts`；`src/utils/` 只有
>   `antdStatic.ts`、`instance.ts`、`logger.ts`、`serverTime.ts`）
> - `src/pages/AuditLogsPage.tsx`、`KubernetesPage.tsx`、`TracePage.tsx`、`RoutesPage.tsx` → 不存在
>   （**已无 `src/pages/` 目录**，页面按域拆分到 `src/features/**`）
>
> **实测现状**：`git grep -c "console.log" -- "src/**/*.ts" "src/**/*.tsx"` 只命中 **1 个文件**
> （`src/utils/logger.ts`，2 处，且都被 `import.meta.env.DEV` 守卫包裹）。清理工作已完成。

项目中曾存在大量 `console.log` 语句，主要分布在：
- `src/utils/api.ts` - 5处（文件已不存在）
- `src/pages/AuditLogsPage.tsx` - 10+处（文件已不存在）
- 其他页面共30+处（已清理）

## 问题

1. **生产环境信息泄露** - 可能暴露敏感数据（token、用户信息）
2. **性能影响** - 频繁的console输出会影响浏览器性能
3. **代码质量** - 生产代码不应包含调试语句

## 解决方案

### 方案1: Vite构建时自动移除（✅ 已实施）

> **★ 不要用 `drop_console: true`。** 它会连带删掉 `console.error` / `console.warn`，
> 包括 `logger.error`（`src/utils/logger.ts` 自己的契约写着"warn/error 恒输出"）和
> `ErrorBoundary.componentDidCatch`。结果就是生产包在 DevTools 里**一条错误都看不到**——
> 对单人运维的部署来说是最糟的结局。`vite.config.ts` 里保留着一段 `FIX:` 注释记录这次回退原因。
>
> 正确做法是用 `pure_funcs` **点名**剥离噪音级别，保留 `warn`/`error`。

`vite.config.ts` 中的**实际现行配置**（`build` 段末尾）：

```typescript
export default defineConfig({
  build: {
    // ...
    // Production optimization: strip noisy console output but KEEP warn/error.
    // FIX: this used `drop_console: true`, which removes EVERY console.* call - including
    // logger.error (src/utils/logger.ts, whose own contract says "warn/error always") and
    // ErrorBoundary.componentDidCatch. The result was a production bundle where DevTools showed
    // no error at all, which is the worst possible outcome for a single-operator deployment.
    minify: 'terser',
    terserOptions: {
      compress: {
        drop_debugger: true, // Remove debugger statements
        pure_funcs: ['console.log', 'console.debug', 'console.trace', 'console.info'],
      }
    },
  },
});
```

### 方案2: 使用环境变量控制（✅ 已实施）

> `src/utils/logger.ts` **已存在**，实现与下方示例几乎逐字相同
> （`isDev` 守卫 `debug`/`info`，`warn`/`error` 恒输出；差异只是参数类型用了 `unknown[]` 而非 `any[]`）。
> 该文件头注释也写明："Production builds use Vite's terser to drop console.log/debug/trace"，
> 即方案1 与方案2 是配合生效的。**不要重复创建这个文件。**

统一的日志工具 `src/utils/logger.ts`:

```typescript
const isDev = import.meta.env.DEV;

const logger = {
  debug: (...args: any[]) => {
    if (isDev) {
      console.log('[DEBUG]', ...args);
    }
  },
  info: (...args: any[]) => {
    if (isDev) {
      console.info('[INFO]', ...args);
    }
  },
  warn: (...args: any[]) => {
    console.warn('[WARN]', ...args); // 警告始终输出
  },
  error: (...args: any[]) => {
    console.error('[ERROR]', ...args); // 错误始终输出
  },
};

export default logger;
```

替换所有 `console.log` 为 `logger.debug()`。

### 方案3: ESLint规则强制检查（❌ 未实施）

> 核实为真：`eslint.config.js` 中确实**没有** `no-console` 规则。这一项仍是待办。
> 注意本项目用的是 flat config（`eslint.config.js`），下面的 `module.exports` 写法需要改写成
> `rules: { 'no-console': ... }` 放进 flat config 的对象里。

在 `eslint.config.js` 中添加：

```javascript
module.exports = {
  rules: {
    'no-console': process.env.NODE_ENV === 'production' ? 'error' : 'warn',
  }
};
```

## 需要清理的文件清单

> ⚠️ **本表已完全失效**：下列 5 个文件在 HEAD 中**一个都不存在**（无 `src/pages/` 目录，
> 无 `src/utils/api.ts`）。全 `src` 树目前只剩 `src/utils/logger.ts` 一个文件含 `console.log`，
> 且已被 `isDev` 守卫。**没有剩余清理工作。**

| 文件（均已不存在） | console数量 | 优先级 |
|------|-----------|--------|
| ~~src/pages/AuditLogsPage.tsx~~ | 10+ | P0 |
| ~~src/utils/api.ts~~ | 5 | P0 |
| ~~src/pages/KubernetesPage.tsx~~ | 8 | P1 |
| ~~src/pages/TracePage.tsx~~ | 6 | P1 |
| ~~src/pages/RoutesPage.tsx~~ | 4 | P2 |

## 执行计划

1. ~~**立即**: 配置Vite自动移除（方案1）~~ → **已完成**（用 `pure_funcs`，不是 `drop_console`）
2. ~~**本周内**: 替换关键文件的console为logger（方案2）~~ → **已完成**（`src/utils/logger.ts`）
3. **待办**: 添加ESLint规则防止新增（方案3）

## 例外情况

以下场景允许保留console：
- 错误处理中的 `console.error`（用于捕获未处理的异常）
- 警告信息 `console.warn`（重要的运行时警告）
- 开发专用工具函数（用 `if (import.meta.env.DEV)` 包裹）
