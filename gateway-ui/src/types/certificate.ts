/**
 * SSL certificate domain types.
 *
 * Field-aligned with backend sources of truth:
 * - model/SslCertificate.java (entity; keyContent/keystoreContent/
 *   keystorePassword are @JsonProperty WRITE_ONLY — never in responses)
 * - controller/SslCertificateController.java (/api/ssl)
 * - SslCertificateService.getCertificateStats (stats Map keys)
 */

/** SslCertificate.status values (computed from validTo). */
export type CertificateStatus = 'VALID' | 'EXPIRING_SOON' | 'EXPIRED';

/** SslCertificate.certType values. */
export type CertificateType = 'PEM' | 'JKS' | 'P12';

/** model/SslCertificate as returned by GET endpoints. */
export interface SslCertificate {
  /** Auto-increment DB id — the identifier used by all endpoints. */
  id: number;
  /** Gateway instance UUID for configuration isolation. */
  instanceId?: string;
  certName: string;
  /** Domain / SNI this certificate serves. */
  domain: string;
  /** Backend default "PEM". */
  certType?: string;
  certContent?: string;
  /** WRITE_ONLY on the backend; only ever sent, never received. */
  keyContent?: string;
  /** WRITE_ONLY; base64 for JKS/P12. */
  keystoreContent?: string;
  /** WRITE_ONLY. */
  keystorePassword?: string;
  issuer?: string;
  serialNumber?: string;
  validFrom?: string;
  validTo?: string;
  /** Auto-calculated from validTo. */
  daysToExpiry?: number;
  /** Backend default "VALID". */
  status?: string;
  /** JSON array of route IDs stored as text. */
  associatedRoutes?: string;
  /** Backend default true. */
  enabled?: boolean;
  /** Backend default false. */
  autoRenew?: boolean;
  lastRenewedAt?: string;
  createdAt?: string;
  updatedAt?: string;
}

/** GET /api/ssl/stats response (service builds a Map with these keys). */
export interface CertificateStats {
  total: number;
  valid: number;
  expiringSoon: number;
  expired: number;
  expiringList: SslCertificate[];
}
