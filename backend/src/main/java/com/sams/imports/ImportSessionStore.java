package com.sams.imports;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class ImportSessionStore {
    public static final int TTL_SECONDS = 30 * 60;
    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            "cleaning", Set.of("conflicts"),
            "conflicts", Set.of("cleaning", "committing"),
            "committing", Set.of("done"));

    private final ConcurrentHashMap<String, Map<String, Object>> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Map<String, Object>> progress = new ConcurrentHashMap<>();

    public String create(Map<String, Object> data, String state) {
        gc();
        String sid = UUID.randomUUID().toString().replace("-", "");
        data.put("_created", System.currentTimeMillis());
        data.put("_state", state);
        sessions.put(sid, data);
        return sid;
    }

    public Map<String, Object> get(String sid) {
        gc();
        return sessions.get(sid);
    }

    public void update(String sid, Map<String, Object> patch) {
        Map<String, Object> s = sessions.get(sid);
        if (s != null) s.putAll(patch);
    }

    public String transition(String sid, String to) {
        Map<String, Object> s = sessions.get(sid);
        if (s == null) return "session 不存在或已过期";
        String cur = String.valueOf(s.getOrDefault("_state", ""));
        if (!TRANSITIONS.getOrDefault(cur, Set.of()).contains(to)) {
            return "invalid_state（当前 " + cur + "，不能转到 " + to + "）";
        }
        s.put("_state", to);
        return null;
    }

    public boolean drop(String sid) {
        progress.remove(sid);
        return sessions.remove(sid) != null;
    }

    public void setProgress(String sid, int done, int total, String phase) {
        progress.put(sid, Map.of("done", done, "total", total, "phase", phase));
    }

    public Map<String, Object> getProgress(String sid) {
        return progress.get(sid);
    }

    public void clearProgress(String sid) {
        progress.remove(sid);
    }

    private void gc() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(e -> now - ((Number) e.getValue().getOrDefault("_created", now)).longValue() > TTL_SECONDS * 1000L);
    }
}
