import { NextResponse } from "next/server";
import { getSession } from "@/lib/session";
import { exportJson } from "@/lib/compliance-api";

export async function GET() {
  const token = await getSession();
  if (!token) {
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
  }

  try {
    const data = await exportJson(token);
    return NextResponse.json(data);
  } catch (error) {
    return NextResponse.json(
      { error: "Failed to export data" },
      { status: 500 }
    );
  }
}
