import { NextRequest, NextResponse } from "next/server";
import { clearSession } from "@/lib/session";
import { COGNITO_DOMAIN, COGNITO_CLIENT_ID, COGNITO_REDIRECT_URI } from "@/lib/constants";

export async function GET(request: NextRequest) {
  await clearSession();

  const logoutUrl =
    `https://${COGNITO_DOMAIN}/logout` +
    `?client_id=${COGNITO_CLIENT_ID}` +
    `&logout_uri=${encodeURIComponent(COGNITO_REDIRECT_URI.replace("/api/auth/callback", "/login"))}`;

  return NextResponse.redirect(logoutUrl);
}
