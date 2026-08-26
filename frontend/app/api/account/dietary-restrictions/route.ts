import { NextResponse } from "next/server";
import { getSession } from "@/lib/session";
import {
  getDietaryRestrictions,
  updateDietaryRestrictions,
} from "@/lib/compliance-api";

export async function GET() {
  const token = await getSession();
  if (!token) {
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
  }

  try {
    const restrictions = await getDietaryRestrictions(token);
    return NextResponse.json(restrictions);
  } catch {
    return NextResponse.json(
      { error: "Failed to fetch dietary restrictions" },
      { status: 500 }
    );
  }
}

export async function PUT(request: Request) {
  const token = await getSession();
  if (!token) {
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
  }

  try {
    const { restrictions } = await request.json();
    const saved = await updateDietaryRestrictions(restrictions ?? [], token);
    return NextResponse.json(saved);
  } catch {
    return NextResponse.json(
      { error: "Failed to update dietary restrictions" },
      { status: 500 }
    );
  }
}
