/**
 * Auth policy API.
 * Endpoints aligned with controller/AuthPolicyController.java (/api/auth).
 *
 * instanceId handling (per backend signatures):
 * - GET/POST /policies and GET /policies/{id} accept an optional instanceId.
 * - PUT/DELETE/enable/disable do NOT accept instanceId.
 * - GET /bindings has no instance filter at all — it returns bindings of
 *   every instance, so callers must filter by instanceId client-side.
 */
import httpClient, { RequestOptions } from './http';
import type {
  AuthPolicyDefinition,
  AuthUsageExample,
  BatchBindRequest,
  BindingCount,
  CreateBindingRequest,
  RouteAuthBinding,
} from '../types/authPolicy';

const BASE = '/api/auth';

// ==================== Policy CRUD ====================

/** GET /api/auth/policies — optionally filtered by gateway instance UUID. */
export const listAuthPolicies = (
  params?: { instanceId?: string },
  options?: RequestOptions,
) => httpClient.get<AuthPolicyDefinition[]>(`${BASE}/policies`, {
  ...options,
  params: params as Record<string, unknown>,
});

/** GET /api/auth/policies/{policyId} */
export const getAuthPolicy = (
  policyId: string,
  instanceId?: string,
  options?: RequestOptions,
) => httpClient.get<AuthPolicyDefinition>(`${BASE}/policies/${encodeURIComponent(policyId)}`, {
  ...options,
  params: instanceId ? { instanceId } : undefined,
});

/** POST /api/auth/policies — body follows model/AuthPolicyDefinition. */
export const createAuthPolicy = (
  body: AuthPolicyDefinition,
  instanceId?: string,
  options?: RequestOptions,
) => httpClient.post<AuthPolicyDefinition>(`${BASE}/policies`, body, {
  ...options,
  params: instanceId ? { instanceId } : undefined,
});

/** PUT /api/auth/policies/{policyId} — backend replaces the whole config. */
export const updateAuthPolicy = (
  policyId: string,
  body: AuthPolicyDefinition,
  options?: RequestOptions,
) => httpClient.put<AuthPolicyDefinition>(
  `${BASE}/policies/${encodeURIComponent(policyId)}`,
  body,
  options,
);

/** DELETE /api/auth/policies/{policyId} */
export const deleteAuthPolicy = (policyId: string, options?: RequestOptions) =>
  httpClient.delete<void>(`${BASE}/policies/${encodeURIComponent(policyId)}`, options);

/** POST /api/auth/policies/{policyId}/enable */
export const enableAuthPolicy = (policyId: string, options?: RequestOptions) =>
  httpClient.post<void>(`${BASE}/policies/${encodeURIComponent(policyId)}/enable`, undefined, options);

/** POST /api/auth/policies/{policyId}/disable */
export const disableAuthPolicy = (policyId: string, options?: RequestOptions) =>
  httpClient.post<void>(`${BASE}/policies/${encodeURIComponent(policyId)}/disable`, undefined, options);

/** GET /api/auth/policies/{policyId}/usage-example — Map by auth type. */
export const getAuthPolicyUsageExample = (policyId: string, options?: RequestOptions) =>
  httpClient.get<AuthUsageExample>(
    `${BASE}/policies/${encodeURIComponent(policyId)}/usage-example`,
    options,
  );

// ==================== Route bindings ====================

/**
 * GET /api/auth/bindings — returns bindings of ALL instances; filter by
 * instanceId on the client side.
 */
export const listAuthBindings = (options?: RequestOptions) =>
  httpClient.get<RouteAuthBinding[]>(`${BASE}/bindings`, options);

/** GET /api/auth/bindings/policy/{policyId} */
export const listBindingsForPolicy = (policyId: string, options?: RequestOptions) =>
  httpClient.get<RouteAuthBinding[]>(
    `${BASE}/bindings/policy/${encodeURIComponent(policyId)}`,
    options,
  );

/** POST /api/auth/bindings — body { policyId, routeId, priority? }. */
export const createAuthBinding = (
  body: CreateBindingRequest,
  options?: RequestOptions,
) => httpClient.post<RouteAuthBinding>(`${BASE}/bindings`, body, options);

/** POST /api/auth/bindings/batch — body { routeId, policyIds }. */
export const batchBindAuthPolicies = (
  body: BatchBindRequest,
  options?: RequestOptions,
) => httpClient.post<RouteAuthBinding[]>(`${BASE}/bindings/batch`, body, options);

/** DELETE /api/auth/bindings/{bindingId} */
export const deleteAuthBinding = (bindingId: string, options?: RequestOptions) =>
  httpClient.delete<void>(`${BASE}/bindings/${encodeURIComponent(bindingId)}`, options);

/** POST /api/auth/bindings/{bindingId}/enable */
export const enableAuthBinding = (bindingId: string, options?: RequestOptions) =>
  httpClient.post<void>(`${BASE}/bindings/${encodeURIComponent(bindingId)}/enable`, undefined, options);

/** POST /api/auth/bindings/{bindingId}/disable */
export const disableAuthBinding = (bindingId: string, options?: RequestOptions) =>
  httpClient.post<void>(`${BASE}/bindings/${encodeURIComponent(bindingId)}/disable`, undefined, options);

/** GET /api/auth/policies/{policyId}/bindings/count */
export const countAuthBindings = (policyId: string, options?: RequestOptions) =>
  httpClient.get<BindingCount>(
    `${BASE}/policies/${encodeURIComponent(policyId)}/bindings/count`,
    options,
  );
