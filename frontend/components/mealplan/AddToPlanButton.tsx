"use client";

import { useEffect, useRef, useState } from "react";
import { CalendarPlus, Check, Loader2, X } from "lucide-react";
import { RecipeSource } from "@/types/mealPlan";
import { MEAL_SLOTS, MealSlot, mealSlotLabel } from "@/lib/mealSlots";
import { addEntry, getDefaultMealPlan } from "@/lib/mealPlanApi";
import { todayIso } from "@/lib/calendar";

const SUCCESS_MS = 2500;

interface Props {
  /** Which library the recipe lives in. */
  source: RecipeSource;
  /** catalogRecipeId (CATALOG) or recipeId (SAVED). */
  recipeId: string;
  /** Recipe title, used in the sheet header and the confirmation. */
  title?: string;
  /**
   * Visual style:
   *  - "button": full-width labeled button for a card action row (saved recipes).
   *  - "icon": compact icon-only control for the dense catalog grid (browse). Still labelled
   *    for assistive tech.
   */
  variant?: "button" | "icon";
  className?: string;
}

type Phase = "idle" | "submitting" | "success" | "error";

/**
 * Adds a known recipe to the user's single meal-plan calendar. This is the inverse of the
 * in-calendar RecipePicker: the recipe is fixed, so the sheet only collects *when* (date,
 * slot, meal-prep span). It reuses the existing planner API — getDefaultMealPlan (get-or-create
 * the user's calendar) then addEntry — so there is no new backend surface.
 *
 * Self-contained: it owns its own open/submitting/success/error state so host cards don't have
 * to. A blank recipeId disables the control (never adds an empty reference).
 */
export default function AddToPlanButton({
  source,
  recipeId,
  title,
  variant = "button",
  className,
}: Props) {
  const [open, setOpen] = useState(false);
  const [phase, setPhase] = useState<Phase>("idle");
  const [errorMsg, setErrorMsg] = useState<string | null>(null);

  const disabled = !recipeId;

  // Auto-clear the transient "added" confirmation.
  useEffect(() => {
    if (phase !== "success") return;
    const t = setTimeout(() => setPhase("idle"), SUCCESS_MS);
    return () => clearTimeout(t);
  }, [phase]);

  if (disabled) return null;

  // Stop the click from bubbling into a surrounding <Link> (the browse card wraps the whole
  // surface in a next/link) and from following it.
  function openSheet(e: React.MouseEvent) {
    e.preventDefault();
    e.stopPropagation();
    setErrorMsg(null);
    setOpen(true);
  }

  async function handleConfirm(sel: { date: string; slot: MealSlot; spanDays: number }) {
    setPhase("submitting");
    setErrorMsg(null);
    try {
      const plan = await getDefaultMealPlan();
      await addEntry(plan.mealPlanId, {
        date: sel.date,
        slot: sel.slot,
        source,
        recipeId,
        spanDays: sel.spanDays,
      });
      setOpen(false);
      setPhase("success");
    } catch {
      // The sheet stays open so the user can retry without re-picking.
      setPhase("error");
      setErrorMsg("Couldn't add this to your plan. Please try again.");
    }
  }

  const busy = phase === "submitting";
  const added = phase === "success";

  return (
    <>
      {variant === "icon" ? (
        <button
          type="button"
          aria-label={`Add ${title ?? "recipe"} to plan`}
          title="Add to plan"
          onClick={openSheet}
          className={
            className ??
            "inline-flex items-center justify-center rounded-full bg-white/90 p-2 text-gray-700 shadow-sm backdrop-blur hover:bg-white hover:text-blue-600"
          }
        >
          {added ? <Check className="h-4 w-4 text-green-600" /> : <CalendarPlus className="h-4 w-4" />}
        </button>
      ) : (
        <button
          type="button"
          onClick={openSheet}
          className={
            className ??
            "flex-1 inline-flex items-center justify-center gap-1.5 rounded-lg border border-gray-300 px-3 py-2 text-xs font-medium text-gray-700 hover:bg-gray-50"
          }
        >
          {added ? (
            <>
              <Check className="h-4 w-4 text-green-600" /> Added
            </>
          ) : (
            <>
              <CalendarPlus className="h-4 w-4" /> Add to plan
            </>
          )}
        </button>
      )}

      {open && (
        <AddToPlanSheet
          title={title}
          busy={busy}
          errorMsg={errorMsg}
          onConfirm={handleConfirm}
          onClose={() => {
            if (busy) return;
            setOpen(false);
            setPhase("idle");
          }}
        />
      )}
    </>
  );
}

// ── Date/slot picker sheet ────────────────────────────────────────────────────

interface SheetProps {
  title?: string;
  busy: boolean;
  errorMsg: string | null;
  onConfirm: (sel: { date: string; slot: MealSlot; spanDays: number }) => void;
  onClose: () => void;
}

/**
 * Mobile-first bottom-sheet (full-screen on phones, centered card on sm+) that collects the
 * date, meal slot, and optional meal-prep span for a recipe that's already chosen. Mirrors the
 * EntryEditor/RecipePicker sheet skeleton.
 */
