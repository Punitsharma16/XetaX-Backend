package com.xetax.crm.data_manager.repository;

import com.xetax.crm.data_manager.entity.FormField;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FormFieldRepo extends JpaRepository<FormField , Long> {

    List<FormField> findByFormIdOrderByDisplayOrder(Long formId);

    boolean existsByFormIdAndFieldKey(Long formId, String fieldKey);

}
