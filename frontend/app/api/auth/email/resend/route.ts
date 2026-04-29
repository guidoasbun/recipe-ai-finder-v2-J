import { NextRequest, NextResponse } from "next/server";
import { COGNITO_CLIENT_ID } from "@/lib/constants";
import { cognitoPost } from "@/lib/cognito";

export async function POST(request: NextRequest) {
  const { email } = await request.json();
  if (!email) {
    return NextResponse.json({ error: "Email is required" }, { status: 400 });
  }

  const { ok, data } = await cognitoPost("AWSCognitoIdentityProviderService.ResendConfirmationCode", {
    ClientId: COGNITO_CLIENT_ID,
    Username: email,
  });

  if (!ok) {
    return NextResponse.json(
      { error: (data.message as string) ?? "Failed to resend code." },
      { status: 400 }
    );
  }

  return NextResponse.json({ ok: true });
}
