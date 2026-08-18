/**
 * RoutesPage 纯函数工具集
 * 从 RoutesPage.tsx 中提取的、不依赖组件状态的纯函数
 */

// ============================================================================
// Predicate Args Parsing / Stringifying
// ============================================================================

/**
 * Parse predicate args string into structured object format
 * Handles Path, Host, Method, Header predicate types
 */
export const parsePredicateArgs = (type: string, args: unknown): Record<string, unknown> => {
  // Defensive check: args may be undefined or null
  if (!args || args === 'undefined' || args === 'null') {
    switch (type) {
      case 'Path':
        return { pattern: '/**' };
      case 'Host':
        return { patterns: '' };  // SCG expects "patterns" (plural)
      case 'Method':
        return { 'methods.0': 'GET' };  // Indexed key for GATHER_LIST
      case 'Header':
        return { header: '', regexp: '.*' };
      default:
        return { value: '' };
    }
  }

  // If args is already an object (from form components like Method Select), use it directly
  if (typeof args === 'object' && args !== null && !Array.isArray(args)) {
    return args as Record<string, unknown>;
  }

  // args is a string, parse it to object format
  const argsStr = String(args);

  switch (type) {
    case 'Path':
      return { pattern: argsStr || '/**' };
    case 'Host':
      // SCG expects "patterns" (plural) for Host predicate
      // Support both single pattern and comma-separated patterns
      return { patterns: argsStr };
    case 'Method':
      // Split by comma and use indexed keys for GATHER_LIST mode
      // Spring Cloud Gateway expects: {"methods.0": "GET", "methods.1": "POST"}
      const methods = argsStr.split(',').map(m => m.trim()).filter(m => m);
      const methodArgs: Record<string, string> = {};
      methods.forEach((m, i) => {
        methodArgs[`methods.${i}`] = m;
      });
      return methodArgs;
    case 'Header':
      // Use indexOf to handle regexp containing colons
      const colonIndex = argsStr.indexOf(':');
      if (colonIndex === -1) {
        return { header: argsStr, regexp: '.*' };
      }
      return { header: argsStr.substring(0, colonIndex), regexp: argsStr.substring(colonIndex + 1) || '.*' };
    default:
      return { value: argsStr };
  }
};

/**
 * Convert structured predicate args object back to display string
 * Used in edit mode to populate form fields
 */
export const stringifyPredicateArgs = (type: string, args: Record<string, unknown> | string | undefined): string => {
  if (!args) return '';
  if (typeof args === 'string') return args;
  switch (type) {
    case 'Path':
      return String(args.pattern || '/**');
    case 'Host':
      // SCG uses "patterns" (plural) - also support legacy "pattern" for backward compatibility
      return String(args.patterns || args.pattern || '');
    case 'Method':
      // Handle indexed keys format: methods.0=GET, methods.1=POST
      // Also handle legacy format: methods=["GET","POST"] or methods="GET,POST"
      if (Array.isArray(args.methods)) {
        return (args.methods as unknown[]).join(', ');
      }
      if (typeof args.methods === 'string') {
        return args.methods;
      }
      // Extract from indexed keys (methods.0, methods.1, etc.)
      const methodKeys = Object.keys(args).filter(k => k.startsWith('methods.'));
      if (methodKeys.length > 0) {
        const methods = methodKeys
          .sort((a, b) => parseInt(a.split('.')[1]) - parseInt(b.split('.')[1]))
          .map(k => args[k]);
        return methods.join(', ');
      }
      return '';
    case 'Header':
      // SCG uses "header" + "regexp" parameter names (not "name")
      return `${args.header || args.name || ''}:${args.regexp || '.*'}`;
    default:
      return String(args.value || '');
  }
};

// ============================================================================
// Filter Args Parsing / Stringifying
// ============================================================================

/**
 * Parse filter args string into structured object format
 * Handles StripPrefix, PrefixPath, Add/Set/Remove headers, RewritePath, etc.
 */
