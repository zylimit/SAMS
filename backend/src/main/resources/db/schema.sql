-- SAMS · Site Acquisition Management System
-- MySQL 8.0 schema (ported from PostGIS; geometry stored as WKB LONGBLOB)

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

CREATE TABLE IF NOT EXISTS site (
    site_id      VARCHAR(255) NOT NULL,
    `option`     VARCHAR(255) NOT NULL DEFAULT '',
    project      VARCHAR(512),
    site_status  VARCHAR(64),
    operator     VARCHAR(64),
    category     VARCHAR(64),
    type         VARCHAR(64),
    lati         DOUBLE,
    longi        DOUBLE,
    extras       JSON         NOT NULL,
    source_file  VARCHAR(512),
    created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    geom         LONGBLOB,
    PRIMARY KEY (site_id, `option`),
    KEY site_status_idx (site_status),
    KEY site_layer_idx (operator, category, type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS road (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    property     VARCHAR(512),
    extras       JSON         NOT NULL,
    source_file  VARCHAR(512),
    created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    geom         LONGBLOB,
    PRIMARY KEY (id),
    KEY road_property_idx (property)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS lessor (
    fid              VARCHAR(255) NOT NULL,
    lessor_name      VARCHAR(512),
    lessor_category  VARCHAR(255),
    relationship     VARCHAR(64),
    extras           JSON         NOT NULL,
    source_file      VARCHAR(512),
    created_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    geom             LONGBLOB,
    PRIMARY KEY (fid),
    KEY lessor_relationship_idx (relationship)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS area (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    name        VARCHAR(512) NOT NULL,
    operator    VARCHAR(64)  NOT NULL,
    geom        LONGBLOB,
    extras      JSON         NOT NULL,
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY area_op_name (operator, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS countries (
    iso_a2   VARCHAR(8),
    iso_a3   VARCHAR(8),
    name     VARCHAR(255),
    name_en  VARCHAR(255),
    name_zh  VARCHAR(255),
    admin    VARCHAR(255),
    geom     LONGBLOB,
    KEY countries_iso_a2_idx (iso_a2)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS baseline_state (
    id              INT          NOT NULL,
    iso_a2          VARCHAR(8)   NOT NULL,
    name_zh         VARCHAR(255),
    coverage_pct    INT,
    points_used     INT,
    established_at  DATETIME(3)  DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS restore_point (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    reason          VARCHAR(64)  NOT NULL,
    note            VARCHAR(512),
    site_count      INT,
    road_count      INT,
    lessor_count    INT,
    area_count      INT,
    baseline_iso_a2 VARCHAR(8),
    PRIMARY KEY (id),
    KEY restore_point_reason_idx (reason, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS site_snapshot (
    restore_point_id BIGINT       NOT NULL,
    site_id          VARCHAR(255) NOT NULL,
    `option`         VARCHAR(255) NOT NULL DEFAULT '',
    project          VARCHAR(512),
    site_status      VARCHAR(64),
    operator         VARCHAR(64),
    category         VARCHAR(64),
    type             VARCHAR(64),
    lati             DOUBLE,
    longi            DOUBLE,
    extras           JSON         NOT NULL,
    source_file      VARCHAR(512),
    created_at       DATETIME(3),
    updated_at       DATETIME(3),
    geom             LONGBLOB,
    KEY site_snapshot_rp_idx (restore_point_id),
    CONSTRAINT site_snapshot_rp_fk FOREIGN KEY (restore_point_id)
        REFERENCES restore_point(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS road_snapshot (
    restore_point_id BIGINT NOT NULL,
    id               BIGINT,
    property         VARCHAR(512),
    extras           JSON   NOT NULL,
    source_file      VARCHAR(512),
    created_at       DATETIME(3),
    geom             LONGBLOB,
    KEY road_snapshot_rp_idx (restore_point_id),
    CONSTRAINT road_snapshot_rp_fk FOREIGN KEY (restore_point_id)
        REFERENCES restore_point(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS lessor_snapshot (
    restore_point_id BIGINT       NOT NULL,
    fid              VARCHAR(255) NOT NULL,
    lessor_name      VARCHAR(512),
    lessor_category  VARCHAR(255),
    relationship     VARCHAR(64),
    extras           JSON         NOT NULL,
    source_file      VARCHAR(512),
    created_at       DATETIME(3),
    updated_at       DATETIME(3),
    geom             LONGBLOB,
    KEY lessor_snapshot_rp_idx (restore_point_id),
    CONSTRAINT lessor_snapshot_rp_fk FOREIGN KEY (restore_point_id)
        REFERENCES restore_point(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS area_snapshot (
    restore_point_id BIGINT       NOT NULL,
    id               BIGINT,
    name             VARCHAR(512) NOT NULL,
    operator         VARCHAR(64)  NOT NULL,
    geom             LONGBLOB,
    extras           JSON         NOT NULL,
    created_at       DATETIME(3),
    KEY area_snapshot_rp_idx (restore_point_id),
    CONSTRAINT area_snapshot_rp_fk FOREIGN KEY (restore_point_id)
        REFERENCES restore_point(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS baseline_state_snapshot (
    restore_point_id BIGINT NOT NULL,
    id               INT,
    iso_a2           VARCHAR(8),
    name_zh          VARCHAR(255),
    coverage_pct     INT,
    points_used      INT,
    established_at   DATETIME(3),
    KEY baseline_state_snapshot_rp_idx (restore_point_id),
    CONSTRAINT baseline_state_snapshot_rp_fk FOREIGN KEY (restore_point_id)
        REFERENCES restore_point(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS audit_log (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    ts          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    session_id  VARCHAR(64),
    ip          VARCHAR(64),
    user_agent  VARCHAR(512),
    username    VARCHAR(128),
    action      VARCHAR(64)  NOT NULL,
    details     JSON,
    result      VARCHAR(32)  NOT NULL DEFAULT 'success',
    error_msg   TEXT,
    PRIMARY KEY (id),
    KEY audit_log_ts_idx (ts),
    KEY audit_log_action_idx (action)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS site_delete_undo_batch (
    undo_id BIGINT NOT NULL AUTO_INCREMENT,
    PRIMARY KEY (undo_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS site_delete_undo (
    undo_id      BIGINT       NOT NULL,
    deleted_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    undone       TINYINT(1)   NOT NULL DEFAULT 0,
    site_id      VARCHAR(255) NOT NULL,
    `option`     VARCHAR(255) NOT NULL DEFAULT '',
    project      VARCHAR(512),
    site_status  VARCHAR(64),
    operator     VARCHAR(64),
    category     VARCHAR(64),
    type         VARCHAR(64),
    lati         DOUBLE,
    longi        DOUBLE,
    extras       JSON         NOT NULL,
    source_file  VARCHAR(512),
    created_at   DATETIME(3),
    updated_at   DATETIME(3),
    geom         LONGBLOB,
    KEY site_delete_undo_batch_idx (undo_id),
    KEY site_delete_undo_deleted_at_idx (deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS app_role (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    name        VARCHAR(128) NOT NULL,
    is_admin    TINYINT(1)   NOT NULL DEFAULT 0,
    perms       JSON         NOT NULL,
    created_at  DATETIME(3)  DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY app_role_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS app_user (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    username             VARCHAR(128) NOT NULL,
    password_hash        VARCHAR(255) NOT NULL,
    role_id              BIGINT,
    disabled             TINYINT(1)   NOT NULL DEFAULT 0,
    must_change_password TINYINT(1)   NOT NULL DEFAULT 1,
    created_at           DATETIME(3)  DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY app_user_username (username),
    KEY app_user_role_idx (role_id),
    CONSTRAINT app_user_role_fk FOREIGN KEY (role_id) REFERENCES app_role(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS app_role_scope (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    role_id     BIGINT       NOT NULL,
    scope_node  VARCHAR(128) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY app_role_scope_uniq (role_id, scope_node),
    CONSTRAINT app_role_scope_fk FOREIGN KEY (role_id) REFERENCES app_role(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS auth_session (
    jti         VARCHAR(64)  NOT NULL,
    user_id     BIGINT       NOT NULL,
    expires_at  DATETIME(3)  NOT NULL,
    created_at  DATETIME(3)  DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (jti),
    KEY auth_session_expires_at_idx (expires_at),
    CONSTRAINT auth_session_user_fk FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET FOREIGN_KEY_CHECKS = 1;
