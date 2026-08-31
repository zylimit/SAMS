package com.sams.exports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class ConflictsXlsxTest {

    @Test
    void pureAreaNotLiedAsNoConflict() throws Exception {
        byte[] data = ConflictsXlsx.build(List.of(areaConflict()));
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(data))) {
            assertTrue(sheetNames(wb).contains("Area Conflicts"));
            Sheet ws = wb.getSheet("Area Conflicts");
            assertNotEquals("无冲突", ws.getRow(0).getCell(0).getStringCellValue());
            assertEquals("area", ws.getRow(1).getCell(0).getStringCellValue());
            assertEquals("QC_NORTH", ws.getRow(1).getCell(1).getStringCellValue());
        }
    }

    @Test
    void mixedSiteAndArea() throws Exception {
        Map<String, Object> site = Map.of(
                "key", "site:S1:A", "kind", "site", "name", "S1 / A",
                "existing", Map.of("site_id", "S1", "option", "A", "project", "", "site_status", "",
                        "lati", 14.6, "longi", 121.0, "extras", Map.of()),
                "incoming", Map.of("site_id", "S1", "option", "A", "project", "", "site_status", "",
                        "lati", 14.7, "longi", 121.0, "extras", Map.of(), "source_file", "sites.kml"),
                "source_file", "sites.kml");
        byte[] data = ConflictsXlsx.build(List.of(site, areaConflict()));
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(data))) {
            assertEquals(List.of("Site Conflicts", "Area Conflicts"), sheetNames(wb));
        }
    }

    @Test
    void pureRoadHasPropertyColumns() throws Exception {
        Map<String, Object> road = Map.of(
                "key", "road:EDSA-SEG1", "kind", "road", "name", "EDSA-SEG1",
                "existing", Map.of("id", 7, "property", "EDSA-SEG1", "extras", Map.of("Length", "1.2km")),
                "incoming", Map.of("property", "EDSA-SEG1", "extras", Map.of("Length", "1.5km"), "source_file", "roads.kml"),
                "source_file", "roads.kml");
        byte[] data = ConflictsXlsx.build(List.of(road));
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(data))) {
            assertTrue(sheetNames(wb).contains("Road Conflicts"));
            Sheet ws = wb.getSheet("Road Conflicts");
            assertNotEquals("无冲突", ws.getRow(0).getCell(0).getStringCellValue());
        }
    }

    private static Map<String, Object> areaConflict() {
        return Map.of(
                "key", "area:Globe:QC_NORTH", "kind", "area", "name", "QC_NORTH",
                "existing", Map.of("id", 1, "name", "QC_NORTH", "operator", "Globe",
                        "extras", Map.of("polygon_id", "002-0019")),
                "incoming", Map.of("name", "QC_NORTH", "extras", Map.of("polygon_id", "002-0020"),
                        "wkt", "POLYGON((121 14.6, 121.1 14.6, 121.1 14.7, 121 14.6))", "source_file", "areas.kml"),
                "source_file", "areas.kml");
    }

    private static List<String> sheetNames(XSSFWorkbook wb) {
        List<String> names = new java.util.ArrayList<>();
        for (int i = 0; i < wb.getNumberOfSheets(); i++) names.add(wb.getSheetName(i));
        return names;
    }
}
