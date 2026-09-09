package com.xetax.crm.playbook;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PlaybookRunRepository extends JpaRepository<PlaybookRun, Long> {
    Optional<PlaybookRun> findFirstByPlaybookIdAndRuleIdAndRecordIdOrderByIdDesc(Long playbookId, String ruleId, String recordId);
    List<PlaybookRun> findTop50ByPlaybookIdOrderByIdDesc(Long playbookId);
    List<PlaybookRun> findTop20ByRecordIdAndOwnerUserIdOrderByIdDesc(String recordId, String ownerUserId);
    List<PlaybookRun> findTop50ByStatusAndNextEligibleAtBefore(String status, LocalDateTime before);
    int countByPlaybookIdAndRecordIdAndLastChannelIn(Long playbookId, String recordId, List<String> channels);
    long countByPlaybookIdAndLastRunAtAfter(Long playbookId, LocalDateTime after);
    void deleteByPlaybookId(Long playbookId);
}
