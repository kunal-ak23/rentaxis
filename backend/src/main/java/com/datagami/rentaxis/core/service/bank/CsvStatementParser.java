package com.datagami.rentaxis.core.service.bank;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.domain.entity.BankStatementProfile;
import org.springframework.stereotype.Component;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** RFC 4180 CSV (quoted fields, doubled quotes, CRLF or LF), UTF-8 with or without a BOM, else Windows-1252. */
@Component
public class CsvStatementParser implements StatementParser {

    @Override
    public BankStatementProfile.FileKind kind() {
        return BankStatementProfile.FileKind.CSV;
    }

    @Override
    public StatementGrid read(byte[] bytes, String sheetName, String delimiter) {
        String text = decode(bytes);
        if (!text.isEmpty() && text.charAt(0) == '﻿') text = text.substring(1);
        char d = delimiter == null || delimiter.isEmpty() ? detect(text) : delimiter.equals("\\t") ? '\t' : delimiter.charAt(0);
        return new StatementGrid(parse(text, d), List.of(), null);
    }

    static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, java.nio.charset.Charset.forName("windows-1252"));
        }
    }

    /** The delimiter that splits the first non-blank lines most evenly: comma, semicolon, tab or pipe. */
    static char detect(String text) {
        String[] lines = text.split("\r?\n", 12);
        char best = ',';
        int bestScore = -1;
        for (char c : new char[]{',', ';', '\t', '|'}) {
            int min = Integer.MAX_VALUE;
            int seen = 0;
            for (String l : lines) {
                if (l.isBlank()) continue;
                int n = (int) l.chars().filter(ch -> ch == c).count();
                min = Math.min(min, n);
                seen++;
            }
            int score = seen == 0 ? 0 : min;
            if (score > bestScore) {
                bestScore = score;
                best = c;
            }
        }
        return best;
    }

    static List<List<Object>> parse(String text, char d) {
        List<List<Object>> rows = new ArrayList<>();
        List<Object> row = new ArrayList<>();
        StringBuilder f = new StringBuilder();
        boolean quoted = false;
        boolean fieldStarted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        f.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    f.append(c);
                }
                continue;
            }
            if (c == '"' && !fieldStarted) {
                quoted = true;
                fieldStarted = true;
            } else if (c == d) {
                row.add(cell(f));
                f.setLength(0);
                fieldStarted = false;
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(cell(f));
                f.setLength(0);
                fieldStarted = false;
                rows.add(row);
                row = new ArrayList<>();
                if (rows.size() > MAX_GRID_ROWS) throw tooMany();
            } else {
                f.append(c);
                fieldStarted = true;
            }
        }
        if (fieldStarted || f.length() > 0 || !row.isEmpty()) {
            row.add(cell(f));
            rows.add(row);
        }
        if (rows.size() > MAX_GRID_ROWS) throw tooMany();
        return rows;
    }

    private static Object cell(StringBuilder f) {
        String s = f.toString().trim();
        return s.isEmpty() ? null : s;
    }

    static BusinessRuleViolationException tooMany() {
        return new BusinessRuleViolationException("A statement may have at most " + MAX_ROWS + " lines; split the file");
    }
}
