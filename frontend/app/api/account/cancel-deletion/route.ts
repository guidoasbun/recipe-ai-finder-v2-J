import { NextResponse } from "next/server";
import { getSession } from "@/lib/session";
import { cancelDeletion } from "@/lib/compliance-api";

export async function POST() {
  const token = await getSession();
  if (!token) {
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
  }

  try {
    await cancelDeletion(token);
    return NextResponse.json({ success: true });
  } catch (error) {
    return NextResponse.json(
      { error: "Failed to cancel deletion" },
      { status: 500 }
    );
  }
}
