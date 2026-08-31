package com.sams.backup;

import com.sams.restore.RestoreService;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class BackupScheduler {
    private static final Logger log = LoggerFactory.getLogger(BackupScheduler.class);
    private static final long INTERVAL = 12 * 3600;
    private final JdbcTemplate jdbc;
    private final RestoreService restore;

    public BackupScheduler(JdbcTemplate jdbc, RestoreService restore) {
        this.jdbc = jdbc;
        this.restore = restore;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void compensate() {
        try {
            List<java.sql.Timestamp> ts = jdbc.query(
                    "SELECT created_at FROM restore_point WHERE reason = 'auto_backup' ORDER BY created_at DESC LIMIT 1",
                    (rs, i) -> rs.getTimestamp(1));
            if (ts.isEmpty() || Duration.between(ts.get(0).toInstant(), Instant.now()).getSeconds() >= INTERVAL) {
                runOnce();
                log.info("启动补偿备份完成");
            }
        } catch (Exception e) {
            log.error("启动补偿备份失败：{}", e.toString());
        }
    }

    @Scheduled(fixedDelay = 12 * 3600 * 1000L, initialDelay = 12 * 3600 * 1000L)
    public void tick() {
        try {
            runOnce();
            log.info("定时备份完成");
        } catch (Exception e) {
            log.error("定时备份失败：{}", e.toString());
        }
    }

    public long runOnce() {
        String note = "auto_backup " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        long id = restore.createRestorePoint("auto_backup", note, null, false);
        jdbc.update("DELETE FROM restore_point WHERE reason = 'auto_backup' AND created_at < DATE_SUB(NOW(3), INTERVAL 30 DAY)");
        return id;
    }
}
