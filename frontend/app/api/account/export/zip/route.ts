import { NextResponse } from "next/server";
import { getSession } from "@/lib/session";
import { apiFetch } from "@/lib/api";

export async function POST() {
  const token = await getSession();
  if (!token) {
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
  }

  try {
    const res = await apiFetch(
      "/api/account/export?format=zip",
      { method: "POST" },
      token
    );

    if (res.ok) {
      const data = await res.json();
      return NextResponse.json(data);
    }

    // Preserve the backend status and relevant headers
    const responseHeaders: Record<string, string> = {};
    const retryAfter = res.headers.get("Retry-After");
    if (retryAfter) {
      responseHeaders["Retry-After"] = retryAfter;
    }

    let errorBody: unknown;
    try {
      errorBody = await res.json();
    } catch {
      errorBody = { error: "Failed to start ZIP export" };
    }

    return NextResponse.json(errorBody, {
      status: res.status,
      headers: responseHeaders,
    });
  } catch {
    return NextResponse.json(
      { error: "Failed to start ZIP export" },
      { status: 500 }
    );
  }
}