function AddToPlanSheet({ title, busy, errorMsg, onConfirm, onClose }: SheetProps) {
  const [date, setDate] = useState(todayIso());
  const [slot, setSlot] = useState<MealSlot>("DINNER");
  const [spanDays, setSpanDays] = useState(1);

  const panelRef = useRef<HTMLDivElement>(null);
  const dateRef = useRef<HTMLInputElement>(null);

  // Modal keyboard/focus behavior: move focus in on open, keep Tab within the sheet, close on
  // Escape, and restore focus to the trigger on close. (aria-modal alone does none of this.)
  useEffect(() => {
    const previouslyFocused = document.activeElement as HTMLElement | null;
    // Focus the first field once the sheet is mounted.
    dateRef.current?.focus();

    function focusable(): HTMLElement[] {
      const root = panelRef.current;
      if (!root) return [];
      return Array.from(
        root.querySelectorAll<HTMLElement>(
          'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
        ),
      ).filter((el) => el.offsetParent !== null || el === document.activeElement);
    }

    function onKeyDown(e: KeyboardEvent) {
      if (e.key === "Escape") {
        e.preventDefault();
        onClose();
        return;
      }
      if (e.key !== "Tab") return;
      const items = focusable();
      if (items.length === 0) return;
      const first = items[0];
      const last = items[items.length - 1];
      const active = document.activeElement as HTMLElement | null;
      // Wrap focus at the edges so it never escapes to content behind the overlay.
      if (e.shiftKey && (active === first || !panelRef.current?.contains(active))) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && active === last) {
        e.preventDefault();
        first.focus();
      }
    }

    document.addEventListener("keydown", onKeyDown);
    return () => {
      document.removeEventListener("keydown", onKeyDown);
      // Restore focus to whatever opened the sheet (the trigger button).
      previouslyFocused?.focus?.();
    };
  }, [onClose]);

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    // Guard against an empty/cleared date so we never post an invalid entry (the required
    // attribute blocks it in-browser; this covers programmatic submits too).
    if (!date) {
      dateRef.current?.focus();
      return;
    }
    onConfirm({ date, slot, spanDays });
  }

  return (
    <div
      className="fixed inset-0 z-50 flex flex-col bg-white sm:items-center sm:justify-center sm:bg-black/40 sm:p-4"
      role="dialog"
      aria-modal="true"
      aria-label={`Add ${title ?? "recipe"} to plan`}
      onClick={(e) => e.stopPropagation()}
    >
      <div
        ref={panelRef}
        className="flex w-full flex-col sm:h-auto sm:max-w-md sm:rounded-xl sm:bg-white sm:shadow-xl"
      >
        <div className="flex items-center justify-between border-b border-gray-200 p-4">
          <div className="min-w-0">
            <h2 className="truncate text-lg font-semibold text-gray-900">Add to plan</h2>
            {title && <p className="truncate text-xs text-gray-500">{title}</p>}
          </div>
          <button
            type="button"
            aria-label="Close"
            onClick={onClose}
            className="rounded-md p-2 text-gray-500 hover:bg-gray-100"
          >
            <X className="h-5 w-5" />
          </button>
        </div>

        <form onSubmit={handleSubmit} className="flex flex-col gap-4 p-4">
          <div className="flex flex-col gap-1">
            <label htmlFor="add-date" className="text-sm font-medium text-gray-700">
              Date
            </label>
            <input
              id="add-date"
              ref={dateRef}
              type="date"
              required
              aria-required="true"
              value={date}
              onChange={(e) => setDate(e.target.value)}
              className="rounded-md border border-gray-300 px-3 py-2 text-sm text-gray-900 focus:border-blue-500 focus:outline-none"
            />
          </div>

          <div className="flex flex-col gap-1">
            <label htmlFor="add-slot" className="text-sm font-medium text-gray-700">
              Meal slot
            </label>
            <select
              id="add-slot"
              value={slot}
              onChange={(e) => setSlot(e.target.value as MealSlot)}
              className="rounded-md border border-gray-300 px-3 py-2 text-sm text-gray-900 focus:border-blue-500 focus:outline-none"
            >
              {MEAL_SLOTS.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </div>

          <div className="flex flex-col gap-1">
            <label htmlFor="add-span" className="text-sm font-medium text-gray-700">
              Meal prep for
            </label>
            <select
              id="add-span"
              value={spanDays}
              onChange={(e) => setSpanDays(Number(e.target.value))}
              className="rounded-md border border-gray-300 px-3 py-2 text-sm text-gray-900 focus:border-blue-500 focus:outline-none"
            >
              {Array.from({ length: 14 }, (_, i) => i + 1).map((n) => (
                <option key={n} value={n}>
                  {n === 1 ? "1 day" : `${n} days`}
                </option>
              ))}
            </select>
            {spanDays > 1 && (
              <span className="text-xs text-gray-400">
                Cook once for {mealSlotLabel(slot)}, covers {spanDays} days.
              </span>
            )}
          </div>

          {errorMsg && (
            <p role="alert" className="rounded-md bg-red-50 p-2 text-xs text-red-700">
              {errorMsg}
            </p>
          )}

          <div className="flex justify-end gap-2 pt-2">
            <button
              type="button"
              onClick={onClose}
              disabled={busy}
              className="rounded-md border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={busy}
              className="inline-flex items-center gap-2 rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
            >
              {busy && <Loader2 className="h-4 w-4 animate-spin" />}
              Add to plan
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
