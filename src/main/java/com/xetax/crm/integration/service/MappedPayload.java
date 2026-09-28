package com.xetax.crm.integration.service;

import java.util.List;
import java.util.Map;

/**
 * The outcome of applying an integration's field mappings to one payload.
 *
 * <p>The two diagnostic lists exist because an unmapped key used to vanish in
 * silence: the sender got 200 OK and the record simply came out missing a
 * field, with nothing anywhere to say why.
 *
 * @param data            CRM fieldKey -> value, ready for RecordService
 * @param ignoredPaths    value-carrying paths in the payload that no mapping claimed
 * @param unmatchedFields mapped source fields the payload did not carry
 */
public record MappedPayload(Map<String, Object> data,
                            List<String> ignoredPaths,
                            List<String> unmatchedFields) {
}
