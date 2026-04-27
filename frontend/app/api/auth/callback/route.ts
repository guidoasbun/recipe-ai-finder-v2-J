import { NextRequest, NextResponse } from "next/server";
import { setSession } from "@/lib/session";
import { cookies } from "next/headers";
import { COGNITO_DOMAIN, COGNITO_CLIENT_ID, COGNITO_REDIRECT_URI } from "@/lib/constants";

const APP_ORIGIN = new URL(COGNITO_REDIRECT_URI).origin;

export async function GET(request: NextRequest) {
  const code = request.nextUrl.searchParams.get("code");
  if (!code) {
    return NextResponse.redirect(new URL("/login", APP_ORIGIN));
  }

  const tokenRes = await fetch(`https://${COGNITO_DOMAIN}/oauth2/token`, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "authorization_code",
      client_id: COGNITO_CLIENT_ID,
      redirect_uri: COGNITO_REDIRECT_URI,
      code,
    }),
  });

  if (!tokenRes.ok) {
    return NextResponse.redirect(new URL("/login", APP_ORIGIN));
  }

  const { id_token, refresh_token } = await tokenRes.json();
  await setSession(id_token);

  if (refresh_token) {
    const cookieStore = await cookies();
    cookieStore.set("refresh_token", refresh_token, {
      httpOnly: true,
      secure: process.env.NODE_ENV === "production",
      sameSite: "lax",
      path: "/",
      maxAge: 60 * 60 * 24 * 30,
    });
  }

  return NextResponse.redirect(new URL("/dashboard", APP_ORIGIN));
}
