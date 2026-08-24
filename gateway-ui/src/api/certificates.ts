/**
 * SSL certificate API.
 * Endpoints aligned with controller/SslCertificateController.java (/api/ssl).
 *
 * Parameter style notes (per backend signatures):
 * - POST /pem accepts a JSON body ({certName, domain, certContent, keyContent}).
 * - POST /keystore, PUT /{id} and PUT /{id}/enabled read @RequestParam —
 *   values MUST be sent as query params, not a JSON body.
 */
import httpClient, { RequestOptions } from './http';
import type { CertificateStats, SslCertificate } from '../types/certificate';

const BASE = '/api/ssl';

/** GET /api/ssl — optionally filtered by enabled / instance. */
export const listCertificates = (
  params?: { enabled?: boolean; instanceId?: string },
  options?: RequestOptions,
) => httpClient.get<SslCertificate[]>(BASE, {
  ...options,
  params: params as Record<string, unknown>,
});

/** GET /api/ssl/{id} */
export const getCertificateById = (id: number, options?: RequestOptions) =>
  httpClient.get<SslCertificate>(`${BASE}/${id}`, options);

/** GET /api/ssl/stats — Map with total/valid/expiringSoon/expired/expiringList. */
export const getCertificateStats = (
  params?: { instanceId?: string },
  options?: RequestOptions,
) => httpClient.get<CertificateStats>(`${BASE}/stats`, {
  ...options,
  params: params as Record<string, unknown>,
});

/** GET /api/ssl/expiring?days=30 */
export const getExpiringCertificates = (
  params?: { days?: number; instanceId?: string },
  options?: RequestOptions,
) => httpClient.get<SslCertificate[]>(`${BASE}/expiring`, {
  ...options,
  params: params as Record<string, unknown>,
});

/** POST /api/ssl/pem — JSON body with PEM cert + private key contents. */
export const uploadPemCertificate = (
  body: { certName: string; domain: string; certContent: string; keyContent: string },
  instanceId?: string,
  options?: RequestOptions,
) => httpClient.post<SslCertificate>(`${BASE}/pem`, body, {
  ...options,
  params: instanceId ? { instanceId } : undefined,
});

/**
 * POST /api/ssl/keystore — backend reads @RequestParam, so everything goes
 * through query params (keystoreContent is the base64-encoded store).
 */
export const uploadKeystore = (
  body: { certName: string; domain: string; certType: string; keystoreContent: string; password: string },
  instanceId?: string,
  options?: RequestOptions,
) => httpClient.post<SslCertificate>(`${BASE}/keystore`, undefined, {
  ...options,
  params: { ...body, ...(instanceId ? { instanceId } : {}) },
});

/** PUT /api/ssl/{id} — @RequestParam update of name / PEM contents. */
export const updateCertificate = (
  id: number,
  body: { certName?: string; certContent?: string; keyContent?: string },
  options?: RequestOptions,
) => httpClient.put<SslCertificate>(`${BASE}/${id}`, undefined, {
  ...options,
  params: body as Record<string, unknown>,
});

/** PUT /api/ssl/{id}/enabled?enabled= */
export const setCertificateEnabled = (
  id: number,
  enabled: boolean,
  options?: RequestOptions,
) => httpClient.put<SslCertificate>(`${BASE}/${id}/enabled`, undefined, {
  ...options,
  params: { enabled },
});

/** DELETE /api/ssl/{id} */
export const deleteCertificate = (id: number, options?: RequestOptions) =>
  httpClient.delete<void>(`${BASE}/${id}`, options);

/** POST /api/ssl/refresh-status — recomputes expiry statuses. */
export const refreshCertificateStatuses = (options?: RequestOptions) =>
  httpClient.post<void>(`${BASE}/refresh-status`, undefined, options);
