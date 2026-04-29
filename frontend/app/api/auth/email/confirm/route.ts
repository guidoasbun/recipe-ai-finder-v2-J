import { NextRequest, NextResponse } from "next/server";
import { COGNITO_CLIENT_ID } from "@/lib/constants";
import { cognitoPost } from "@/lib/cognito";

export async function POST(request: NextRequest) {
  const { email, code } = await request.json();
  if (!email || !code) {
    return NextResponse.json({ error: "Email and code are required" }, { status: 400 });
  }

  const { ok, data } = await cognitoPost("AWSCognitoIdentityProviderService.ConfirmSignUp", {
    ClientId: COGNITO_CLIENT_ID,
    Username: email,
    ConfirmationCode: code,
  });

  if (!ok) {
    const message =
      data.__type === "CodeMismatchException"
        ? "Invalid verification code."
        : data.__type === "ExpiredCodeException"
        ? "Verification code has expired. Please request a new one."
        : (data.message as string) ?? "Confirmation failed.";
    return NextResponse.json({ error: message }, { status: 400 });
  }

  return NextResponse.json({ ok: true });
}