export const parseFilterArgs = (type: string, args: unknown): Record<string, unknown> => {
  // Defensive check: args may be undefined or null
  if (!args || args === 'undefined' || args === 'null') {
    switch (type) {
      case 'StripPrefix':
        return { parts: 1 };
      case 'PrefixPath':
        return { prefix: '' };
      case 'AddRequestHeader':
      case 'SetRequestHeader':
      case 'AddResponseHeader':
      case 'SetResponseHeader':
      case 'AddRequestParameter':
        return { name: '', value: '' };
      case 'RemoveRequestHeader':
      case 'RemoveResponseHeader':
      case 'RemoveRequestParameter':
        return { name: '' };
      case 'SetPath':
        return { template: '' };
      case 'RewritePath':
        return { regexp: '', replacement: '' };
      case 'SetStatus':
        return { status: '' };
      case 'RequestSize':
        return { maxSize: '' };
      case 'RedirectTo':
        return { status: '302', url: '' };
      default:
        return { value: '' };
    }
  }

  // If args is already an object, use it directly
  if (typeof args === 'object' && args !== null && !Array.isArray(args)) {
    return args as Record<string, unknown>;
  }

  const argsStr = String(args);

  switch (type) {
    case 'StripPrefix':
      return { parts: parseInt(argsStr) || 1 };
    case 'PrefixPath':
      return { prefix: argsStr };
    case 'AddRequestHeader':
    case 'SetRequestHeader':
    case 'AddResponseHeader':
    case 'SetResponseHeader':
      // Use indexOf to handle values containing colons (e.g., "name:value:with:colons")
      const colonIndex = argsStr.indexOf(':');
      if (colonIndex === -1) {
        return { name: argsStr, value: '' };
      }
      return { name: argsStr.substring(0, colonIndex), value: argsStr.substring(colonIndex + 1) };
    case 'RemoveRequestHeader':
    case 'RemoveResponseHeader':
      return { name: argsStr };
    case 'AddRequestParameter':
      // Use indexOf to handle values containing colons
      const paramColonIndex = argsStr.indexOf(':');
      if (paramColonIndex === -1) {
        return { name: argsStr, value: '' };
      }
      return { name: argsStr.substring(0, paramColonIndex), value: argsStr.substring(paramColonIndex + 1) };
    case 'RemoveRequestParameter':
      return { name: argsStr };
    case 'SetPath':
      return { template: argsStr };
    case 'RewritePath':
      // Use indexOf to handle replacements containing commas
      const commaIndex = argsStr.indexOf(',');
      if (commaIndex === -1) {
        return { regexp: argsStr, replacement: '' };
      }
      return { regexp: argsStr.substring(0, commaIndex), replacement: argsStr.substring(commaIndex + 1) };
    case 'SetStatus':
      return { status: argsStr };
    case 'RequestSize':
      return { maxSize: argsStr };
    case 'RedirectTo':
      // Use indexOf to handle URLs containing commas
      const redirectCommaIndex = argsStr.indexOf(',');
      if (redirectCommaIndex === -1) {
        return { status: argsStr, url: '' };
      }
      return { status: argsStr.substring(0, redirectCommaIndex), url: argsStr.substring(redirectCommaIndex + 1) };
    default:
      return { value: argsStr };
  }
};

/**
 * Convert structured filter args object back to display string
 * Used in edit mode to populate form fields
 */
export const stringifyFilterArgs = (type: string, args: Record<string, unknown> | string | undefined): string => {
  if (!args) return '';
  if (typeof args === 'string') return args;
  switch (type) {
    case 'StripPrefix':
      return String(args.parts || 1);
    case 'PrefixPath':
      return String(args.prefix || '');
    case 'AddRequestHeader':
    case 'SetRequestHeader':
    case 'AddResponseHeader':
    case 'SetResponseHeader':
      return `${args.name || ''}:${args.value || ''}`;
    case 'RemoveRequestHeader':
    case 'RemoveResponseHeader':
      return String(args.name || '');
    case 'AddRequestParameter':
      return `${args.name || ''}:${args.value || ''}`;
    case 'RemoveRequestParameter':
      return String(args.name || '');
    case 'SetPath':
      return String(args.template || '');
    case 'RewritePath':
      return `${args.regexp || ''},${args.replacement || ''}`;
    case 'SetStatus':
      return String(args.status || '');
    case 'RequestSize':
      return String(args.maxSize || args.value || '');
    case 'RedirectTo':
      return `${args.status || '302'},${args.url || ''}`;
    default:
      return String(args.value || '');
  }
};