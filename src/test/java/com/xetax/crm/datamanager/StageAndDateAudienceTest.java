package com.xetax.crm.datamanager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xetax.crm.data_manager.dto.SeacrhDto.RecordSearchRequest;
import com.xetax.crm.data_manager.entity.FormField;
import com.xetax.crm.data_manager.enums.FieldType;
import com.xetax.crm.data_manager.util.CriteriaBuilderImpl;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A campaign could only be aimed at a whole form. The filter map cannot narrow
 * it, because it is keyed by form fieldKey and reaches only inside a record's
 * `data` — the stage a record sits in and the day it was created are columns
 * on the record itself.
 */
class StageAndDateAudienceTest {

    private CriteriaBuilderImpl builder;
    private List<FormField> fields;

    @BeforeEach
    void setUp() {
        builder = new CriteriaBuilderImpl(new ObjectMapper());
        fields = List.of(FormField.builder()
                .fieldKey("full_name")
                .fieldType(FieldType.TEXT)
                .build());
    }

    private List<Criteria> build(RecordSearchRequest request) {
        return builder.build(5L, request, fields);
    }

    /** The clause raised against one document key, or null if there is none. */
    private Document clauseFor(List<Criteria> criteria, String key) {
        for (Criteria one : criteria) {
            Document document = one.getCriteriaObject();
            if (document.containsKey(key)) {
                return document;
            }
        }
        return null;
    }

    // ------------------------------------------------------------- unchanged

    @Test
    void anUnnarrowedSearchIsExactlyWhatItAlwaysWas() {
        List<Criteria> criteria = build(new RecordSearchRequest());

        assertEquals(1, criteria.size(), "only the form itself");
        assertEquals(5L, criteria.get(0).getCriteriaObject().get("formId"));
    }

    // ----------------------------------------------------------------- stage

    @Test
    void aStageNarrowsTheRecordsToThatStage() {
        RecordSearchRequest request = new RecordSearchRequest();
        request.setStageId(9L);

        Document clause = clauseFor(build(request), "stageId");

        assertNotNull(clause, "no stage clause was built");
        assertEquals(9L, clause.get("stageId"));
    }

    @Test
    void noStageMeansEveryStage() {
        assertNull(clauseFor(build(new RecordSearchRequest()), "stageId"));
    }

    private static void assertNull(Object value) {
        assertTrue(value == null, "expected no clause, got " + value);
    }

    // ------------------------------------------------------------ date range

    @Test
    void aDateRangeCoversBothEndDaysWholly() {
        RecordSearchRequest request = new RecordSearchRequest();
        request.setCreatedFrom(LocalDate.of(2026, 9, 1));
        request.setCreatedTo(LocalDate.of(2026, 9, 30));

        Document clause = clauseFor(build(request), "createdAt");

        assertNotNull(clause, "no created-at clause was built");
        Document range = (Document) clause.get("createdAt");
        assertEquals(LocalDateTime.of(2026, 9, 1, 0, 0), range.get("$gte"));

        // A record created at 4pm on the last day is still inside the range —
        // using the bare date would have cut the whole final day off.
        LocalDateTime upper = (LocalDateTime) range.get("$lte");
        assertEquals(30, upper.getDayOfMonth());
        assertTrue(upper.isAfter(LocalDateTime.of(2026, 9, 30, 23, 59)),
                "upper bound was " + upper);
    }

    @Test
    void onlyAStartDateLeavesTheRangeOpenEnded() {
        RecordSearchRequest request = new RecordSearchRequest();
        request.setCreatedFrom(LocalDate.of(2026, 9, 1));

        Document range = (Document) clauseFor(build(request), "createdAt").get("createdAt");

        assertTrue(range.containsKey("$gte"));
        assertFalse(range.containsKey("$lte"));
    }

    @Test
    void onlyAnEndDateLeavesTheStartOpen() {
        RecordSearchRequest request = new RecordSearchRequest();
        request.setCreatedTo(LocalDate.of(2026, 9, 30));

        Document range = (Document) clauseFor(build(request), "createdAt").get("createdAt");

        assertFalse(range.containsKey("$gte"));
        assertTrue(range.containsKey("$lte"));
    }

    @Test
    void noDatesMeanNoDateClause() {
        assertNull(clauseFor(build(new RecordSearchRequest()), "createdAt"));
    }

    // --------------------------------------------------------- both together

    @Test
    void aStageAndARangeNarrowTogether() {
        RecordSearchRequest request = new RecordSearchRequest();
        request.setStageId(9L);
        request.setCreatedFrom(LocalDate.of(2026, 9, 1));
        request.setCreatedTo(LocalDate.of(2026, 9, 30));

        List<Criteria> criteria = build(request);

        assertNotNull(clauseFor(criteria, "formId"));
        assertNotNull(clauseFor(criteria, "stageId"));
        assertNotNull(clauseFor(criteria, "createdAt"));
    }
}
