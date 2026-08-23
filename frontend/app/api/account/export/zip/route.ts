import { NextResponse } from "next/server";
import { getSession } from "@/lib/session";
import { startZipExport } from "@/lib/compliance-api";

export async function POST() {
  const token = await getSession();
  if (!token) {
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
  }

  try {
    const status = await startZipExport(token);
    return NextResponse.json(status);
  } catch (error) {
    return NextResponse.json(
      { error: "Failed to start ZIP export" },
      { status: 500 }
    );
  }
}
