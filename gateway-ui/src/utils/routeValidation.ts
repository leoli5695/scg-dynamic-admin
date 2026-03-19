/**
 * Route validation utilities for frontend.
 * Validates route fields before submitting to backend.
 * 
 * Validation rules are consistent with backend RouteValidator.
 */

// Valid URI schemes for gateway routes
const VALID_SCHEMES = ['http', 'https', 'lb', 'static'];

// Route ID pattern: alphanumeric, hyphens, underscores
const ROUTE_ID_PATTERN = /^[a-zA-Z0-9_-]+$/;

// Valid predicate names
const VALID_PREDICATES = [
  'Path', 'Host', 'Method', 'Header', 'Query', 'Cookie', 'After', 'Before',
  'Between', 'RemoteAddr', 'Weight'
];

// Valid filter names
const VALID_FILTERS = [
  'AddRequestHeader', 'AddRequestParameter', 'AddResponseHeader',
  'DedupeResponseHeader', 'PrefixPath', 'RedirectTo', 'RemoveRequestHeader',
  'RemoveResponseHeader', 'RewritePath', 'SetPath', 'SetRequestHeader',
  'SetResponseHeader', 'SetStatus', 'StripPrefix', 'RequestRateLimiter',
  'CircuitBreaker', 'Retry'
];

export interface ValidationError {
  field: string;
  message: string;
}

export interface RouteFormData {
  id: string;
  uri: string;
  order?: number;
  predicates?: Array<{ name: string; args?: any }>;
  filters?: Array<{ name: string; args?: any }>;
  metadata?: any;
}

/**
 * Validate route form data
 * @param route Route form data to validate
 * @returns Array of validation errors, empty if valid
 */
export function validateRoute(route: RouteFormData | null): ValidationError[] {
  const errors: ValidationError[] = [];

  if (!route) {
    errors.push({ field: 'root', message: 'Route data is required' });
    return errors;
  }

  // Validate route ID
  validateRouteId(route.id, errors);

  // Validate URI
  validateUri(route.uri, errors);

  // Validate order
  validateOrder(route.order, errors);

  // Validate predicates
  validatePredicates(route.predicates, errors);

  // Validate filters
  validateFilters(route.filters, errors);

  return errors;
}

/**
 * Validate and throw if invalid
 */
export function validateRouteAndThrow(route: RouteFormData | null): void {
  const errors = validateRoute(route);
  if (errors.length > 0) {
    throw new Error(errors.map(e => e.message).join('; '));
  }
}

function validateRouteId(id: string | undefined, errors: ValidationError[]): void {
  if (!id || id.trim() === '') {
    errors.push({ field: 'id', message: 'Route ID is required' });
    return;
  }

  if (id.length > 100) {
    errors.push({ field: 'id', message: 'Route ID must not exceed 100 characters' });
  }

  if (!ROUTE_ID_PATTERN.test(id)) {
    errors.push({ 
      field: 'id', 
      message: 'Route ID must contain only alphanumeric characters, hyphens, and underscores' 
    });
  }
}

function validateUri(uri: string | undefined, errors: ValidationError[]): void {
  if (!uri || uri.trim() === '') {
    errors.push({ field: 'uri', message: 'URI is required' });
    return;
  }

  try {
    const parsedUri = new URL(uri);
    const scheme = parsedUri.protocol.replace(':', '');

    if (!VALID_SCHEMES.includes(scheme.toLowerCase())) {
      errors.push({ 
        field: 'uri', 
        message: `URI scheme must be one of: ${VALID_SCHEMES.join(', ')}. Got: ${scheme}` 
      });
    }

    // For static:// protocol, validate service name
    if (scheme.toLowerCase() === 'static') {
      const host = parsedUri.hostname;
      if (!host || host.trim() === '') {
        errors.push({ 
          field: 'uri', 
          message: 'Static URI must have a service name (e.g., static://my-service)' 
        });
      }
    }

    // For http(s):// protocol, validate host and port
    if (scheme.toLowerCase() === 'http' || scheme.toLowerCase() === 'https') {
      const host = parsedUri.hostname;
      if (!host || host.trim() === '') {
        errors.push({ field: 'uri', message: 'HTTP/HTTPS URI must have a valid host' });
      }

      const port = parsedUri.port;
      if (port) {
        const portNum = parseInt(port, 10);
        if (portNum < 1 || portNum > 65535) {
          errors.push({ field: 'uri', message: 'Port must be between 1 and 65535' });
        }
      }
    }

    // For lb:// protocol, validate service name
    if (scheme.toLowerCase() === 'lb') {
      const host = parsedUri.hostname;
      if (!host || host.trim() === '') {
        errors.push({ 
          field: 'uri', 
          message: 'Load balancer URI must have a service name (e.g., lb://my-service)' 
        });
      }
    }

  } catch (e) {
    errors.push({ field: 'uri', message: 'Invalid URI format' });
  }
}

