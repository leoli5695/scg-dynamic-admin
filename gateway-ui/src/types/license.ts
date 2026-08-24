/**
 * License domain types, aligned with gateway-admin backend:
 * - controller/LicenseController.java (/api/license)
 * - license-client model LicenseInfo (edition/features/quotas)
 */

export type LicenseEdition = 'BASIC' | 'ADVANCED' | 'ENTERPRISE';

/** Quota limits embedded in license status (LicenseInfo.QuotaInfo). */
export interface LicenseQuotaInfo {
  maxInstances?: number;
  maxRoutes?: number;
  maxServices?: number;
  maxStrategies?: number;
  maxAuthPolicies?: number;
  maxRateLimitQPS?: number;
  maxApiCopilotCalls?: number;
  maxTraceRetention?: number;
  maxAuditLogRetention?: number;
  supportLevel?: string;
}

/** GET /api/license/status result map. */
export interface LicenseStatus {
  loaded: boolean;
  valid: boolean;
  /** Edition code, or "UNLICENSED" when nothing is loaded. */
  edition: string;
  licenseId?: string;
  customerName?: string;
  organization?: string;
  issuedAt?: string;
  expiresAt?: string;
  expired?: boolean;
  /** Null/absent for permanent licenses. */
  daysRemaining?: number | null;
  message?: string;
  features?: string[];
  quotas?: LicenseQuotaInfo;
}

/** GET /api/license/list item (single imported license at most). */
export interface LicenseListItem {
  licenseId: string;
  edition: string;
  customerName?: string;
  customerEmail?: string;
  customerCompany?: string;
  issuedAt?: string | null;
  expiresAt?: string | null;
  expired: boolean;
  /** "ACTIVE" or "EXPIRED". */
  status: string;
  features?: string[];
  createdAt?: string | null;
  updatedAt?: string | null;
}

/** POST /api/license/import result map. */
export interface ImportLicenseResult {
  success: boolean;
  licenseId?: string;
  edition?: string;
  customerName?: string;
  message?: string;
}

/** GET /api/license/features result map. */
export interface LicenseFeaturesResult {
  edition: string;
  loaded: boolean;
  features?: string[];
  quotas?: LicenseQuotaInfo;
  /** Feature enum name -> enabled flag. */
  featureStatus?: Record<string, boolean>;
}
