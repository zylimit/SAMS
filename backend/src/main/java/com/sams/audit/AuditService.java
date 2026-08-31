package com.sams.audit;

import com.sams.auth.AuthContext;
import com.sams.auth.CurrentUser;
import com.sams.common.HttpUtil;
import com.sams.common.JsonMaps;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private final JdbcTemplate jdbc;

    public AuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void write(String action, Map<String, Object> details, HttpServletRequest request) {
        write(action, details, "success", null, request, null);
    }

    public void write(
            String action,
            Map<String, Object> details,
            String result,
            String errorMsg,
            HttpServletRequest request,
            String username) {
        if (username == null && request != null) {
            CurrentUser u = AuthContext.get();
            if (u != null) username = u.username();
        }
        try {
            String sid = null;
            CurrentUser u = AuthContext.get();
            if (u != null) sid = u.jti();
            jdbc.update(
                    """
                    INSERT INTO audit_log (session_id, ip, user_agent, username, action, details, result, error_msg)
                    VALUES (?,?,?,?,?,CAST(? AS JSON),?,?)
                    """,
                    sid,
                    HttpUtil.clientIp(request),
                    HttpUtil.userAgent(request),
                    username,
                    action,
                    JsonMaps.stringify(details == null ? Map.of() : details),
                    result,
                    errorMsg);
        } catch (Exception e) {
            log.warn("audit write failed action={} err={}", action, e.toString());
        }
    }
}
