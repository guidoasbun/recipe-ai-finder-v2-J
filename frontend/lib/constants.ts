export const BACKEND_URL =
  process.env.NEXT_PUBLIC_BACKEND_URL ?? "http://localhost:8080";

export const COGNITO_DOMAIN = process.env.NEXT_PUBLIC_COGNITO_DOMAIN ?? "";
export const COGNITO_CLIENT_ID = process.env.NEXT_PUBLIC_COGNITO_CLIENT_ID ?? "";
export const COGNITO_REDIRECT_URI =
  process.env.NEXT_PUBLIC_COGNITO_REDIRECT_URI ?? "http://localhost:3000/api/auth/callback";

export const MODELS = [
  { id: "CLAUDE_HAIKU", label: "Claude Haiku (Fast)" },
  { id: "CLAUDE_SONNET", label: "Claude Sonnet (Smart)" },
  { id: "NOVA_LITE", label: "Amazon Nova Lite" },
  { id: "LLAMA3", label: "Llama 3" },
  { id: "AMAZON_TITAN", label: "Amazon Titan" },
] as const;

export type ModelId = (typeof MODELS)[number]["id"];
