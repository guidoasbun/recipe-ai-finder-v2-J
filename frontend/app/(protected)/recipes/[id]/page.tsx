import { getSession } from "@/lib/session";
import { apiFetch } from "@/lib/api";
import { Recipe } from "@/types/recipe";
import { notFound } from "next/navigation";
import DeleteRecipeButton from "@/components/recipe/DeleteRecipeButton";
import { MODELS, IMAGE_MODELS } from "@/lib/constants";

export default async function RecipeDetailPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  const token = await getSession();
  const res = await apiFetch(`/api/recipes/${id}`, {}, token ?? undefined);

  if (!res.ok) notFound();
  const recipe: Recipe = await res.json();

  const modelLabel = MODELS.find((m) => m.id === recipe.model)?.label;
  const imageModelLabel = IMAGE_MODELS.find(
    (m) => m.id === recipe.imageModel,
  )?.label;

  return (
    <div className="mx-auto max-w-2xl">
      {recipe.imageUrl && (
        <img
          src={recipe.imageUrl}
          alt={recipe.title}
          className="mb-6 w-full rounded-2xl object-cover h-64"
        />
      )}
      <h1 className="mb-2 text-3xl font-bold text-gray-900">{recipe.title}</h1>

      {(modelLabel || imageModelLabel) && (
        <div className="mb-3 flex flex-col gap-1">
          {modelLabel && (
            <span className="text-xl text-gray-700">
              <span className="font-medium text-gray-900">Text:</span>{" "}
              {modelLabel}
            </span>
          )}
          {recipe.textGenerationMs && (
            <span className="w-fit rounded-full bg-blue-50 px-2 py-0.5 text-xs text-blue-600">
              AI{" "}
              {recipe.textGenerationMs >= 1000
                ? `${(recipe.textGenerationMs / 1000).toFixed(1)}s`
                : `${recipe.textGenerationMs}ms`}
            </span>
          )}
          {imageModelLabel && (
            <span className="text-xl text-gray-700">
              <span className="font-medium text-gray-900">Image:</span>{" "}
              {imageModelLabel}
            </span>
          )}
        </div>
      )}

      {(recipe.imageWidth ||
        recipe.imageType ||
        recipe.imageSizeBytes ||
        recipe.imageGenerationMs) && (
        <div className="mb-4 flex flex-wrap gap-2">
          {recipe.imageGenerationMs && (
            <span className="rounded-full bg-orange-50 px-2.5 py-1 text-xs text-orange-600">
              AI{" "}
              {recipe.imageGenerationMs >= 1000
                ? `${(recipe.imageGenerationMs / 1000).toFixed(1)}s`
                : `${recipe.imageGenerationMs}ms`}
            </span>
          )}
          {recipe.imageWidth && recipe.imageHeight && (
            <span className="rounded-full bg-gray-100 px-2.5 py-1 text-xs text-gray-600">
              {recipe.imageWidth} × {recipe.imageHeight} px
            </span>
          )}
          {recipe.imageType && (
            <span className="rounded-full bg-gray-100 px-2.5 py-1 text-xs text-gray-600">
              {recipe.imageType}
            </span>
          )}
          {recipe.imageSizeBytes && (
            <span className="rounded-full bg-gray-100 px-2.5 py-1 text-xs text-gray-600">
              {recipe.imageSizeBytes < 1024 * 1024
                ? `${(recipe.imageSizeBytes / 1024).toFixed(1)} KB`
                : `${(recipe.imageSizeBytes / (1024 * 1024)).toFixed(2)} MB`}
            </span>
          )}
        </div>
      )}
      <p className="mb-6 text-gray-500">{recipe.description}</p>

      <section className="mb-6">
        <h2 className="mb-3 text-lg font-semibold text-gray-800">
          Ingredients
        </h2>
        <ul className="space-y-1">
          {recipe.ingredients.map((ing, i) => (
            <li
              key={i}
              className="flex items-start gap-2 text-sm text-gray-700"
            >
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

      <div className="mt-8">
        <DeleteRecipeButton recipeId={recipe.recipeId} />
      </div>
    </div>
  );
}
