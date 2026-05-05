import { NextRequest, NextResponse } from "next/server";
import { COGNITO_CLIENT_ID } from "@/lib/constants";
import { cognitoPost } from "@/lib/cognito";
import { isValidEmail } from "@/lib/validation";

export async function POST(request: NextRequest) {
  const { email } = await request.json();
  if (!email) {
    return NextResponse.json({ error: "Email is required" }, { status: 400 });
  }
  if (!isValidEmail(email)) {
    return NextResponse.json({ error: "Invalid email format" }, { status: 400 });
  }

  const { ok, data } = await cognitoPost("AWSCognitoIdentityProviderService.ForgotPassword", {
    ClientId: COGNITO_CLIENT_ID,
    Username: email,
  });

  if (!ok) {
    const message =
      data.__type === "UserNotFoundException"
        ? "No account found with that email."
        : (data.message as string) ?? "Failed to send reset code.";
    return NextResponse.json({ error: message }, { status: 400 });
  }

  return NextResponse.json({ ok: true });
}
