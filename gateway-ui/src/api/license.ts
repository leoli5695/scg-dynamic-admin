/**
 * License API client (gateway-admin LicenseController, /api/license).
 * All endpoints return the ApiResponse envelope.
 * Note: license creation/delete/revoke lives on the license-server;
 * gateway-admin only manages the single imported license.
 */
import { httpClient } from './http';
import type {
  ImportLicenseResult,
  LicenseFeaturesResult,
  LicenseListItem,
  LicenseStatus,
} from '../types/license';

/** Current license status (loaded=false when nothing is imported). */
export function getLicenseStatus(): Promise<LicenseStatus> {
  return httpClient.get<LicenseStatus>('/api/license/status');
}

/** Imported license as a single-item list (empty when none). */
export function getLicenseList(): Promise<LicenseListItem[]> {
  return httpClient.get<LicenseListItem[]>('/api/license/list');
}

/** Validate and import a license code. */
export function importLicense(licenseCode: string): Promise<ImportLicenseResult> {
  return httpClient.post<ImportLicenseResult>('/api/license/import', { licenseCode });
}

/** All known features with their enabled status for the current license. */
export function getLicenseFeatures(): Promise<LicenseFeaturesResult> {
  return httpClient.get<LicenseFeaturesResult>('/api/license/features');
}
