package io.asbun.backend.ingest;

import lombok.extern.slf4j.Slf4j;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 2 source: parses the RecipeNLG dataset CSV (full set ~2.2M recipes).
 *
 * <p>Standard RecipeNLG columns: an index column, {@code title}, {@code ingredients}
 * (JSON string array), {@code directions} (JSON string array), {@code link}, {@code source},
 * {@code NER} (JSON string array of ingredient names). This source enforces a hard record
 * cap so the <b>in-app</b> backend stays within its memory ceiling (~50K). Ingesting the
 * full 2.2M requires the OpenSearch backend + {@link BatchEmbeddingStrategy} (see design.md).
 *
 * <p>NOTE: not yet run against a real file — provided so Phase 2 can proceed by dropping the
 * RecipeNLG CSV into place and pointing the ingestion runner at it.
 */
@Slf4j
public class RecipeNlgCsvSource implements RecipeSource {

    private static final String SOURCE_NAME = "RecipeNLG";
    private static final String SOURCE_LICENSE = "RecipeNLG - research/non-commercial use; see dataset license";

    private final Path csvFile;
    private final int maxRecords;

    /**
     * @param csvFile    path to the RecipeNLG CSV
     * @param maxRecords hard cap on recipes parsed (keeps in-app backend within its ceiling)
     */
    public RecipeNlgCsvSource(Path csvFile, int maxRecords) {
        this.csvFile = csvFile;
        this.maxRecords = maxRecords;
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
            int titleIdx = header.indexOf("title");
            int ingIdx = header.indexOf("ingredients");
            int dirIdx = header.indexOf("directions");
            int linkIdx = header.indexOf("link");
            int sourceIdx = header.indexOf("source");

            if (titleIdx < 0 || ingIdx < 0 || dirIdx < 0) {
                log.warn("{}: unexpected header {}", SOURCE_NAME, header);
                return recipes;
            }

            for (int r = 1; r < rows.size() && recipes.size() < maxRecords; r++) {
                List<String> row = rows.get(r);
                String title = get(row, titleIdx);
                if (title == null || title.isBlank()) {
                    continue;
                }
                title = title.trim();

                List<String> ingredients = CsvReader.parseJsonStringArray(get(row, ingIdx));
                List<String> steps = CsvReader.parseJsonStringArray(get(row, dirIdx));
                String link = normalizeLink(get(row, linkIdx));
                String origin = get(row, sourceIdx);

                // RecipeNLG links are often bare hosts ("www.site.com/recipe"); use as the
                // stable id when present, else fall back to the title.
                String sourceId = SOURCE_NAME + ":" + (link != null && !link.isBlank() ? link : title);

                recipes.add(new ParsedRecipe(
                        sourceId,
                        title,
                        origin != null && !origin.isBlank() ? "Source: " + origin : null,
                        ingredients,
                        steps,
                        null,
                        SOURCE_NAME,
                        link,
                        SOURCE_LICENSE,
                        null
                ));
            }
        } catch (Exception e) {
            log.warn("Failed to read {}: {}", csvFile, e.getMessage());
        }
        log.info("{}: parsed {} recipes (cap {})", SOURCE_NAME, recipes.size(), maxRecords);
        return recipes;
    }

    private String get(List<String> row, int idx) {
        if (idx < 0 || idx >= row.size()) {
            return null;
        }
        return row.get(idx);
    }

    private String normalizeLink(String link) {
        if (link == null || link.isBlank()) {
            return null;
        }
        String l = link.trim();
        if (l.startsWith("http://") || l.startsWith("https://")) {
            return l;
        }
        return "https://" + l;
    }
}
