import { NextResponse } from "next/server";
import { getSession, decodeSession } from "@/lib/session";

export async function GET() {
  const token = await getSession();
  if (!token) {
    return NextResponse.json({ user: null }, { status: 401 });
  }

  const payload = decodeSession(token);
  return NextResponse.json({
    user: {
      sub: payload.sub,
      email: payload.email,
      name: payload.name,
      picture: payload.picture,
    },
  });
}
