import Link from "next/link";
import { UtensilsCrossed } from "lucide-react";

export default function RecipeNotFound() {
  return (
    <div className="mx-auto max-w-2xl rounded-lg border border-gray-200 bg-white p-8 text-center">
      <UtensilsCrossed className="mx-auto h-10 w-10 text-gray-400" />
      <h1 className="mt-4 text-xl font-semibold text-gray-900">
        Recipe not found
      </h1>
      <p className="mt-2 text-sm text-gray-500">
        This recipe doesn&apos;t exist or may have been deleted.
      </p>
      <Link
        href="/recipes"
        className="mt-6 inline-flex items-center gap-2 rounded-md bg-blue-600 px-5 py-2 text-sm font-medium text-white hover:bg-blue-700 transition-colors"
      >
        Back to recipes
      </Link>
    </div>
  );
}
