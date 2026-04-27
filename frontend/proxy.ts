import { NextRequest, NextResponse } from "next/server";
import { COGNITO_DOMAIN, COGNITO_CLIENT_ID } from "@/lib/constants";

const PUBLIC_PATHS = ["/login", "/api/auth", "/api/health"];
const SESSION_COOKIE = "session";
const REFRESH_COOKIE = "refresh_token";
const COOKIE_OPTS = {
  httpOnly: true,
  secure: process.env.NODE_ENV === "production",
  sameSite: "lax" as const,
  path: "/",
};

function isExpired(token: string): boolean {
  try {
    const payload = JSON.parse(atob(token.split(".")[1]));
    return payload.exp * 1000 < Date.now();
  } catch {
    return true;
  }
}

async function refresh(refreshToken: string): Promise<string | null> {
  try {
    const res = await fetch(`https://${COGNITO_DOMAIN}/oauth2/token`, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "refresh_token",
        client_id: COGNITO_CLIENT_ID,
        refresh_token: refreshToken,
      }),
    });
    if (!res.ok) return null;
    const { id_token } = await res.json();
    return id_token ?? null;
  } catch {
    return null;
  }
}

export async function proxy(request: NextRequest) {
  const { pathname } = request.nextUrl;

  const isPublic = PUBLIC_PATHS.some((p) => pathname.startsWith(p));
  if (isPublic) return NextResponse.next();

  const session = request.cookies.get(SESSION_COOKIE)?.value;
  if (!session) {
    return NextResponse.redirect(new URL("/login", request.url));
  }

  let activeToken = session;

  if (isExpired(session)) {
    const refreshToken = request.cookies.get(REFRESH_COOKIE)?.value;
    if (!refreshToken) {
      return NextResponse.redirect(new URL("/login", request.url));
    }

    const newToken = await refresh(refreshToken);
    if (!newToken) {
      const res = NextResponse.redirect(new URL("/login", request.url));
      res.cookies.delete(SESSION_COOKIE);
      res.cookies.delete(REFRESH_COOKIE);
      return res;
    }

    activeToken = newToken;
  }

  const requestHeaders = new Headers(request.headers);
  let response: NextResponse;

  if (pathname.startsWith("/api/backend/")) {
    requestHeaders.set("Authorization", `Bearer ${activeToken}`);
    response = NextResponse.next({ request: { headers: requestHeaders } });
  } else {
    response = NextResponse.next();
  }

  if (activeToken !== session) {
    response.cookies.set(SESSION_COOKIE, activeToken, {
      ...COOKIE_OPTS,
      maxAge: 60 * 60 * 24,
    });
  }

  return response;
}

export const config = {
  matcher: ["/((?!_next/static|_next/image|favicon.ico|public/).*)"],
};
