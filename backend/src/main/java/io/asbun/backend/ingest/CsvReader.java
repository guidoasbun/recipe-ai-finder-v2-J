package io.asbun.backend.ingest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal RFC-4180 CSV reader supporting quoted fields with embedded commas, quotes, and
 * newlines. Shared by the dataset parsers. Streaming character scan (no regex, no
 * backtracking).
 */
final class CsvReader {

    private CsvReader() {}

    static List<List<String>> readAll(Reader in) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        List<String> current = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean fieldStarted = false;

        try (BufferedReader br = new BufferedReader(in)) {
            int ci;
            while ((ci = br.read()) != -1) {
                char c = (char) ci;
                if (inQuotes) {
                    if (c == '"') {
                        int next = br.read();
                        if (next == '"') {
                            field.append('"'); // escaped quote
                        } else {
                            inQuotes = false;
                            if (next != -1) {
                                c = (char) next;
                                if (c == ',') {
                                    current.add(field.toString());
                                    field.setLength(0);
                                    fieldStarted = false;
                                } else if (c == '\n') {
                                    current.add(field.toString());
                                    field.setLength(0);
                                    rows.add(current);
                                    current = new ArrayList<>();
                                    fieldStarted = false;
                                } else if (c != '\r') {
                                    field.append(c);
                                }
                            }
                        }
                    } else {
                        field.append(c);
                    }
                } else {
                    if (c == '"' && !fieldStarted) {
                        inQuotes = true;
                        fieldStarted = true;
                    } else if (c == ',') {
                        current.add(field.toString());
                        field.setLength(0);
                        fieldStarted = false;
                    } else if (c == '\n') {
                        current.add(field.toString());
                        field.setLength(0);
                        rows.add(current);
                        current = new ArrayList<>();
                        fieldStarted = false;
                    } else if (c == '\r') {
                        // ignore
                    } else {
                        field.append(c);
                        fieldStarted = true;
                    }
                }
            }
        }
        if (field.length() > 0 || !current.isEmpty()) {
            current.add(field.toString());
            rows.add(current);
        }
        return rows;
    }

    /**
     * Parses a JSON-style string array (e.g. {@code ["a", "b"]}) into its items using a
     * linear scan (no regex). Handles escaped quotes; treats \n as a space.
     */
    static List<String> parseJsonStringArray(String raw) {
        List<String> items = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return items;
        }
        int i = 0;
        int n = raw.length();
        while (i < n) {
            char c = raw.charAt(i);
            if (c == '"') {
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
                    } else if (ch == '"') {
                        i++;
                        break;
                    } else {
                        sb.append(ch);
                        i++;
                    }
                }
                String val = sb.toString().trim();
                if (!val.isBlank()) {
                    items.add(val);
                }
            } else {
                i++;
            }
        }
        return items;
    }
}
