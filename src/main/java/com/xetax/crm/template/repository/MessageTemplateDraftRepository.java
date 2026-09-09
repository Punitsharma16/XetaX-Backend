package com.xetax.crm.template.repository;

import com.xetax.crm.template.entity.MessageTemplateDraft;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MessageTemplateDraftRepository extends JpaRepository<MessageTemplateDraft, Long> {
    List<MessageTemplateDraft> findByOwnerUserIdOrderByIdDesc(String ownerUserId);
    Optional<MessageTemplateDraft> findByIdAndOwnerUserId(Long id, String ownerUserId);
    Optional<MessageTemplateDraft> findFirstByOwnerUserIdAndNameAndLanguage(String ownerUserId, String name, String language);
    List<MessageTemplateDraft> findByOwnerUserIdAndFormId(String ownerUserId, Long formId);
}
