package com.xetax.crm.common.util;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal RFC-4180-ish CSV parser: quoted values, embedded commas/newlines,
 * escaped quotes, UTF-8 BOM. Shared by record bulk upload and WhatsApp
 * campaign CSV audiences so both accept exactly the same files.
 */
public final class CsvParser {

    private CsvParser() {}

    public static List<List<String>> parse(InputStream input) throws Exception {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {

            List<List<String>> rows = new ArrayList<>();
            List<String> current = new ArrayList<>();
            StringBuilder cell = new StringBuilder();
            boolean inQuotes = false;
            int ch;
            boolean first = true;

            while ((ch = reader.read()) != -1) {
                char c = (char) ch;
                if (first) {
                    first = false;
                    if (c == '﻿') continue; // strip BOM (Excel exports)
                }
                if (inQuotes) {
                    if (c == '"') {
                        reader.mark(1);
                        int next = reader.read();
                        if (next == '"') {
                            cell.append('"');
                        } else {
                            inQuotes = false;
                            if (next != -1) reader.reset();
                        }
                    } else {
                        cell.append(c);
                    }
                } else if (c == '"') {
                    inQuotes = true;
                } else if (c == ',') {
                    current.add(cell.toString());
                    cell.setLength(0);
                } else if (c == '\n' || c == '\r') {
                    if (c == '\r') {
                        reader.mark(1);
                        int next = reader.read();
                        if (next != '\n' && next != -1) reader.reset();
                    }
                    current.add(cell.toString());
                    cell.setLength(0);
                    rows.add(current);
                    current = new ArrayList<>();
                } else {
                    cell.append(c);
                }
            }
            if (cell.length() > 0 || !current.isEmpty()) {
                current.add(cell.toString());
                rows.add(current);
            }
            rows.removeIf(r -> r.stream().allMatch(v -> v == null || v.isBlank()));
            return rows;
        }
    }
}
