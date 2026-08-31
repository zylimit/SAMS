package com.sams.imports;

import com.sams.imports.ParseModels.ParseResult;
import com.sams.imports.ParseModels.SiteRow;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

public class XlsxParser {
    public static class ParseError extends RuntimeException {
        public ParseError(String m) { super(m); }
    }

    private static final Set<String> CORE = Set.of(
            "SITE ID", "OPTION", "PROJECT", "SITE STATUS", "LATI", "LONGI",
            "OPERATOR", "CATEGORY", "SITE CATEGORY", "TYPE", "SITE TYPE");

    public ParseResult parse(byte[] data) throws Exception {
        DataFormatter fmt = new DataFormatter();
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(data))) {
            Sheet ws = wb.getSheetAt(0);
            List<List<String>> scanned = new ArrayList<>();
            Row headerRow = null;
            int headerIdx = -1;
            int scan = Math.min(5, ws.getLastRowNum() + 1);
            for (int i = 0; i < scan; i++) {
                Row r = ws.getRow(i);
                List<String> norm = rowVals(r, fmt);
                scanned.add(norm);
                if (norm.contains("SITE ID")) {
                    headerRow = r;
                    headerIdx = i;
                    break;
                }
            }
            if (headerRow == null) {
                List<String> candidates = new ArrayList<>();
                for (List<String> r : scanned) {
                    for (String s : r) {
                        if (s != null && s.toLowerCase().contains("site") && s.toLowerCase().contains("id")) {
                            candidates.add(s);
                        }
                    }
                }
                throw new ParseError("找不到表头行：前 5 行内未发现 'SITE ID' 列"
                        + (candidates.isEmpty() ? "" : "（检测到候选列：" + String.join(", ", candidates) + "）"));
            }
            List<String> headers = rowVals(headerRow, fmt);
            int sidIdx = headers.indexOf("SITE ID");
            int optIdx = headers.indexOf("OPTION");
            int projectIdx = headers.indexOf("PROJECT");
            int statusIdx = headers.indexOf("SITE STATUS");
            int latiIdx = headers.indexOf("LATI");
            int longiIdx = headers.indexOf("LONGI");
            ParseResult result = new ParseResult();
            for (int i = headerIdx + 1; i <= ws.getLastRowNum(); i++) {
                Row r = ws.getRow(i);
                if (r == null) continue;
                List<String> vals = rowVals(r, fmt);
                if (vals.stream().allMatch(String::isEmpty)) continue;
                String siteId = get(vals, sidIdx);
                if (siteId.isEmpty()) continue;
                SiteRow s = new SiteRow();
                s.siteId = siteId;
                s.option = optIdx >= 0 ? get(vals, optIdx) : "";
                s.project = emptyToNull(projectIdx >= 0 ? get(vals, projectIdx) : "");
                s.siteStatus = emptyToNull(statusIdx >= 0 ? get(vals, statusIdx) : "");
                s.lati = toFloat(latiIdx >= 0 ? get(vals, latiIdx) : "");
                s.longi = toFloat(longiIdx >= 0 ? get(vals, longiIdx) : "");
                Map<String, Object> extras = new LinkedHashMap<>();
                for (int c = 0; c < headers.size(); c++) {
                    String h = headers.get(c);
                    if (h.isEmpty() || CORE.contains(h)) continue;
                    String v = get(vals, c);
                    if (!v.isEmpty()) extras.put(h, v);
                }
                s.extras = extras;
                if (s.lati != null && s.longi != null) s.wkt = "POINT(" + s.longi + " " + s.lati + ")";
                result.sites.add(s);
            }
            return result;
        }
    }

    private static List<String> rowVals(Row r, DataFormatter fmt) {
        List<String> out = new ArrayList<>();
        if (r == null) return out;
        short last = r.getLastCellNum();
        for (int i = 0; i < last; i++) {
            Cell c = r.getCell(i);
            out.add(c == null ? "" : fmt.formatCellValue(c).strip());
        }
        return out;
    }

    private static String get(List<String> vals, int i) {
        return i < 0 || i >= vals.size() ? "" : vals.get(i);
    }

    private static String emptyToNull(String s) { return s == null || s.isBlank() ? null : s; }

    private static Double toFloat(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Double.parseDouble(s.strip()); } catch (Exception e) { return null; }
    }
}
