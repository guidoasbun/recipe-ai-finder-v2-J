export interface Recipe {
  recipeId: string;
  title: string;
  description: string;
  ingredients: string[];
  steps: string[];
  imageUrl?: string;
  imageWidth?: number;
  imageHeight?: number;
  imageType?: string;
  imageSizeBytes?: number;
  imageGenerationMs?: number;
  userId: string;
  createdAt: string;
  model?: string;
  imageModel?: string;
  textGenerationMs?: number;
}

export interface GeneratedRecipe {
  title: string;
  description: string;
  ingredients: string[];
  steps: string[];
  generationMs?: number;
}
