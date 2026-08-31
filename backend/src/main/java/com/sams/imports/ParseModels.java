package com.sams.imports;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ParseModels {
    private ParseModels() {}

    public static class SiteRow {
        public String siteId = "";
        public String option = "";
        public String project;
        public String siteStatus;
        public Double lati;
        public Double longi;
        public Map<String, Object> extras = new LinkedHashMap<>();
        public String wkt;
    }

    public static class RoadRow {
        public String property;
        public Map<String, Object> extras = new LinkedHashMap<>();
        public String wkt;
    }

    public static class LessorRow {
        public String fid = "";
        public String lessorName;
        public String lessorCategory;
        public String relationship;
        public Map<String, Object> extras = new LinkedHashMap<>();
        public String wkt;
    }

    public static class AreaRow {
        public String name = "";
        public Map<String, Object> extras = new LinkedHashMap<>();
        public String wkt;
    }

    public static class ParseResult {
        public List<SiteRow> sites = new ArrayList<>();
        public List<RoadRow> roads = new ArrayList<>();
        public List<LessorRow> lessors = new ArrayList<>();
        public List<AreaRow> areas = new ArrayList<>();
    }
}
