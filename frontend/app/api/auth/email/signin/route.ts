import { NextRequest, NextResponse } from "next/server";
import { cookies } from "next/headers";
import { setSession } from "@/lib/session";
import { COGNITO_CLIENT_ID } from "@/lib/constants";
import { cognitoPost } from "@/lib/cognito";

export async function POST(request: NextRequest) {
  const { email, password } = await request.json();
  if (!email || !password) {
    return NextResponse.json({ error: "Email and password are required" }, { status: 400 });
  }

  const { ok, data } = await cognitoPost("AWSCognitoIdentityProviderService.InitiateAuth", {
    AuthFlow: "USER_PASSWORD_AUTH",
    ClientId: COGNITO_CLIENT_ID,
    AuthParameters: {
      USERNAME: email,
      PASSWORD: password,
    },
  });

  if (!ok) {
    const message =
      data.__type === "NotAuthorizedException"
        ? "Incorrect email or password."
        : data.__type === "UserNotConfirmedException"
        ? "Please verify your email before signing in."
        : (data.message as string) ?? "Sign in failed.";
    return NextResponse.json({ error: message }, { status: 401 });
  }

  const result = data.AuthenticationResult as Record<string, string>;
  await setSession(result.IdToken);

  if (result.RefreshToken) {
    const cookieStore = await cookies();
    cookieStore.set("refresh_token", result.RefreshToken, {
      httpOnly: true,
      secure: process.env.NODE_ENV === "production",
      sameSite: "lax",
      path: "/",
      maxAge: 60 * 60 * 24 * 30,
    });
  }

  return NextResponse.json({ ok: true });
}
