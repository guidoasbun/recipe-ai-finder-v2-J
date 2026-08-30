package io.asbun.backend.ingest;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses the TheMealDB Kaggle xlsx export: one .xlsx per country in a directory, each sheet
 * with columns [food, image_url, instructions, country, ingredients, quantity]. The list
 * columns are stringified Python lists, e.g. {@code ['step one', 'step two']}, and contain
 * HTML entities (e.g. &#189; = ½). ingredients and quantity are parallel arrays.
 */
@Slf4j
public class XlsxMealDbSource implements RecipeSource {

    private static final String SOURCE_NAME = "TheMealDB";
    private static final String SOURCE_LICENSE = "TheMealDB (Kaggle export) - free for education/development; attribution requested";

    private final Path directory;

    public XlsxMealDbSource(Path directory) {
        this.directory = directory;
    }

    @Override
    public String name() {
        return SOURCE_NAME;
    }

    @Override
    public List<ParsedRecipe> load() {
        List<ParsedRecipe> recipes = new ArrayList<>();
        File dir = directory.toFile();
        File[] files = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".xlsx") && !n.startsWith("~$"));
        if (files == null || files.length == 0) {
            log.warn("No .xlsx files found in {}", directory);
            return recipes;
        }
        for (File file : files) {
            try (InputStream in = new FileInputStream(file);
                 Workbook wb = new XSSFWorkbook(in)) {
                parseSheet(wb.getSheetAt(0), file.getName(), recipes);
            } catch (Exception e) {
                log.warn("Failed to parse {}: {}", file.getName(), e.getMessage());
            }
        }
        log.info("{}: parsed {} recipes from {} files", SOURCE_NAME, recipes.size(), files.length);
        return recipes;
    }

    private void parseSheet(Sheet sheet, String fileName, List<ParsedRecipe> out) {
        int firstRow = sheet.getFirstRowNum();
        Row header = sheet.getRow(firstRow);
        if (header == null) {
            return;
        }
        String countryFromFile = fileName.replaceAll("\\.xlsx$", "");

        for (int i = firstRow + 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null) {
                continue;
            }
            String food = cell(row, 0);
            String imageUrl = cell(row, 1);
            String instructions = cell(row, 2);
            String country = cell(row, 3);
            String ingredientsRaw = cell(row, 4);
            String quantityRaw = cell(row, 5);

            if (food == null || food.isBlank()) {
                continue;
            }
            String title = decode(food).trim();

            List<String> names = parseList(ingredientsRaw);
            List<String> qtys = parseList(quantityRaw);
            List<String> ingredients = new ArrayList<>();
            for (int k = 0; k < names.size(); k++) {
                String n = names.get(k).trim();
                String q = k < qtys.size() ? qtys.get(k).trim() : "";
                ingredients.add(q.isBlank() ? n : (q + " " + n));
            }

            List<String> steps = parseList(instructions);

            String ctry = (country == null || country.isBlank()) ? countryFromFile : country.trim();
            String sourceId = SOURCE_NAME + ":" + ctry + ":" + title;

            out.add(new ParsedRecipe(
                    sourceId,
                    title,
                    null,
                    ingredients,
                    steps,
                    decode(imageUrl),
                    SOURCE_NAME,
                    decode(imageUrl) != null ? "https://www.themealdb.com" : null,
                    SOURCE_LICENSE,
                    ctry
            ));
        }
    }

    private String cell(Row row, int idx) {
        Cell c = row.getCell(idx);
        if (c == null) {
            return null;
        }
        return switch (c.getCellType()) {
            case STRING -> c.getStringCellValue();
            case NUMERIC -> String.valueOf(c.getNumericCellValue());
            case BOOLEAN -> String.valueOf(c.getBooleanCellValue());
            default -> null;
        };
    }

    /**
     * Parses a stringified Python list ("['a', 'b']") into its string items, decoding entities.
     * Uses a single linear scan (no regex) to avoid catastrophic backtracking on long strings.
     */
    private List<String> parseList(String raw) {
        List<String> items = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return items;
        }
        int i = 0;
        int n = raw.length();
        while (i < n) {
            char c = raw.charAt(i);
            if (c == '\'' || c == '"') {
                char quote = c;
                i++;
                StringBuilder sb = new StringBuilder();
                while (i < n) {
                    char ch = raw.charAt(i);
                    if (ch == '\\' && i + 1 < n) {
                        char next = raw.charAt(i + 1);
                        switch (next) {
                            case 'n', 't', 'r' -> sb.append(' ');
                            default -> sb.append(next);
                        }
                        i += 2;
                    } else if (ch == quote) {
                        i++;
                        break;
                    } else {
                        sb.append(ch);
                        i++;
                    }
                }
                String val = decode(sb.toString().trim());
                if (!val.isBlank()) {
                    items.add(val);
                }
            } else {
                i++;
            }
        }
        return items;
    }

    /** Decodes the numeric/basic HTML entities present in the dataset. */
    private String decode(String s) {
        if (s == null) {
            return null;
        }
        return s.replace("&#189;", "½")
                .replace("&#188;", "¼")
                .replace("&#190;", "¾")
                .replace("&#8531;", "⅓")
                .replace("&#8532;", "⅔")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
    }
}
