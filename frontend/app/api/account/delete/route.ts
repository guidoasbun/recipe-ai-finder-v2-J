import { NextResponse } from "next/server";
import { getSession } from "@/lib/session";
import { apiFetch } from "@/lib/api";
import type { DeletionType } from "@/lib/compliance-api";

export async function POST(request: Request) {
  const token = await getSession();
  if (!token) {
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
  }

  try {
    const { type } = await request.json();
    const body = JSON.stringify({ type: type as DeletionType });

    const res = await apiFetch(
      "/api/account/delete",
      { method: "POST", body },
      token
    );

    if (res.ok) {
      return NextResponse.json({ success: true });
    }

    // Preserve the backend status and body
    const responseHeaders: Record<string, string> = {};
    const retryAfter = res.headers.get("Retry-After");
    if (retryAfter) {
      responseHeaders["Retry-After"] = retryAfter;
    }

    let errorBody: unknown;
    try {
      errorBody = await res.json();
    } catch {
      errorBody = { error: "Failed to delete account" };
    }

    return NextResponse.json(errorBody, {
      status: res.status,
      headers: responseHeaders,
    });
  } catch {
    return NextResponse.json(
      { error: "Failed to delete account" },
      { status: 500 }
    );
  }
}
