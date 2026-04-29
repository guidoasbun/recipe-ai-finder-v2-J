import { COGNITO_DOMAIN, COGNITO_CLIENT_ID, COGNITO_REDIRECT_URI } from "@/lib/constants";
import { LoginForm } from "./LoginForm";

export const dynamic = "force-dynamic";

export default function LoginPage() {
  const googleLoginUrl =
    `https://${COGNITO_DOMAIN}/oauth2/authorize` +
    `?client_id=${COGNITO_CLIENT_ID}` +
    `&response_type=code` +
    `&scope=openid+email+profile` +
    `&redirect_uri=${encodeURIComponent(COGNITO_REDIRECT_URI)}` +
    `&identity_provider=Google`;

  return <LoginForm googleLoginUrl={googleLoginUrl} />;
}
