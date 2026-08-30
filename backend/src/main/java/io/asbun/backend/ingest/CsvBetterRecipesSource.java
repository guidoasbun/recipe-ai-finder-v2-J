package io.asbun.backend.ingest;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses the "Better Recipes for a Better Life" (AllRecipes-sourced) CSV.
 * Columns include: recipe_name, ingredients (comma-separated string), directions
 * (newline-separated steps), url, cuisine_path, img_src. Uses {@link CsvReader} because
 * fields contain embedded commas and newlines inside quotes.
 */
@Slf4j
public class CsvBetterRecipesSource implements RecipeSource {

    private static final String SOURCE_NAME = "AllRecipes";
    private static final String SOURCE_LICENSE = "Better Recipes for a Better Life (Kaggle, AllRecipes source) - non-commercial use";

    private final Path csvFile;

    public CsvBetterRecipesSource(Path csvFile) {
        this.csvFile = csvFile;
    }

    @Override
    public String name() {
        return SOURCE_NAME;
    }

    @Override
    public List<ParsedRecipe> load() {
        List<ParsedRecipe> recipes = new ArrayList<>();
        try (Reader reader = Files.newBufferedReader(csvFile, StandardCharsets.UTF_8)) {
            List<List<String>> rows = CsvReader.readAll(reader);
            if (rows.isEmpty()) {
                return recipes;
            }
            List<String> header = rows.get(0);
            int nameIdx = header.indexOf("recipe_name");
            int ingIdx = header.indexOf("ingredients");
            int dirIdx = header.indexOf("directions");
            int urlIdx = header.indexOf("url");
            int cuisineIdx = header.indexOf("cuisine_path");
            int imgIdx = header.indexOf("img_src");

            if (nameIdx < 0 || ingIdx < 0 || dirIdx < 0) {
                log.warn("{}: unexpected header {}", SOURCE_NAME, header);
                return recipes;
            }

            for (int r = 1; r < rows.size(); r++) {
                List<String> row = rows.get(r);
                String name = get(row, nameIdx);
                if (name == null || name.isBlank()) {
                    continue;
                }
                String title = name.trim();
                List<String> ingredients = splitIngredients(get(row, ingIdx));
                List<String> steps = splitSteps(get(row, dirIdx));
                String url = get(row, urlIdx);
                String cuisine = cleanCuisine(get(row, cuisineIdx));
                String img = get(row, imgIdx);

                String sourceId = SOURCE_NAME + ":" + (url != null && !url.isBlank() ? url : title);

                recipes.add(new ParsedRecipe(
                        sourceId,
                        title,
                        cuisine,
                        ingredients,
                        steps,
                        img,
                        SOURCE_NAME,
                        url,
                        SOURCE_LICENSE,
                        null
                ));
            }
        } catch (IOException e) {
            log.warn("Failed to read {}: {}", csvFile, e.getMessage());
        }
        log.info("{}: parsed {} recipes", SOURCE_NAME, recipes.size());
        return recipes;
    }

    private String get(List<String> row, int idx) {
        if (idx < 0 || idx >= row.size()) {
            return null;
        }
        return row.get(idx);
    }

    /** Ingredients are one comma-separated string with quantities inline. */
    private List<String> splitIngredients(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String part : raw.split(",")) {
            String p = part.trim();
            if (!p.isBlank()) {
                out.add(p);
            }
        }
        return out;
    }

    /** Directions are newline-separated steps. */
    private List<String> splitSteps(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String line : raw.split("\\r?\\n")) {
            String s = line.trim();
            if (!s.isBlank()) {
                out.add(s);
            }
        }
        return out;
    }

    private String cleanCuisine(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        // e.g. "/Desserts/Fruit Desserts/Apple Dessert Recipes/" -> "Desserts / Fruit Desserts / Apple Dessert Recipes"
        String trimmed = path.replaceAll("^/|/$", "");
        return trimmed.replace("/", " / ").trim();
    }
}
