package com.xetax.crm.data_manager.repository;

import com.xetax.crm.data_manager.documents.RecordDocument;
import com.xetax.crm.data_manager.dto.SeacrhDto.RecordSearchRequest;
import com.xetax.crm.data_manager.entity.FormField;
import org.springframework.data.domain.Page;

import java.util.List;

public interface RecordSearchRepo {

    Page<RecordDocument> search(Long formId,
                                RecordSearchRequest request,
                                List<FormField> fields);

    /** assignedTo non-null => sirf us member ke assigned records (view.own scope). */
    Page<RecordDocument> search(Long formId,
                                RecordSearchRequest request,
                                List<FormField> fields,
                                String assignedTo);

}
