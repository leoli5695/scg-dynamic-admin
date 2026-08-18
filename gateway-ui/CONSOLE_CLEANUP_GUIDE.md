# Gateway UI - Console.log 清理指南

## 现状分析

项目中存在大量 `console.log` 语句，主要分布在：
- `src/utils/api.ts` - 5处
- `src/pages/AuditLogsPage.tsx` - 10+处
- 其他页面共30+处

## 问题

1. **生产环境信息泄露** - 可能暴露敏感数据（token、用户信息）
2. **性能影响** - 频繁的console输出会影响浏览器性能
3. **代码质量** - 生产代码不应包含调试语句

## 解决方案

### 方案1: Vite构建时自动移除（推荐）

在 `vite.config.ts` 中添加配置：

```typescript
import { defineConfig } from 'vite';

export default defineConfig({
  build: {
    minify: 'terser',
    terserOptions: {
      compress: {
        drop_console: true,  // 生产环境移除所有console
        drop_debugger: true, // 移除debugger语句
      }
    }
  }
});
```

### 方案2: 使用环境变量控制

创建统一的日志工具 `src/utils/logger.ts`:

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

### 方案3: ESLint规则强制检查

在 `.eslintrc.js` 或 `eslint.config.js` 中添加：

```javascript
module.exports = {
  rules: {
    'no-console': process.env.NODE_ENV === 'production' ? 'error' : 'warn',
  }
};
```

## 需要清理的文件清单

| 文件 | console数量 | 优先级 |
|------|-----------|--------|
| src/pages/AuditLogsPage.tsx | 10+ | P0 |
| src/utils/api.ts | 5 | P0 |
| src/pages/KubernetesPage.tsx | 8 | P1 |
| src/pages/TracePage.tsx | 6 | P1 |
| src/pages/RoutesPage.tsx | 4 | P2 |

## 执行计划

1. **立即**: 配置Vite自动移除（方案1）
2. **本周内**: 替换关键文件的console为logger（方案2）
3. **本月内**: 添加ESLint规则防止新增（方案3）

## 例外情况

以下场景允许保留console：
- 错误处理中的 `console.error`（用于捕获未处理的异常）
- 警告信息 `console.warn`（重要的运行时警告）
- 开发专用工具函数（用 `if (import.meta.env.DEV)` 包裹）
