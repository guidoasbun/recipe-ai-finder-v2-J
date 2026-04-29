import { NextRequest, NextResponse } from "next/server";
import { COGNITO_CLIENT_ID } from "@/lib/constants";
import { cognitoPost } from "@/lib/cognito";

export async function POST(request: NextRequest) {
  const { email, code, password } = await request.json();
  if (!email || !code || !password) {
    return NextResponse.json({ error: "Email, code, and password are required" }, { status: 400 });
  }

  const { ok, data } = await cognitoPost("AWSCognitoIdentityProviderService.ConfirmForgotPassword", {
    ClientId: COGNITO_CLIENT_ID,
    Username: email,
    ConfirmationCode: code,
    Password: password,
  });

  if (!ok) {
    const message =
      data.__type === "CodeMismatchException"
        ? "Invalid reset code."
        : data.__type === "ExpiredCodeException"
        ? "Reset code has expired. Please request a new one."
        : data.__type === "InvalidPasswordException"
        ? "Password must be at least 8 characters with uppercase, lowercase, and numbers."
        : (data.message as string) ?? "Password reset failed.";
    return NextResponse.json({ error: message }, { status: 400 });
  }

  return NextResponse.json({ ok: true });
}
