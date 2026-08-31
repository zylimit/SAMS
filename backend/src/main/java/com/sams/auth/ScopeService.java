package com.sams.auth;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.lang.Nullable;

public final class ScopeService {
    public static final String FULL = "*";
    public static final String[] OPERATOR_NODES = {"Globe", "Smart", "Dito"};
    public static final Map<String, String> CATEGORY_NODE_TO_DB = Map.of(
            "EXISTING", "存量", "PLANNED", "规划", "SURVEY", "勘测");
    public static final String AREA_NODE = "AREA";

    private ScopeService() {}

    public static List<String> visibleScopes(CurrentUser user) {
        if (user == null || user.isAdmin()) return List.of(FULL);
        return user.scopes() == null ? List.of() : user.scopes();
    }

    public static boolean validateScopeNode(Object node) {
        if (!(node instanceof String s)) return false;
        if (s.equals("site") || s.equals("road") || s.equals("lessor")) return true;
        String[] parts = s.split(":");
        if (parts.length == 2) return "site".equals(parts[0]) && isOp(parts[1]);
        if (parts.length == 3) {
            return "site".equals(parts[0]) && isOp(parts[1])
                    && (CATEGORY_NODE_TO_DB.containsKey(parts[2]) || AREA_NODE.equals(parts[2]));
        }
        return false;
    }

    /**
     * null = 全量；空列表 = 无 site；否则 (operator, category|null)。
     */
    @Nullable
    public static List<OpCat> siteScopePairs(List<String> scopes) {
        if (scopes.contains(FULL) || scopes.contains("site")) return null;
        Set<String> fullOps = new LinkedHashSet<>();
        Set<OpCat> catPairs = new LinkedHashSet<>();
        for (String s : scopes) {
            String[] parts = s.split(":");
            if (parts.length == 2 && "site".equals(parts[0])) fullOps.add(parts[1]);
            else if (parts.length == 3 && "site".equals(parts[0])) {
                String cat = CATEGORY_NODE_TO_DB.get(parts[2]);
                if (cat != null) catPairs.add(new OpCat(parts[1], cat));
            }
        }
        List<OpCat> pairs = new ArrayList<>();
        for (String op : fullOps) pairs.add(new OpCat(op, null));
        catPairs.stream().filter(p -> !fullOps.contains(p.operator())).sorted().forEach(pairs::add);
        return pairs;
    }

    public static boolean canSeeRoad(List<String> scopes) {
        return scopes.contains(FULL) || scopes.contains("road");
    }

    public static boolean canSeeLessor(List<String> scopes) {
        return scopes.contains(FULL) || scopes.contains("lessor");
    }

    /** null = 全量；空集 = 全不可见。 */
    @Nullable
    public static Set<String> areaScopeOperators(List<String> scopes) {
        if (scopes.contains(FULL) || scopes.contains("site")) return null;
        Set<String> ops = new LinkedHashSet<>();
        for (String s : scopes) {
            String[] parts = s.split(":");
            if (parts.length == 2 && "site".equals(parts[0]) && isOp(parts[1])) ops.add(parts[1]);
            else if (parts.length == 3 && "site".equals(parts[0]) && isOp(parts[1]) && AREA_NODE.equals(parts[2])) {
                ops.add(parts[1]);
            }
        }
        return ops;
    }

    public static boolean siteRowVisible(List<String> scopes, String operator, String category) {
        List<OpCat> pairs = siteScopePairs(scopes);
        if (pairs == null) return true;
        return pairs.stream().anyMatch(p -> p.operator().equals(operator)
                && (p.category() == null || p.category().equals(category)));
    }

    public static boolean importTargetVisible(List<String> scopes, String targetKind, String operator, String category) {
        if (targetKind == null) return true;
        return switch (targetKind) {
            case "road" -> canSeeRoad(scopes);
            case "lessor" -> canSeeLessor(scopes);
            case "area" -> {
                if (operator == null || operator.isBlank()) yield false;
                Set<String> ops = areaScopeOperators(scopes);
                yield ops == null || ops.contains(operator);
            }
            default -> {
                if (operator == null || category == null) yield false;
                yield siteRowVisible(scopes, operator, category);
            }
        };
    }

    private static boolean isOp(String s) {
        for (String o : OPERATOR_NODES) if (o.equals(s)) return true;
        return false;
    }

    public record OpCat(String operator, String category) implements Comparable<OpCat> {
        @Override
        public int compareTo(OpCat o) {
            int c = operator.compareTo(o.operator);
            if (c != 0) return c;
            String a = category == null ? "" : category;
            String b = o.category == null ? "" : o.category;
            return a.compareTo(b);
        }
    }
}
