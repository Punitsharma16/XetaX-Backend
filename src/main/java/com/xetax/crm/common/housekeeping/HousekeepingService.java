package com.xetax.crm.common.housekeeping;

import com.xetax.crm.contact.EmailLogRepository;
import com.xetax.crm.notification.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** Keeps ever-growing log tables bounded — 90 days of history is plenty. */
@Service
@RequiredArgsConstructor
@Slf4j
public class HousekeepingService {

    private final NotificationRepository notificationRepository;
    private final EmailLogRepository emailLogRepository;

    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void purgeOldRows() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(90);
        long notifications = notificationRepository.deleteByCreatedAtBefore(cutoff);
        long emails = emailLogRepository.deleteByCreatedAtBefore(cutoff);
        if (notifications > 0 || emails > 0) {
            log.info("Housekeeping: purged {} notifications, {} email logs older than 90d",
                    notifications, emails);
        }
    }
}
