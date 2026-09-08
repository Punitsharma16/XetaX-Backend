package com.xetax.crm.data_manager.repository;


import com.xetax.crm.data_manager.documents.RecordDocument;
import com.xetax.crm.data_manager.dto.SeacrhDto.RecordSearchRequest;
import com.xetax.crm.data_manager.entity.FormField;
import com.xetax.crm.data_manager.util.CriteriaBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class RecordSearchRepoImpl implements RecordSearchRepo {

    private final MongoTemplate mongoTemplate;

    private final CriteriaBuilder builder;


    @Override
    public Page<RecordDocument> search(Long formId,
                                       RecordSearchRequest request,
                                       List<FormField> fields) {
        return search(formId, request, fields, null);
    }

    @Override
    public Page<RecordDocument> search(Long formId,
                                       RecordSearchRequest request,
                                       List<FormField> fields,
                                       String assignedTo) {

        List<Criteria> criteriaList =
                builder.build(formId, request, fields);
        if (assignedTo != null) {
            criteriaList.add(Criteria.where("assignedTo").is(assignedTo));
        }

        Query query = new Query();

        if (!criteriaList.isEmpty()) {
            query.addCriteria(
                    new Criteria().andOperator(
                            criteriaList.toArray(new Criteria[0])
                    )
            );
        }

        applySorting(query, request);

        long total =
                mongoTemplate.count(query, RecordDocument.class);

        applyPagination(query, request);

        List<RecordDocument> records =
                mongoTemplate.find(query, RecordDocument.class);

        Pageable pageable =
                PageRequest.of(
                        request.getPage(),
                        request.getSize());

        return new PageImpl<>(
                records,
                pageable,
                total
        );

    }

    /*
     * Form-field values live inside the `data` map, so sorting by a fieldKey
     * must target "data.<key>" — only the document's own columns are top-level.
     */
    private static final List<String> TOP_LEVEL_SORTS =
            List.of("id", "formId", "stageId", "userId", "createdBy", "createdAt", "updatedAt");

    private void applySorting(Query query,
                              RecordSearchRequest request) {
        String sortBy = request.getSortBy() == null || request.getSortBy().isBlank()
                ? "createdAt"
                : request.getSortBy();

        if (!TOP_LEVEL_SORTS.contains(sortBy)) {
            sortBy = "data." + sortBy;
        }

        Sort sort = Sort.by(
                request.getDirection(),
                sortBy
        );
        query.with(sort);
    }

    private void applyPagination(Query query,
                                 RecordSearchRequest request) {
        Pageable pageable =
                PageRequest.of(
                        request.getPage(),
                        request.getSize()
                );

        query.with(pageable);
    }
}
