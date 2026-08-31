# SAMS · Site Acquisition Management System

站点获取管理系统。由 Pre-Survey Map 换栈重建：业务规则与 HTTP 契约对齐现网，技术栈改为 Java / Vue 模块化单体。

## 技术栈

| 层 | 选型 |
|----|------|
| 后端 | JDK 21 · Spring Boot 3.3 · MyBatis-Plus 3.5 · JWT (jjwt 0.11) · GeoTools / JTS · Apache POI · poi-tl（占位） |
| 前端 | Vue 3.5 · Vite 5 · Element Plus 2 · Pinia 2 · Axios 1 · OpenLayers 9.2 · ECharts 5 / html2canvas（占位） |
| 数据库 | MySQL 8.0（几何以 WKB 存储，空间谓词在 GeoTools/JTS） |

默认账号：`admin` / `admin123`（首登强制改密）。

## 本地启动

```bash
cp .env.example .env
docker compose up -d --build
```

| 入口 | 地址 |
|------|------|
| 前端 | http://localhost:5174 |
| 后端健康 | http://localhost:8080/health |
| MySQL | localhost:3306 · sams / sams / sams |

开发态（需本机 JDK 21 + Node 20+）：

```bash
# 先起 MySQL
docker compose up -d db
# 后端
cd backend && mvn spring-boot:run
# 前端
cd frontend && npm install && npm run dev
```

若 Docker Hub / Maven 的 Java HTTP 客户端在当前网络下超时，可用本机回退：

```bash
# 依赖：~/.local/jdk-21、curl 拉 jar 到 ~/.m2、本机 MySQL 8（scripts 可从阿里云镜像取 generic tarball）
python3 scripts/fetch-maven-deps.py
./scripts/compile-and-test.sh
./scripts/start-local.sh          # API :8080
cd frontend && npm run dev -- --port 5174
./scripts/e2e.sh
```

## 目录

```
SAMS/
  backend/     Spring Boot 模块化单体（auth / admin / geo / feature / imports / exports / restore / backup / audit）
  frontend/    Vue 3 SPA
  deploy/mysql/schema.sql
  deploy/geo_data/          Natural Earth 国家面
  data/                     对照验收样本
```

## HTTP 契约（`/api`，对齐现网）

- 认证：`POST /api/auth/login|logout|change-password`，`GET /api/auth/me`（Bearer JWT + `jti` 会话表，7 天滑动续期）
- 要素：`GET /api/sites|roads|lessors|areas`；`PATCH /api/sites`；`POST /api/sites/delete`；删除历史 / 撤销
- 导入：`POST /api/import` → `proceed-to-conflicts` / `back-to-cleaning` / `commit` / `progress` / `conflicts.xlsx` / `DELETE`
- 导出：`GET /api/export/all`，`POST /api/export/selection`，`POST /api/export/selection_ids`
- 基线 / 恢复点 / 备份 / 审计 / 管理用户与角色：路径与现网相同

空间谓词（选区 `contains`、海里/基准国、面质心）在 GeoTools/JTS，不走 MySQL `ST_Contains`。

## 对照验收

```bash
# 容器起来后
./scripts/e2e.sh
```

样本：`data/sample_sites.kml`（脚本用）、`data/Integrated_Libraries_2026-05-27.kml`（现网整库对照）。

后端单测（清洗 / ST_Contains 严格包含 / 国家面 / KML 解析 / 冲突 Excel / KMZ 自反）：

```bash
cd backend && mvn test
```

旧仓 `pre-survey-map` 只作对照，本仓独立运行。
