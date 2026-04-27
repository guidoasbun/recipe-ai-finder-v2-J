export interface Recipe {
  recipeId: string;
  title: string;
  description: string;
  ingredients: string[];
  steps: string[];
  imageUrl?: string;
  userId: string;
  createdAt: string;
}

export interface GeneratedRecipe {
  title: string;
  description: string;
  ingredients: string[];
  steps: string[];
}
