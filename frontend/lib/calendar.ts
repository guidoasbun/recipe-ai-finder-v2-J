// Pure date helpers for the meal calendar. All dates are ISO day strings (yyyy-MM-dd) in the
// user's LOCAL calendar — a meal is a calendar day, never an instant, so we never touch UTC
// offsets here. Kept dependency-free and pure so it's trivially testable.

export type ISODate = string; // "yyyy-MM-dd"

export function todayIso(): ISODate {
  return toIso(new Date());
}

export function toIso(d: Date): ISODate {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, "0");
  const day = String(d.getDate()).padStart(2, "0");
  return `${y}-${m}-${day}`;
}

export function parseIso(iso: ISODate): Date {
  const [y, m, d] = iso.split("-").map(Number);
  return new Date(y, m - 1, d);
}

export function addDays(iso: ISODate, delta: number): ISODate {
  const d = parseIso(iso);
  d.setDate(d.getDate() + delta);
  return toIso(d);
}

/** Difference in whole days (b - a). */
export function daysBetween(a: ISODate, b: ISODate): number {
  const ms = parseIso(b).getTime() - parseIso(a).getTime();
  return Math.round(ms / 86_400_000);
}

/** Start of the week containing `iso`. weekStartsOn: 0 = Sunday (default), 1 = Monday. */
export function startOfWeek(iso: ISODate, weekStartsOn = 0): ISODate {
  const d = parseIso(iso);
  const diff = (d.getDay() - weekStartsOn + 7) % 7;
  return addDays(iso, -diff);
}

/** The 7 ISO dates of the week containing `iso`. */
export function weekDays(iso: ISODate, weekStartsOn = 0): ISODate[] {
  const start = startOfWeek(iso, weekStartsOn);
  return Array.from({ length: 7 }, (_, i) => addDays(start, i));
}

/**
 * The grid of weeks covering the month containing `iso`, padded to full weeks (so the grid is
 * a clean rectangle). Each inner array is one week row of 7 ISO dates.
 */
export function monthGrid(iso: ISODate, weekStartsOn = 0): ISODate[][] {
  const d = parseIso(iso);
  const firstOfMonth = new Date(d.getFullYear(), d.getMonth(), 1);
  const gridStart = startOfWeek(toIso(firstOfMonth), weekStartsOn);
  const lastOfMonth = new Date(d.getFullYear(), d.getMonth() + 1, 0);
  const weeks: ISODate[][] = [];
  let cursor = gridStart;
  // Add weeks until we've passed the last day of the month.
  do {
    const row = Array.from({ length: 7 }, (_, i) => addDays(cursor, i));
    weeks.push(row);
    cursor = addDays(cursor, 7);
  } while (parseIso(cursor) <= lastOfMonth);
  return weeks;
}

export function isSameMonth(iso: ISODate, ref: ISODate): boolean {
  return iso.slice(0, 7) === ref.slice(0, 7);
}

export function monthLabel(iso: ISODate): string {
  return parseIso(iso).toLocaleDateString(undefined, { month: "long", year: "numeric" });
}

export function dayNumber(iso: ISODate): number {
  return parseIso(iso).getDate();
}

export function weekdayShort(iso: ISODate): string {
  return parseIso(iso).toLocaleDateString(undefined, { weekday: "short" });
}

export function dayLabel(iso: ISODate): string {
  return parseIso(iso).toLocaleDateString(undefined, {
    weekday: "long",
    month: "short",
    day: "numeric",
  });
}

/**
 * True when a span [start .. start+spanDays-1] covers `day`. Used to place a multi-day meal
 * into every day it touches.
 */
export function spanCoversDay(start: ISODate, spanDays: number, day: ISODate): boolean {
  const offset = daysBetween(start, day);
  return offset >= 0 && offset < Math.max(1, spanDays);
}
