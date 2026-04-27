export const BACKEND_URL =
  process.env.BACKEND_URL ?? "http://localhost:8080";

export const COGNITO_DOMAIN = process.env.COGNITO_DOMAIN ?? "";
export const COGNITO_CLIENT_ID = process.env.COGNITO_CLIENT_ID ?? "";
export const COGNITO_REDIRECT_URI =
  process.env.COGNITO_REDIRECT_URI ?? "http://localhost:3000/api/auth/callback";

export const MODELS = [
  { id: "CLAUDE_HAIKU", label: "Claude Haiku (Fast)" },
  { id: "CLAUDE_SONNET", label: "Claude Sonnet (Smart)" },
  { id: "NOVA_LITE", label: "Amazon Nova Lite" },
  { id: "LLAMA3", label: "Llama 3" },
  { id: "AMAZON_TITAN", label: "Amazon Nova Micro (Fast)" },
] as const;

export type ModelId = (typeof MODELS)[number]["id"];
