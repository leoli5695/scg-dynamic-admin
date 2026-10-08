/**
 * Data-plane JWT revocation API.
 * Endpoints aligned with controller/GatewayTokenRevocationController.java
 * (/api/gateway/jwt-revocations).
 *
 * The blacklist lives in the Redis shared by the gateway deployment, so these
 * calls are deployment-wide rather than scoped to one instance.
 */
import httpClient, { RequestOptions } from './http';

const BASE = '/api/gateway/jwt-revocations';

/** Identifiers written to the blacklist (jti and/or SHA-256 of the token). */
export interface RevocationResult {
  ids: string[];
  ttlSeconds: number;
}

export interface RevocationSwitch {
  enabled: boolean;
}

export interface RevocationRequest {
  /** Raw JWT. Never sent as a query parameter so it stays out of access logs. */
  token?: string;
  /** jti claim, for tokens the operator only knows by identifier. */
  jti?: string;
  /** Lifetime in seconds; derived from the token's exp when omitted. */
  ttlSeconds?: number;
}

/** POST /api/gateway/jwt-revocations — blacklist a token. */
export const revokeJwtToken = (body: RevocationRequest, options?: RequestOptions) =>
  httpClient.post<RevocationResult>(BASE, body, options);

/** POST /api/gateway/jwt-revocations/lift — remove a blacklist entry early. */
export const liftJwtRevocation = (body: RevocationRequest, options?: RequestOptions) =>
  httpClient.post<RevocationResult>(`${BASE}/lift`, body, options);

/** GET /api/gateway/jwt-revocations/enabled */
export const getRevocationSwitch = (options?: RequestOptions) =>
  httpClient.get<RevocationSwitch>(`${BASE}/enabled`, options);

/** PUT /api/gateway/jwt-revocations/enabled */
export const setRevocationSwitch = (enabled: boolean, options?: RequestOptions) =>
  httpClient.put<RevocationSwitch>(`${BASE}/enabled`, { enabled }, options);
