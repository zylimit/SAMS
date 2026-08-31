package com.sams.imports;

import com.sams.imports.ParseModels.ParseResult;
import java.io.ByteArrayInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class KmzParser {
    private final KmlParser kml = new KmlParser();

    public ParseResult parse(byte[] data) throws Exception {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.getName().toLowerCase().endsWith(".kml")) {
                    return kml.parse(zis.readAllBytes());
                }
            }
        }
        throw new IllegalArgumentException("KMZ 内未找到 .kml");
    }
}
