import { NextResponse } from "next/server";
import { getSession } from "@/lib/session";
import { listConsents, revokeConsent } from "@/lib/compliance-api";
import type { ConsentType } from "@/lib/compliance-api";

export async function GET() {
  const token = await getSession();
  if (!token) {
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
  }

  try {
    const consents = await listConsents(token);
    return NextResponse.json(consents);
  } catch (error) {
    return NextResponse.json(
      { error: "Failed to fetch consents" },
      { status: 500 }
    );
  }
}

export async function DELETE(request: Request) {
  const token = await getSession();
  if (!token) {
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
  }

  try {
    const { consentType } = await request.json();
    const consent = await revokeConsent(consentType as ConsentType, token);
    return NextResponse.json(consent);
  } catch (error) {
    return NextResponse.json(
      { error: "Failed to revoke consent" },
      { status: 500 }
    );
  }
}
