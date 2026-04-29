import { NextRequest, NextResponse } from "next/server";
import { COGNITO_CLIENT_ID } from "@/lib/constants";
import { cognitoPost } from "@/lib/cognito";

export async function POST(request: NextRequest) {
  const { email, password } = await request.json();
  if (!email || !password) {
    return NextResponse.json({ error: "Email and password are required" }, { status: 400 });
  }

  const { ok, data } = await cognitoPost("AWSCognitoIdentityProviderService.SignUp", {
    ClientId: COGNITO_CLIENT_ID,
    Username: email,
    Password: password,
    UserAttributes: [{ Name: "email", Value: email }],
  });

  if (!ok) {
    const message =
      data.__type === "UsernameExistsException"
        ? "An account with this email already exists."
        : data.__type === "InvalidPasswordException"
        ? "Password must be at least 8 characters with uppercase, lowercase, and numbers."
        : (data.message as string) ?? "Sign up failed.";
    return NextResponse.json({ error: message }, { status: 400 });
  }

  return NextResponse.json({ ok: true });
}
