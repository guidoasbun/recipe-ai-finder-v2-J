import { NextResponse } from "next/server";
import { getSession } from "@/lib/session";
import { getExportStatus } from "@/lib/compliance-api";

export async function GET() {
  const token = await getSession();
  if (!token) {
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
  }

  try {
    const status = await getExportStatus(token);
    if (status === null) {
      return new NextResponse(null, { status: 204 });
    }
    return NextResponse.json(status);
  } catch (error) {
    return NextResponse.json(
      { error: "Failed to get export status" },
      { status: 500 }
    );
  }
}
