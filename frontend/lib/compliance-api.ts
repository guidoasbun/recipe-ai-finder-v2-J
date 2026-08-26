import { apiFetch } from "./api";

// --- Types ---

export type ConsentType =
  | "TERMS_OF_SERVICE"
  | "PRIVACY_POLICY"
  | "AI_DATA_PROCESSING";

export interface Consent {
  userId: string;
  consentType: string;
  granted: boolean;
  grantedAt: string | null;
  revokedAt: string | null;
  version: string | null;
  ipAddress: string | null;
}

export interface GrantConsentRequest {
  consentType: ConsentType;
  version?: string;
}

export type DeletionType = "soft" | "immediate";

export interface DeleteAccountRequest {
  type: DeletionType;
}

export type ExportStatus = "IN_PROGRESS" | "COMPLETED" | "FAILED";

export interface ExportStatusResponse {
  status: ExportStatus;
  downloadUrl: string | null;
  error: string | null;
}

export interface UserProfile {
  userId: string;
  email: string;
  username: string;
  createdAt: string;
  accountStatus: string | null;
  scheduledDeletionDate: string | null;
  dietaryRestrictions: string[] | null;
}

export interface RecipeExportData {
  recipeId: string;
  title: string;
  description: string;
  ingredients: string[];
  steps: string[];
  model: string;
  imageModel: string;
  textGenerationMs: number;
  imageGenerationMs: number;
  createdAt: string;
  imageS3Key: string | null;
}

export interface DataExportJson {
  exportedAt: string;
  user: {
    email: string;
    username: string;
    createdAt: string;
  };
  recipes: RecipeExportData[];
  missingImages: string[];
}

// --- Consent API ---

export async function grantConsent(
  request: GrantConsentRequest,
  sessionToken: string
): Promise<Consent> {
  const res = await apiFetch(
    "/api/consent",
    { method: "POST", body: JSON.stringify(request) },
    sessionToken
  );
  if (!res.ok) {
    throw new Error(`Failed to grant consent: ${res.status}`);
  }
  return res.json();
}

export async function revokeConsent(
  type: ConsentType,
  sessionToken: string
): Promise<Consent> {
  const res = await apiFetch(
    `/api/consent/${type}`,
    { method: "DELETE" },
    sessionToken
  );
  if (!res.ok) {
    throw new Error(`Failed to revoke consent: ${res.status}`);
  }
  return res.json();
}

export async function listConsents(
  sessionToken: string
): Promise<Consent[]> {
  const res = await apiFetch("/api/consent", { method: "GET" }, sessionToken);
  if (!res.ok) {
    throw new Error(`Failed to list consents: ${res.status}`);
  }
  return res.json();
}

// --- Account Deletion API ---

export async function deleteAccount(
  type: DeletionType,
  sessionToken: string
): Promise<void> {
  const body: DeleteAccountRequest = { type };
  const res = await apiFetch(
    "/api/account/delete",
    { method: "POST", body: JSON.stringify(body) },
    sessionToken
  );
  if (!res.ok) {
    throw new Error(`Failed to delete account: ${res.status}`);
  }
}

export async function cancelDeletion(
  sessionToken: string
): Promise<void> {
  const res = await apiFetch(
    "/api/account/cancel-deletion",
    { method: "POST" },
    sessionToken
  );
  if (!res.ok) {
    throw new Error(`Failed to cancel deletion: ${res.status}`);
  }
}

// --- Data Export API ---

export async function exportJson(
  sessionToken: string
): Promise<DataExportJson> {
  const res = await apiFetch(
    "/api/account/export?format=json",
    { method: "GET" },
    sessionToken
  );
  if (!res.ok) {
    throw new Error(`Failed to export JSON: ${res.status}`);
  }
  return res.json();
}

export async function startZipExport(
  sessionToken: string
): Promise<ExportStatusResponse> {
  const res = await apiFetch(
    "/api/account/export?format=zip",
    { method: "POST" },
    sessionToken
  );
  if (!res.ok) {
    throw new Error(`Failed to start ZIP export: ${res.status}`);
  }
  return res.json();
}

export async function getExportStatus(
  sessionToken: string
): Promise<ExportStatusResponse | null> {
  const res = await apiFetch(
    "/api/account/export/status",
    { method: "GET" },
    sessionToken
  );
  if (res.status === 204) {
    return null;
  }
  if (!res.ok) {
    throw new Error(`Failed to get export status: ${res.status}`);
  }
  return res.json();
}

// --- Dietary Restrictions API ---

export async function getDietaryRestrictions(
  sessionToken: string
): Promise<string[]> {
  const res = await apiFetch(
    "/api/account/dietary-restrictions",
    { method: "GET" },
    sessionToken
  );
  if (!res.ok) {
    throw new Error(`Failed to fetch dietary restrictions: ${res.status}`);
  }
  return res.json();
}

export async function updateDietaryRestrictions(
  restrictions: string[],
  sessionToken: string
): Promise<string[]> {
  const res = await apiFetch(
    "/api/account/dietary-restrictions",
    { method: "PUT", body: JSON.stringify({ restrictions }) },
    sessionToken
  );
  if (!res.ok) {
    throw new Error(`Failed to update dietary restrictions: ${res.status}`);
  }
  return res.json();
}

// --- Profile API ---

export async function fetchProfile(
  sessionToken: string
): Promise<UserProfile> {
  const res = await apiFetch(
    "/api/account/profile",
    { method: "GET" },
    sessionToken
  );
  if (!res.ok) {
    throw new Error(`Failed to fetch profile: ${res.status}`);
  }
  return res.json();
}
