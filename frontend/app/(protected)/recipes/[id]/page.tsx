import { getSession } from "@/lib/session";
import { apiFetch } from "@/lib/api";
import { Recipe } from "@/types/recipe";
import { notFound } from "next/navigation";

export default async function RecipeDetailPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  const token = await getSession();
  const res = await apiFetch(`/api/recipes/${id}`, {}, token ?? undefined);

  if (!res.ok) notFound();
  const recipe: Recipe = await res.json();

  return (
    <div className="mx-auto max-w-2xl">
      {recipe.imageUrl && (
        <img src={recipe.imageUrl} alt={recipe.title} className="mb-6 w-full rounded-2xl object-cover h-64" />
      )}
      <h1 className="mb-2 text-3xl font-bold text-gray-900">{recipe.title}</h1>
      <p className="mb-6 text-gray-500">{recipe.description}</p>

      <section className="mb-6">
        <h2 className="mb-3 text-lg font-semibold text-gray-800">Ingredients</h2>
        <ul className="space-y-1">
          {recipe.ingredients.map((ing, i) => (
            <li key={i} className="flex items-start gap-2 text-sm text-gray-700">
              <span className="mt-1 h-1.5 w-1.5 rounded-full bg-blue-500 flex-shrink-0" />
              {ing}
            </li>
          ))}
        </ul>
      </section>

      <section>
        <h2 className="mb-3 text-lg font-semibold text-gray-800">Steps</h2>
        <ol className="space-y-3">
          {recipe.steps.map((step, i) => (
            <li key={i} className="flex gap-3 text-sm text-gray-700">
              <span className="flex h-6 w-6 flex-shrink-0 items-center justify-center rounded-full bg-blue-600 text-xs font-bold text-white">
                {i + 1}
              </span>
              {step}
            </li>
          ))}
        </ol>
      </section>
    </div>
  );
}
