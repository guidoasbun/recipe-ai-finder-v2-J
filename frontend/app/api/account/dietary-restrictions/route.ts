import { NextResponse } from "next/server";
import { getSession } from "@/lib/session";
import { apiFetch } from "@/lib/api";

const BACKEND_PATH = "/api/account/dietary-restrictions";

export async function GET() {
  const token = await getSession();
  if (!token) {
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
  }

  try {
    const res = await apiFetch(BACKEND_PATH, { method: "GET" }, token);
    return await proxyResponse(res);
  } catch {
    return NextResponse.json(
      { error: "Failed to fetch dietary restrictions" },
      { status: 502 }
    );
  }
}

export async function PUT(request: Request) {
  const token = await getSession();
  if (!token) {
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
  }

  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return NextResponse.json(
      { error: "Request body must be valid JSON" },
      { status: 400 }
    );
  }

  const restrictions = (body as { restrictions?: unknown })?.restrictions;
  // Reject malformed input up front. Coalescing a missing/null field to [] would
  // silently turn a bad request into a destructive "clear all restrictions".
  if (!Array.isArray(restrictions)) {
    return NextResponse.json(
      { error: "'restrictions' must be an array" },
      { status: 400 }
    );
  }

  try {
    const res = await apiFetch(
      BACKEND_PATH,
      { method: "PUT", body: JSON.stringify({ restrictions }) },
      token
    );
    return await proxyResponse(res);
  } catch {
    return NextResponse.json(
      { error: "Failed to update dietary restrictions" },
      { status: 502 }
    );
  }
}

/**
 * Forwards the backend response verbatim, preserving its status code and body so
 * that validation (400) and not-found (404) results are not flattened into 500.
 */
async function proxyResponse(res: Response): Promise<NextResponse> {
  const text = await res.text();
  if (!text) {
    return new NextResponse(null, { status: res.status });
  }
  try {
    return NextResponse.json(JSON.parse(text), { status: res.status });
  } catch {
    return new NextResponse(text, {
      status: res.status,
      headers: { "Content-Type": res.headers.get("Content-Type") ?? "text/plain" },
    });
  }
}
