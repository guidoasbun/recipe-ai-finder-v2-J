package io.asbun.backend.ingest;

import lombok.extern.slf4j.Slf4j;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * RecipeNLG dataset CSV source (full set ~2.2M recipes).
 *
 * <p>Standard RecipeNLG columns: an index column, {@code title}, {@code ingredients}
 * (JSON string array), {@code directions} (JSON string array), {@code link}, {@code source},
 * {@code NER}. Supports a window (skip + cap) so the full dataset can be processed in slices.
 *
 * <p>{@link #stream(Consumer)} pushes each recipe as it is parsed and NEVER accumulates the
 * dataset in memory — this is the path used for the full 2.2M load. {@link #load()} collects
 * into a list (bounded by the cap) and is only appropriate for small windows.
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
    private final int skipRecords;

    public RecipeNlgCsvSource(Path csvFile, int maxRecords) {
        this(csvFile, maxRecords, 0);
    }

    /**
     * @param maxRecords  max data records to collect after skipping (window size)
     * @param skipRecords number of leading data records to skip (window offset) — lets the full
     *                    dataset be processed one window at a time across separate runs
     */
    public RecipeNlgCsvSource(Path csvFile, int maxRecords, int skipRecords) {
        this.csvFile = csvFile;
        this.maxRecords = maxRecords;
        this.skipRecords = Math.max(0, skipRecords);
    }

    @Override
    public String name() {
        return SOURCE_NAME;
    }

    @Override
    public List<ParsedRecipe> load() {
        // Collects the (capped) window into a list. Only safe for small caps — the full-set
        // path uses stream(...) to avoid materializing millions of records.
        List<ParsedRecipe> recipes = new ArrayList<>();
        stream(recipes::add);
        return recipes;
    }

    @Override
    public void stream(Consumer<ParsedRecipe> consumer) {
        // Header column indices resolved from the first row; -1 until then.
        int[] idx = {-1, -1, -1, -1, -1}; // title, ingredients, directions, link, source
        boolean[] headerSeen = {false};
        long[] dataRowsSeen = {0}; // data rows encountered (for the skip offset)
        long[] emitted = {0};      // recipes pushed to the consumer (for the cap)

        try (Reader reader = Files.newBufferedReader(csvFile, StandardCharsets.UTF_8)) {
            // Stream row-by-row; never accumulate. Each parsed recipe is handed to the consumer
            // and released, so the full ~2.2M-row file processes in bounded memory.
            CsvReader.stream(reader, row -> {
                if (!headerSeen[0]) {
                    headerSeen[0] = true;
                    idx[0] = row.indexOf("title");
                    idx[1] = row.indexOf("ingredients");
                    idx[2] = row.indexOf("directions");
                    idx[3] = row.indexOf("link");
                    idx[4] = row.indexOf("source");
                    if (idx[0] < 0 || idx[1] < 0 || idx[2] < 0) {
                        log.warn("{}: unexpected header {}", SOURCE_NAME, row);
                        return false; // stop
                    }
                    return true; // continue to data rows
                }

                // Skip the first skipRecords data rows (window offset). Count every data row so
                // the offset is stable and reproducible across runs regardless of blank titles.
                dataRowsSeen[0]++;
                if (dataRowsSeen[0] <= skipRecords) {
                    return true; // keep scanning until we reach the window start
                }

                String title = get(row, idx[0]);
                if (title != null && !title.isBlank()) {
                    title = title.trim();
                    List<String> ingredients = CsvReader.parseJsonStringArray(get(row, idx[1]));
                    List<String> steps = CsvReader.parseJsonStringArray(get(row, idx[2]));
                    String link = normalizeLink(get(row, idx[3]));
                    String origin = get(row, idx[4]);
                    String sourceId = SOURCE_NAME + ":" + (link != null && !link.isBlank() ? link : title);

                    consumer.accept(new ParsedRecipe(
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
                    emitted[0]++;
                }
                // Stop reading once the cap is reached (enforced DURING the scan).
                return emitted[0] < maxRecords;
            });
        } catch (Exception e) {
            log.warn("Failed to read {}: {}", csvFile, e.getMessage());
        }
        log.info("{}: streamed {} recipes (skip {}, cap {})",
                SOURCE_NAME, emitted[0], skipRecords, maxRecords);
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
