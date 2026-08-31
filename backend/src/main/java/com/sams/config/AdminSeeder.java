package com.sams.config;

import com.sams.auth.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class AdminSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AdminSeeder.class);
    private final JdbcTemplate jdbc;
    private final AuthService auth;

    public AdminSeeder(JdbcTemplate jdbc, AuthService auth) {
        this.jdbc = jdbc;
        this.auth = auth;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            Long roleId = jdbc.query(
                    "SELECT id FROM app_role WHERE is_admin = 1 ORDER BY id LIMIT 1",
                    rs -> rs.next() ? rs.getLong(1) : null);
            if (roleId == null) {
                jdbc.update("INSERT INTO app_role (name, is_admin, perms) VALUES ('admin', 1, CAST('{}' AS JSON))");
                roleId = jdbc.queryForObject("SELECT id FROM app_role WHERE name = 'admin'", Long.class);
                log.info("已插入 admin 角色");
            }
            Integer exists = jdbc.query(
                    "SELECT 1 FROM app_user WHERE username = 'admin'",
                    rs -> rs.next() ? 1 : null);
            if (exists == null) {
                jdbc.update(
                        "INSERT INTO app_user (username, password_hash, role_id) VALUES (?,?,?)",
                        "admin", auth.hashPassword("admin123"), roleId);
                log.info("已插入 admin 用户（初始密码 admin123，首登强制改密）");
            }
        } catch (Exception e) {
            log.warn("admin 种子失败：{}", e.toString());
        }
    }
}
