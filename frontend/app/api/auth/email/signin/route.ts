import { NextRequest, NextResponse } from "next/server";
import { cookies } from "next/headers";
import { setSession } from "@/lib/session";
import { COGNITO_CLIENT_ID } from "@/lib/constants";
import { cognitoPost } from "@/lib/cognito";
import { isValidEmail } from "@/lib/validation";

export async function POST(request: NextRequest) {
  const { email, password } = await request.json();
  if (!email || !password) {
    return NextResponse.json({ error: "Email and password are required" }, { status: 400 });
  }
  if (!isValidEmail(email)) {
    return NextResponse.json({ error: "Invalid email format" }, { status: 400 });
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

  // Admin-created accounts start with a forced password change. Resolve it
  // automatically so the user lands directly in the app.
  if (data.ChallengeName === "NEW_PASSWORD_REQUIRED") {
    const challenge = await cognitoPost(
      "AWSCognitoIdentityProviderService.RespondToAuthChallenge",
      {
        ClientId: COGNITO_CLIENT_ID,
        ChallengeName: "NEW_PASSWORD_REQUIRED",
        Session: data.Session,
        ChallengeResponses: {
          USERNAME: email,
          NEW_PASSWORD: password,
        },
      }
    );

    if (!challenge.ok) {
      const message = (challenge.data.message as string) ?? "Sign in failed.";
      return NextResponse.json({ error: message }, { status: 401 });
    }

    data.AuthenticationResult = challenge.data.AuthenticationResult;
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
