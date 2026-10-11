/**
 * Logger Utility
 * Environment-aware logging: debug/info only in development, warn/error always
 * Production builds use Vite's terser to drop console.log/debug/trace
 */

const isDev = import.meta.env.DEV;

const logger = {
  debug: (...args: unknown[]): void => {
    if (isDev) {
      console.log('[DEBUG]', ...args);
    }
  },

  info: (...args: unknown[]): void => {
    if (isDev) {
      console.info('[INFO]', ...args);
    }
  },

  warn: (...args: unknown[]): void => {
    console.warn('[WARN]', ...args);
  },

  error: (...args: unknown[]): void => {
    console.error('[ERROR]', ...args);
  },
};

export default logger;
