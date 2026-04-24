import { BACKEND_URL } from "./constants";

export async function apiFetch(
  path: string,
  options: RequestInit = {},
  sessionToken?: string
): Promise<Response> {
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    ...(options.headers as Record<string, string>),
  };

  if (sessionToken) {
    headers["Authorization"] = `Bearer ${sessionToken}`;
  }

  return fetch(`${BACKEND_URL}${path}`, {
    ...options,
    headers,
  });
}
