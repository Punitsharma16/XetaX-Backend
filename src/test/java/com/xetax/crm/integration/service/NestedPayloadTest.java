package com.xetax.crm.integration.service;

import com.xetax.crm.data_manager.entity.FormField;
import com.xetax.crm.integration.entity.IntegrationFieldMapping;
import com.xetax.crm.integration.repository.IntegrationFieldMappingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Mapping used to read only the top level of a payload, so a webhook shaped
 * like Shopify's — {"customer": {"email": ...}} — had no reachable email at
 * all. And any key without a mapping was dropped without a word: the sender
 * got 200 OK and the record came out missing a field.
 */
class NestedPayloadTest {

    private IntegrationFieldMappingRepository mappingRepository;
    private PayloadMappingServiceImpl service;

    @BeforeEach
    void setUp() {
        mappingRepository = mock(IntegrationFieldMappingRepository.class);
        service = new PayloadMappingServiceImpl(mappingRepository);
    }

    /** Source fields are stored lowercased by saveMappings; mirror that here. */
    private void mappings(String... sourceThenFieldKey) {
        List<IntegrationFieldMapping> list = new ArrayList<>();
        for (int i = 0; i < sourceThenFieldKey.length; i += 2) {
            list.add(IntegrationFieldMapping.builder()
                    .sourceField(sourceThenFieldKey[i])
                    .formField(FormField.builder()
                            .fieldKey(sourceThenFieldKey[i + 1])
                            .label(sourceThenFieldKey[i + 1])
                            .build())
                    .build());
        }
        when(mappingRepository.findByIntegrationId(1L)).thenReturn(list);
    }

    private static Map<String, Object> json(Object... keyThenValue) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyThenValue.length; i += 2) {
            map.put(String.valueOf(keyThenValue[i]), keyThenValue[i + 1]);
        }
        return map;
    }

    // ------------------------------------------------------------ unchanged

    @Test
    void topLevelKeysStillResolveTheWayTheyAlwaysDid() {
        mappings("mobile", "phone", "customer_name", "full_name");

        MappedPayload result = service.apply(1L, json(
                "Mobile", "9812345678",
                " CUSTOMER_NAME ", "Ravi"));

        assertEquals("9812345678", result.data().get("phone"));
        assertEquals("Ravi", result.data().get("full_name"));
    }

    @Test
    void aMappingOnAWholeObjectStillStoresTheWholeObject() {
        mappings("customer", "blob");

        Map<String, Object> customer = json("email", "a@b.com");
        MappedPayload result = service.apply(1L, json("customer", customer));

        assertEquals(customer, result.data().get("blob"),
                "an integration already mapped to a container must keep working");
    }

    // --------------------------------------------------------------- nested

    @Test
    void aMappingCanReachIntoANestedObject() {
        mappings("customer.email", "email");

        MappedPayload result = service.apply(1L,
                json("customer", json("email", "a@b.com")));

        assertEquals("a@b.com", result.data().get("email"));
    }

    @Test
    void aMappingCanReachIntoAnArray() {
        mappings("items.0.sku", "sku", "items.0.qty", "quantity");

        MappedPayload result = service.apply(1L,
                json("items", List.of(json("sku", "X1", "qty", 2))));

        assertEquals("X1", result.data().get("sku"));
        assertEquals(2, result.data().get("quantity"));
    }

    @Test
    void nestedPathsMatchWhateverCaseTheSenderUses() {
        mappings("customer.billing.city", "city");

        MappedPayload result = service.apply(1L,
                json("Customer", json("BILLING", json(" City ", "Ludhiana"))));

        assertEquals("Ludhiana", result.data().get("city"));
    }

    // ---------------------------------------------------------- diagnostics

    @Test
    void aKeyNobodyMappedIsReportedInsteadOfVanishing() {
        mappings("mobile", "phone");

        MappedPayload result = service.apply(1L, json(
                "mobile", "9812345678",
                "utm_source", "google",
                "city", "Ludhiana"));

        assertTrue(result.ignoredPaths().contains("utm_source"));
        assertTrue(result.ignoredPaths().contains("city"));
        assertFalse(result.ignoredPaths().contains("mobile"));
    }

    @Test
    void aRenameOnTheSendingSideShowsUpOnBothLists() {
        mappings("mobile", "phone");

        // The sender started calling it "phone_number" instead.
        MappedPayload result = service.apply(1L, json("phone_number", "9812345678"));

        assertTrue(result.data().isEmpty(), "nothing could be mapped");
        assertEquals(List.of("phone_number"), result.ignoredPaths());
        assertEquals(List.of("mobile"), result.unmatchedFields(),
                "the mapping that found nothing has to be named too");
    }

    @Test
    void nestedLeavesAreReportedByTheirFullPath() {
        mappings("mobile", "phone");

        MappedPayload result = service.apply(1L, json(
                "mobile", "9812345678",
                "customer", json("email", "a@b.com")));

        assertEquals(List.of("customer.email"), result.ignoredPaths(),
                "a user has to be told the path they can actually map");
    }

    @Test
    void leavesInsideAnObjectSomebodyMappedAreNotReported() {
        mappings("customer", "blob");

        MappedPayload result = service.apply(1L,
                json("customer", json("email", "a@b.com", "city", "Ludhiana")));

        assertTrue(result.ignoredPaths().isEmpty(),
                "the whole object was stored, so nothing inside it was dropped");
    }

    @Test
    void aFullyMappedPayloadReportsNothing() {
        mappings("mobile", "phone");

        MappedPayload result = service.apply(1L, json("mobile", "9812345678"));

        assertTrue(result.ignoredPaths().isEmpty());
        assertTrue(result.unmatchedFields().isEmpty());
    }

    // -------------------------------------------------------------- guards

    @Test
    void aPayloadThatRepeatsAKeyInAnotherCaseNoLongerBlowsUp() {
        mappings("name", "full_name");

        // Collectors.toMap used to throw IllegalStateException here, turning a
        // sloppy payload into a 500.
        assertDoesNotThrow(() -> service.apply(1L, json("Name", "Ravi", "name", "Vanshu")));
    }

    @Test
    void anEndlesslyNestedPayloadStopsAtAFixedDepth() {
        Map<String, Object> deep = json("value", "bottom");
        for (int level = 0; level < 50; level++) {
            deep = json("level" + level, deep);
        }

        Map<String, Object> flat = PayloadFlattener.flatten(deep);

        assertFalse(flat.isEmpty());
        int deepest = flat.keySet().stream()
                .mapToInt(path -> path.split("\\.").length)
                .max().orElse(0);
        assertTrue(deepest <= 10, "stopped descending at " + deepest + " segments");
    }

    @Test
    void aHugeArrayCannotFillMemory() {
        List<Object> many = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            many.add(json("sku", "X" + i));
        }

        Map<String, Object> flat = PayloadFlattener.flatten(json("items", many));

        assertTrue(flat.size() <= 2000, "capped at 2000, got " + flat.size());
    }

    @Test
    void onlyTheFirstFewIgnoredKeysAreReported() {
        mappings("mobile", "phone");

        Map<String, Object> payload = json("mobile", "9812345678");
        for (int i = 0; i < 100; i++) {
            payload.put("extra_" + i, "value");
        }

        MappedPayload result = service.apply(1L, payload);

        assertEquals(25, result.ignoredPaths().size(),
                "enough to spot the problem, not enough to bloat the column");
    }
}
