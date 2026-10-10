/**
 * Provider credential API for the AI proxy.
 * Endpoints aligned with gateway-admin controller/ProviderCredentialController.java
 * (/api/provider-credentials). Keys are write-only: the backend encrypts them and
 * returns only a masked view (keyHint), never the plaintext.
 */
import httpClient, { RequestOptions } from './http';

const BASE = '/api/provider-credentials';

/** Masked credential view returned by the backend (no plaintext key). */
export interface ProviderCredential {
  credentialRef: string;
  name: string;
  provider: string;
  keyHint?: string;
  tenantId?: string | null;
  enabled?: boolean;
  createdAt?: string;
  updatedAt?: string;
}

/** Create/update request body; apiKey carries the plaintext key on write only. */
export interface ProviderCredentialRequest {
  credentialRef?: string;
  name: string;
  provider: string;
  apiKey?: string;
  tenantId?: string;
  enabled?: boolean;
}

/** GET /api/provider-credentials — masked list. */
export const listProviderCredentials = (options?: RequestOptions) =>
  httpClient.get<ProviderCredential[]>(BASE, options);

/** POST /api/provider-credentials — create (apiKey encrypted at rest). */
export const createProviderCredential = (
  body: ProviderCredentialRequest,
  options?: RequestOptions,
) => httpClient.post<ProviderCredential>(BASE, body, options);

/** PUT /api/provider-credentials/{ref} — update / rotate key. */
export const updateProviderCredential = (
  ref: string,
  body: ProviderCredentialRequest,
  options?: RequestOptions,
) => httpClient.put<ProviderCredential>(`${BASE}/${encodeURIComponent(ref)}`, body, options);

/** DELETE /api/provider-credentials/{ref} */
export const deleteProviderCredential = (ref: string, options?: RequestOptions) =>
  httpClient.delete<void>(`${BASE}/${encodeURIComponent(ref)}`, options);
