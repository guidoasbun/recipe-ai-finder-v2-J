import { getSession } from "@/lib/session";
import { apiFetch } from "@/lib/api";
import { Recipe } from "@/types/recipe";
import RecipeCard from "@/components/recipe/RecipeCard";

export default async function RecipesPage() {
  const token = await getSession();
  const res = await apiFetch("/api/recipes", {}, token ?? undefined);
  const recipes: Recipe[] = res.ok ? await res.json() : [];

  return (
    <div>
      <h1 className="mb-6 text-2xl font-bold text-gray-900">Saved Recipes</h1>
      {recipes.length === 0 ? (
        <p className="text-gray-500">No saved recipes yet. Generate some first!</p>
      ) : (
        <div className="grid gap-6 sm:grid-cols-2 lg:grid-cols-3">
          {recipes.map((recipe) => (
            <RecipeCard key={recipe.id} recipe={recipe} saved />
          ))}
        </div>
      )}
    </div>
  );
}
