package com.sams.restore;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class RestoreService {
    private static final int KEEP = 10;
    private final JdbcTemplate jdbc;

    public RestoreService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long createRestorePoint(String reason, String note, Long protectId, boolean evict) {
        jdbc.update("INSERT INTO restore_point (reason, note) VALUES (?,?)", reason, note);
        Long rpId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update(
                """
                INSERT INTO site_snapshot
                  (restore_point_id, site_id, `option`, project, site_status, operator, category, type,
                   lati, longi, extras, source_file, created_at, updated_at, geom)
                SELECT ?, site_id, `option`, project, site_status, operator, category, type,
                       lati, longi, extras, source_file, created_at, updated_at, geom FROM site
                """, rpId);
        jdbc.update(
                """
                INSERT INTO road_snapshot (restore_point_id, id, property, extras, source_file, created_at, geom)
                SELECT ?, id, property, extras, source_file, created_at, geom FROM road
                """, rpId);
        jdbc.update(
                """
                INSERT INTO lessor_snapshot
                  (restore_point_id, fid, lessor_name, lessor_category, relationship, extras, source_file, created_at, updated_at, geom)
                SELECT ?, fid, lessor_name, lessor_category, relationship, extras, source_file, created_at, updated_at, geom FROM lessor
                """, rpId);
        jdbc.update(
                """
                INSERT INTO area_snapshot (restore_point_id, id, name, operator, geom, extras, created_at)
                SELECT ?, id, name, operator, geom, extras, created_at FROM area
                """, rpId);
        jdbc.update(
                """
                INSERT INTO baseline_state_snapshot
                  (restore_point_id, id, iso_a2, name_zh, coverage_pct, points_used, established_at)
                SELECT ?, id, iso_a2, name_zh, coverage_pct, points_used, established_at FROM baseline_state
                """, rpId);
        jdbc.update(
                """
                UPDATE restore_point SET
                  site_count = (SELECT count(*) FROM site),
                  road_count = (SELECT count(*) FROM road),
                  lessor_count = (SELECT count(*) FROM lessor),
                  area_count = (SELECT count(*) FROM area),
                  baseline_iso_a2 = (SELECT iso_a2 FROM baseline_state WHERE id = 1)
                WHERE id = ?
                """, rpId);
        if (evict) {
            if (protectId == null) {
                jdbc.update(
                        """
                        DELETE FROM restore_point WHERE reason <> 'auto_backup' AND id NOT IN (
                          SELECT id FROM (
                            SELECT id FROM restore_point WHERE reason <> 'auto_backup' ORDER BY created_at DESC LIMIT ?
                          ) t)
                        """, KEEP);
            } else {
                jdbc.update(
                        """
                        DELETE FROM restore_point WHERE reason <> 'auto_backup' AND id <> ? AND id NOT IN (
                          SELECT id FROM (
                            SELECT id FROM restore_point WHERE reason <> 'auto_backup' ORDER BY created_at DESC LIMIT ?
                          ) t)
                        """, protectId, KEEP);
            }
        }
        return rpId;
    }

    public void restoreFromSnapshot(long rpId) {
        jdbc.update("DELETE FROM site");
        jdbc.update("DELETE FROM road");
        jdbc.update("DELETE FROM lessor");
        jdbc.update("DELETE FROM area");
        jdbc.update("DELETE FROM baseline_state");
        jdbc.update(
                """
                INSERT INTO site
                  (site_id, `option`, project, site_status, operator, category, type, lati, longi,
                   extras, source_file, created_at, updated_at, geom)
                SELECT site_id, `option`, project, site_status, operator, category, type, lati, longi,
                       extras, source_file, created_at, updated_at, geom
                FROM site_snapshot WHERE restore_point_id = ?
                """, rpId);
        jdbc.update(
                """
                INSERT INTO road (id, property, extras, source_file, created_at, geom)
                SELECT id, property, extras, source_file, created_at, geom FROM road_snapshot WHERE restore_point_id = ?
                """, rpId);
        jdbc.update(
                """
                INSERT INTO lessor (fid, lessor_name, lessor_category, relationship, extras, source_file, created_at, updated_at, geom)
                SELECT fid, lessor_name, lessor_category, relationship, extras, source_file, created_at, updated_at, geom
                FROM lessor_snapshot WHERE restore_point_id = ?
                """, rpId);
        jdbc.update(
                """
                INSERT INTO area (id, name, operator, geom, extras, created_at)
                SELECT id, name, operator, geom, extras, created_at FROM area_snapshot WHERE restore_point_id = ?
                """, rpId);
        jdbc.update(
                """
                INSERT IGNORE INTO baseline_state (id, iso_a2, name_zh, coverage_pct, points_used, established_at)
                SELECT id, iso_a2, name_zh, coverage_pct, points_used, established_at
                FROM baseline_state_snapshot WHERE restore_point_id = ?
                """, rpId);
        resetAi("road");
        resetAi("area");
    }

    private void resetAi(String table) {
        try {
            Long max = jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM " + table, Long.class);
            jdbc.update("ALTER TABLE " + table + " AUTO_INCREMENT = " + ((max == null ? 0 : max) + 1));
        } catch (Exception ignored) {
            // MySQL ALTER TABLE 隐式提交，失败不阻断回滚数据本身
        }
    }
}
