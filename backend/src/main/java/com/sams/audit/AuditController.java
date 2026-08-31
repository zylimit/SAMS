package com.sams.audit;

import com.sams.auth.RequirePerm;
import com.sams.common.JsonMaps;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AuditController {
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public AuditController(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @GetMapping("/audit-log")
    public Map<String, Object> list(
            @RequestParam(required = false) String action,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "50") int pageSize) {
        RequirePerm.admin();
        Where w = buildWhere(action, from, to);
        Integer total = jdbc.queryForObject("SELECT count(*) FROM audit_log" + w.sql, Integer.class, w.params.toArray());
        int offset = Math.max(page - 1, 0) * pageSize;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM audit_log" + w.sql + " ORDER BY ts DESC, id DESC LIMIT ? OFFSET ?",
                concat(w.params, pageSize, offset));
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> r : rows) items.add(toItem(r));
        return Map.of("items", items, "total", total == null ? 0 : total, "page", page, "page_size", pageSize);
    }

    @GetMapping("/audit-log/export")
    public ResponseEntity<byte[]> export(
            HttpServletRequest request,
            @RequestParam(required = false) String action,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(required = false) String to) throws Exception {
        RequirePerm.admin();
        Where w = buildWhere(action, from, to);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM audit_log" + w.sql + " ORDER BY ts DESC, id DESC", w.params.toArray());
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet ws = wb.createSheet("Audit Log");
            String[] headers = {"ID", "时间 (ts)", "操作 (action)", "结果", "IP", "Session ID", "User Agent", "Details (JSON)", "Error"};
            Row h = ws.createRow(0);
            for (int i = 0; i < headers.length; i++) h.createCell(i).setCellValue(headers[i]);
            int i = 1;
            for (Map<String, Object> r : rows) {
                Row row = ws.createRow(i++);
                row.createCell(0).setCellValue(num(r.get("id")));
                row.createCell(1).setCellValue(str(r.get("ts")));
                row.createCell(2).setCellValue(str(r.get("action")));
                row.createCell(3).setCellValue(str(r.get("result")));
                row.createCell(4).setCellValue(str(r.get("ip")));
                row.createCell(5).setCellValue(str(r.get("session_id")));
                row.createCell(6).setCellValue(str(r.get("user_agent")));
                Object d = r.get("details");
                row.createCell(7).setCellValue(d instanceof String s ? s : JsonMaps.stringify(d));
                row.createCell(8).setCellValue(str(r.get("error_msg")));
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            wb.write(bos);
            byte[] data = bos.toByteArray();
            String fname = "audit_log_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".xlsx";
            audit.write("audit_log_export", Map.of(
                    "file_name", fname,
                    "exported_rows", rows.size(),
                    "filters", Map.of("action", action == null ? "" : action, "from", from == null ? "" : from, "to", to == null ? "" : to),
                    "bytes", data.length), request);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fname + "\"")
                    .header("X-Filename", fname)
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(data);
        }
    }

    private static Map<String, Object> toItem(Map<String, Object> r) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", r.get("id"));
        d.put("ts", r.get("ts") == null ? null : r.get("ts").toString().replace(' ', 'T'));
        d.put("session_id", r.get("session_id"));
        d.put("ip", r.get("ip"));
        d.put("user_agent", r.get("user_agent"));
        d.put("username", r.get("username"));
        d.put("action", r.get("action"));
        d.put("details", JsonMaps.parse(r.get("details")));
        d.put("result", r.get("result"));
        d.put("error_msg", r.get("error_msg"));
        return d;
    }

    private static Where buildWhere(String action, String from, String to) {
        List<String> clauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        if (action != null && !action.isBlank()) {
            clauses.add("action = ?");
            params.add(action);
        }
        if (from != null && !from.isBlank()) {
            clauses.add("ts >= ?");
            params.add(from.replace("T", " ").replace("Z", ""));
        }
        if (to != null && !to.isBlank()) {
            clauses.add("ts <= ?");
            params.add(to.replace("T", " ").replace("Z", ""));
        }
        String sql = clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
        return new Where(sql, params);
    }

    private record Where(String sql, List<Object> params) {}

    private static Object[] concat(List<Object> a, Object... more) {
        List<Object> all = new ArrayList<>(a);
        all.addAll(List.of(more));
        return all.toArray();
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private static double num(Object o) { return o instanceof Number n ? n.doubleValue() : 0; }

    @SuppressWarnings("unused")
    private void touch(Cell c) {}
}
