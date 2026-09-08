package com.xetax.crm.whatsapp;

import com.xetax.crm.common.util.CsvParser;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CsvParserTest {

    private List<List<String>> parse(String csv) throws Exception {
        return CsvParser.parse(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void quotedCommasAndEscapedQuotesSurvive() throws Exception {
        List<List<String>> rows = parse("phone,name\n\"919876543210\",\"Shah, \"\"PJ\"\"\"\n");
        assertEquals(2, rows.size());
        assertEquals("919876543210", rows.get(1).get(0));
        assertEquals("Shah, \"PJ\"", rows.get(1).get(1));
    }

    @Test
    void bomAndCrlfHandled() throws Exception {
        List<List<String>> rows = parse("﻿phone\r\n1\r\n2\r\n");
        assertEquals(3, rows.size());
        assertEquals("phone", rows.get(0).get(0));
    }

    @Test
    void emptyTrailingLinesDropped() throws Exception {
        assertEquals(2, parse("a,b\n1,2\n\n\n").size());
    }
}
