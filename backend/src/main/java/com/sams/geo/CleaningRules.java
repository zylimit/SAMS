package com.sams.geo;

public final class CleaningRules {
    public static final double BASELINE_THRESHOLD = 0.70;

    private CleaningRules() {}

    /** 返回 swap_latlong / missing_decimal / null。 */
    public static String detectSwapOrMissingDecimal(Double lati, Double longi) {
        if (lati == null || longi == null) return null;
        boolean latOk = lati >= -90.0 && lati <= 90.0;
        boolean lonOk = longi >= -180.0 && longi <= 180.0;
        if (latOk && lonOk) return null;
        if (!latOk && lonOk && longi >= -90.0 && longi <= 90.0 && lati >= -180.0 && lati <= 180.0) {
            return "swap_latlong";
        }
        return "missing_decimal";
    }
}
