package com.xetax.crm.data_manager.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xetax.crm.data_manager.dto.SeacrhDto.FilterValue;
import com.xetax.crm.data_manager.dto.SeacrhDto.RecordSearchRequest;
import com.xetax.crm.data_manager.entity.FormField;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class CriteriaBuilderImpl implements CriteriaBuilder {

    private final ObjectMapper objectMapper;

    @Override
    public List<Criteria> build(Long formId,
                                RecordSearchRequest request,
                                List<FormField> fields){

        List<Criteria> criteria = new ArrayList<>();

        addFormCriteria(criteria, formId);

        addGlobalSearch(criteria, request, fields);

        addDynamicFilters(criteria, request, fields);

        return criteria;
    }

    private void addFormCriteria(List<Criteria> criteria,
                                 Long formId){
        criteria.add(
                Criteria.where("formId")
                        .is(formId)
        );
    }

    /**
     * Global term: case-insensitive regex over the text-ish fields. Mirrors the
     * UI's fallback — when no field is marked searchable, every text field
     * counts, so the search box never silently does nothing.
     */
    private void addGlobalSearch(
            List<Criteria> criteria,
            RecordSearchRequest request,
            List<FormField> fields){

        if (!StringUtils.hasText(request.getSearch())) {
            return;
        }

        boolean anySearchable = fields.stream()
                .anyMatch(f -> Boolean.TRUE.equals(f.getSearchable()));

        String pattern = Pattern.quote(request.getSearch().trim());
        List<Criteria> searchCriteria = new ArrayList<>();

        for (FormField field : fields) {

            if (anySearchable && !Boolean.TRUE.equals(field.getSearchable())) {
                continue;
            }

            switch (field.getFieldType()) {
                case TEXT, TEXTAREA, EMAIL, PHONE, URL -> searchCriteria.add(
                        Criteria.where("data." + field.getFieldKey())
                                .regex(pattern, "i")
                );
                default -> { }
            }
        }

        if (!searchCriteria.isEmpty()) {
            criteria.add(
                    new Criteria().orOperator(
                            searchCriteria.toArray(new Criteria[0])
                    )
            );
        }
    }

    /**
     * Per-field filters. Values arrive as raw JSON — a scalar for equals, or a
     * map for contains/{value}, number ranges/{min,max} and date spans/{from,to}
     * — so they are normalised into FilterValue instead of blind-cast.
     */
    private void addDynamicFilters(
            List<Criteria> criteria,
            RecordSearchRequest request,
            List<FormField> fields){

        if (request.getFilters() == null || request.getFilters().isEmpty()) {
            return;
        }

        Map<String, FormField> fieldMap = fields.stream()
                .collect(Collectors.toMap(FormField::getFieldKey, Function.identity()));

        boolean anyFilterable = fields.stream()
                .anyMatch(f -> Boolean.TRUE.equals(f.getFilterable()));

        request.getFilters().forEach((fieldKey, raw) -> {

            FormField field = fieldMap.get(fieldKey);
            if (field == null) {
                return;
            }

            // Same fallback the UI applies: flags are honoured only when the
            // form actually marks fields filterable.
            if (anyFilterable && !Boolean.TRUE.equals(field.getFilterable())) {
                return;
            }

            FilterValue filter = toFilterValue(raw);
            if (filter == null) {
                return;
            }

            buildFieldCriteria(criteria, field, filter);
        });
    }

    /** Scalar → FilterValue.value; map → converted field by field. */
    private FilterValue toFilterValue(Object raw) {
        if (raw == null) {
            return null;
        }

        if (raw instanceof Map<?, ?>) {
            try {
                return objectMapper.convertValue(raw, FilterValue.class);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        FilterValue filter = new FilterValue();
        filter.setValue(raw);
        return filter;
    }

    private void buildFieldCriteria(List<Criteria> criteriaList,
                                    FormField field,
                                    FilterValue filter) {

        switch (field.getFieldType()) {

            case TEXT, TEXTAREA, EMAIL, PHONE, URL ->
                    buildTextCriteria(criteriaList, field, filter);

            case NUMBER, DECIMAL -> buildNumberCriteria(criteriaList, field, filter);

            case DATE, DATETIME -> buildDateCriteria(criteriaList, field, filter);

            // Choice/boolean fields hold the exact stored value (arrays for
            // multi-select — Mongo's `is` matches array elements too).
            default -> buildExactCriteria(criteriaList, field, filter);
        }
    }

    private void buildTextCriteria(List<Criteria> criteriaList,
                                   FormField field,
                                   FilterValue filter) {

        if (filter.getValue() == null || filter.getValue().toString().isBlank()) {
            return;
        }

        criteriaList.add(
                Criteria.where("data." + field.getFieldKey())
                        .regex(Pattern.quote(filter.getValue().toString()), "i")
        );
    }

    private void buildNumberCriteria(List<Criteria> criteriaList,
                                     FormField field,
                                     FilterValue filter) {

        if (filter.getMin() == null && filter.getMax() == null && filter.getValue() == null) {
            return;
        }

        Criteria criteria = Criteria.where("data." + field.getFieldKey());

        if (filter.getValue() != null) {
            criteria.is(filter.getValue());
        } else {
            if (filter.getMin() != null) {
                criteria.gte(filter.getMin());
            }
            if (filter.getMax() != null) {
                criteria.lte(filter.getMax());
            }
        }

        criteriaList.add(criteria);
    }

    /**
     * Dates live in the data map as "yyyy-MM-dd(THH:mm)" strings, so string
     * comparison orders correctly; LocalDate bounds are stringified the same
     * way. "On this date" matches datetimes of that day via prefix regex.
     */
    private void buildDateCriteria(List<Criteria> criteriaList,
                                   FormField field,
                                   FilterValue filter) {

        if (filter.getFrom() == null && filter.getTo() == null && filter.getValue() == null) {
            return;
        }

        Criteria criteria = Criteria.where("data." + field.getFieldKey());

        if (filter.getValue() != null) {
            criteria.regex("^" + Pattern.quote(filter.getValue().toString()));
        } else {
            if (filter.getFrom() != null) {
                criteria.gte(filter.getFrom().toString());
            }
            if (filter.getTo() != null) {
                // Day-end sentinel keeps the upper bound inclusive for datetimes.
                criteria.lte(filter.getTo().toString() + "￿");
            }
        }

        criteriaList.add(criteria);
    }

    private void buildExactCriteria(List<Criteria> criteriaList,
                                    FormField field,
                                    FilterValue filter) {

        if (filter.getValue() == null) {
            return;
        }

        criteriaList.add(
                Criteria.where("data." + field.getFieldKey())
                        .is(filter.getValue())
        );
    }
}
