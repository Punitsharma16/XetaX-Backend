package com.xetax.crm.activity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RecordActivityRepository extends JpaRepository<RecordActivity, Long> {

    List<RecordActivity> findTop100ByRecordIdAndOwnerUserIdOrderByCreatedAtDesc(
            String recordId, String ownerUserId);

    void deleteByRecordId(String recordId);
}
