package com.sams.exports;

import com.sams.common.JsonMaps;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

public final class ConflictsXlsx {
    private static final List<String> SITE_CORE = List.of("site_id", "option", "project", "site_status", "lati", "longi");
    private static final List<String> LESSOR_CORE = List.of("fid", "lessor_name", "lessor_category", "relationship");
    private static final List<String> AREA_CORE = List.of("name", "operator");
    private static final List<String> ROAD_CORE = List.of("property");
    private static final Set<String> SITE_DROP = Set.of("SITE ID", "OPTION", "PROJECT", "SITE STATUS", "LATI", "LONGI");
    private static final Set<String> LESSOR_DROP = Set.of("fid", "Lessor Name", "Lessor Category", "Lessor Cagegory", "Relationship");
    private static final Set<String> AREA_DROP = Set.of("name", "Name", "operator", "OPERATOR");
    private static final Set<String> ROAD_DROP = Set.of("Property", "property");

    private ConflictsXlsx() {}

    public static byte[] build(List<Map<String, Object>> conflicts) {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            write(wb, "Site Conflicts", "site", SITE_CORE, SITE_DROP, conflicts);
            write(wb, "Road Conflicts", "road", ROAD_CORE, ROAD_DROP, conflicts);
            write(wb, "Lessor Conflicts", "lessor", LESSOR_CORE, LESSOR_DROP, conflicts);
            write(wb, "Area Conflicts", "area", AREA_CORE, AREA_DROP, conflicts);
            if (wb.getNumberOfSheets() == 0) {
                Sheet ws = wb.createSheet("Conflicts");
                ws.createRow(0).createCell(0).setCellValue("无冲突");
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            wb.write(bos);
            return bos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void write(XSSFWorkbook wb, String sheet, String kind, List<String> core, Set<String> drop,
                              List<Map<String, Object>> all) {
        List<Map<String, Object>> rows = all.stream().filter(c -> kind.equals(c.get("kind"))).toList();
        if (rows.isEmpty()) return;
        Sheet ws = wb.createSheet(sheet);
        List<String> fields = collect(rows, core, drop);
        CellStyle db = wb.createCellStyle();
        db.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
        db.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        CellStyle neu = wb.createCellStyle();
        neu.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
        neu.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        Row h = ws.createRow(0);
        h.createCell(0).setCellValue("类型");
        h.createCell(1).setCellValue("名称");
        h.createCell(2).setCellValue("来源文件名");
        int col = 3;
        for (String f : fields) {
            h.createCell(col).setCellValue(f + " [DB]");
            h.getCell(col).setCellStyle(db);
            h.createCell(col + 1).setCellValue(f + " [新]");
            h.getCell(col + 1).setCellStyle(neu);
            col += 2;
        }
        int ridx = 1;
        for (Map<String, Object> c : rows) {
            Row row = ws.createRow(ridx++);
            row.createCell(0).setCellValue(String.valueOf(c.get("kind")));
            row.createCell(1).setCellValue(String.valueOf(c.get("name")));
            row.createCell(2).setCellValue(c.get("source_file") == null ? "" : String.valueOf(c.get("source_file")));
            @SuppressWarnings("unchecked")
            Map<String, Object> existing = (Map<String, Object>) c.getOrDefault("existing", Map.of());
            @SuppressWarnings("unchecked")
            Map<String, Object> incoming = (Map<String, Object>) c.getOrDefault("incoming", Map.of());
            col = 3;
            for (String f : fields) {
                row.createCell(col).setCellValue(val(existing, f, core));
                row.getCell(col).setCellStyle(db);
                row.createCell(col + 1).setCellValue(val(incoming, f, core));
                row.getCell(col + 1).setCellStyle(neu);
                col += 2;
            }
        }
    }

    private static List<String> collect(List<Map<String, Object>> rows, List<String> core, Set<String> drop) {
        Set<String> extras = new LinkedHashSet<>();
        for (Map<String, Object> c : rows) {
            for (String side : List.of("existing", "incoming")) {
                Object row = c.get(side);
                if (row instanceof Map<?, ?> m) {
                    JsonMaps.parse(m.get("extras")).keySet().stream().filter(k -> !drop.contains(k)).forEach(extras::add);
                }
            }
        }
        List<String> fields = new ArrayList<>(core);
        extras.stream().sorted().forEach(fields::add);
        return fields;
    }

    private static String val(Map<String, Object> row, String field, List<String> core) {
        if (core.contains(field)) {
            Object v = row.get(field);
            return v == null ? "" : String.valueOf(v);
        }
        Object v = JsonMaps.parse(row.get("extras")).get(field);
        return v == null ? "" : String.valueOf(v);
    }
}
