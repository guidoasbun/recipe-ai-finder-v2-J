import { NextResponse } from "next/server";
import { getSession } from "@/lib/session";
import { deleteAccount } from "@/lib/compliance-api";
import type { DeletionType } from "@/lib/compliance-api";

export async function POST(request: Request) {
  const token = await getSession();
  if (!token) {
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
  }

  try {
    const { type } = await request.json();
    await deleteAccount(type as DeletionType, token);
    return NextResponse.json({ success: true });
  } catch (error) {
    return NextResponse.json(
      { error: "Failed to delete account" },
      { status: 500 }
    );
  }
}