function validateOrder(order: number | undefined, errors: ValidationError[]): void {
  if (order !== undefined && order !== null) {
    if (order < -10000 || order > 10000) {
      errors.push({ field: 'order', message: 'Route order should be between -10000 and 10000' });
    }
  }
}

function validatePredicates(
  predicates: Array<{ name: string; args?: any }> | undefined,
  errors: ValidationError[]
): void {
  if (!predicates || predicates.length === 0) {
    errors.push({ field: 'predicates', message: 'At least one predicate is required' });
    return;
  }

  let hasPathPredicate = false;

  predicates.forEach((predicate, index) => {
    if (!predicate) {
      errors.push({ field: `predicates[${index}]`, message: `Predicate at index ${index} is null` });
      return;
    }

    const name = predicate.name;
    if (!name || name.trim() === '') {
      errors.push({ field: `predicates[${index}].name`, message: `Predicate at index ${index} has no name` });
      return;
    }

    // Check if it's a Path predicate
    if (name === 'Path') {
      hasPathPredicate = true;
      
      // Validate path pattern
      const args = predicate.args;
      let pattern = args?.pattern || args;
      if (typeof pattern === 'string') {
        if (!pattern.startsWith('/')) {
          errors.push({ 
            field: `predicates[${index}].args`, 
            message: `Path pattern must start with '/': ${pattern}` 
          });
        }
      }
    }

    // Warn about unknown predicates (as info, not error)
    if (!VALID_PREDICATES.includes(name)) {
      console.info(`Predicate '${name}' is not a standard Spring Cloud Gateway predicate`);
    }
  });

  // Require at least one Path predicate for proper routing
  if (!hasPathPredicate) {
    errors.push({ 
      field: 'predicates', 
      message: 'At least one Path predicate is required for proper route matching' 
    });
  }
}

function validateFilters(
  filters: Array<{ name: string; args?: any }> | undefined,
  errors: ValidationError[]
): void {
  if (!filters) {
    return;
  }

  filters.forEach((filter, index) => {
    if (!filter) {
      errors.push({ field: `filters[${index}]`, message: `Filter at index ${index} is null` });
      return;
    }

    const name = filter.name;
    if (!name || name.trim() === '') {
      errors.push({ field: `filters[${index}].name`, message: `Filter at index ${index} has no name` });
      return;
    }

    // Validate specific filters
    if (name === 'StripPrefix') {
      const parts = filter.args?.parts;
      if (parts !== undefined) {
        const partsNum = parseInt(parts, 10);
        if (isNaN(partsNum) || partsNum < 0) {
          errors.push({ 
            field: `filters[${index}].args.parts`, 
            message: "StripPrefix 'parts' must be a non-negative integer" 
          });
        }
      }
    }

    if (name === 'SetStatus') {
      const status = filter.args?.status;
      if (status !== undefined) {
        const statusNum = parseInt(status, 10);
        if (!isNaN(statusNum) && (statusNum < 100 || statusNum > 599)) {
          errors.push({ 
            field: `filters[${index}].args.status`, 
            message: "SetStatus 'status' must be a valid HTTP status code (100-599)" 
          });
        }
      }
    }

    // Warn about unknown filters
    if (!VALID_FILTERS.includes(name)) {
      console.info(`Filter '${name}' is not a standard Spring Cloud Gateway filter`);
    }
  });
}

/**
 * Format validation errors for display
 */
export function formatValidationErrors(errors: ValidationError[]): string {
  return errors.map(e => `${e.field}: ${e.message}`).join('\n');
}