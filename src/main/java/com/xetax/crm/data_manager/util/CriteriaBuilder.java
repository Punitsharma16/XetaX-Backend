package com.xetax.crm.data_manager.util;

import com.xetax.crm.data_manager.dto.SeacrhDto.RecordSearchRequest;
import com.xetax.crm.data_manager.entity.FormField;
import org.springframework.data.mongodb.core.query.Criteria;

import java.util.List;

public interface CriteriaBuilder {

    List<Criteria> build(Long formId,
                         RecordSearchRequest request,
                         List<FormField> fields);

}
