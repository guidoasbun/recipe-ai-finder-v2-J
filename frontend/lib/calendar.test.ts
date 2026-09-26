import { describe, it, expect } from "vitest";
import {
  addDays,
  daysBetween,
  startOfWeek,
  weekDays,
  monthGrid,
  isSameMonth,
  spanCoversDay,
} from "./calendar";

describe("calendar helpers", () => {
  it("addDays crosses month boundaries", () => {
    expect(addDays("2026-01-31", 1)).toBe("2026-02-01");
    expect(addDays("2026-03-01", -1)).toBe("2026-02-28");
  });

  it("daysBetween counts whole days", () => {
    expect(daysBetween("2026-01-01", "2026-01-05")).toBe(4);
    expect(daysBetween("2026-01-05", "2026-01-01")).toBe(-4);
  });

  it("startOfWeek returns the Sunday of the week", () => {
    // 2026-02-11 is a Wednesday; the Sunday before is 2026-02-08.
    expect(startOfWeek("2026-02-11")).toBe("2026-02-08");
  });

  it("weekDays returns 7 consecutive days starting Sunday", () => {
    const days = weekDays("2026-02-11");
    expect(days).toHaveLength(7);
    expect(days[0]).toBe("2026-02-08");
    expect(days[6]).toBe("2026-02-14");
  });

  it("monthGrid covers the whole month in full weeks of 7", () => {
    const weeks = monthGrid("2026-02-15");
    // Every row is a full week.
    for (const w of weeks) expect(w).toHaveLength(7);
    // The grid starts on a Sunday and includes Feb 1 and Feb 28.
    const flat = weeks.flat();
    expect(flat).toContain("2026-02-01");
    expect(flat).toContain("2026-02-28");
    // Grid starts on a Sunday.
    expect(startOfWeek(flat[0])).toBe(flat[0]);
  });

  it("isSameMonth compares year+month", () => {
    expect(isSameMonth("2026-02-01", "2026-02-28")).toBe(true);
    expect(isSameMonth("2026-01-31", "2026-02-01")).toBe(false);
  });

  it("spanCoversDay covers the start and continuation days only", () => {
    // 3-day span from Feb 8 covers Feb 8, 9, 10 — not 7 or 11.
    expect(spanCoversDay("2026-02-08", 3, "2026-02-07")).toBe(false);
    expect(spanCoversDay("2026-02-08", 3, "2026-02-08")).toBe(true);
    expect(spanCoversDay("2026-02-08", 3, "2026-02-10")).toBe(true);
    expect(spanCoversDay("2026-02-08", 3, "2026-02-11")).toBe(false);
  });

  it("spanCoversDay treats span<1 as a single day", () => {
    expect(spanCoversDay("2026-02-08", 0, "2026-02-08")).toBe(true);
    expect(spanCoversDay("2026-02-08", 0, "2026-02-09")).toBe(false);
  });
});
