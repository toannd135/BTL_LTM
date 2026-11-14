package ftpserver.core;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import ftpserver.db.Database;

/**
 * Bang ftp_sessions: cac phien FTP dang song cua tien trinh nay, de trang quan tri (tien trinh rieng,
 * dung chung CSDL) liet ke va yeu cau ngat phien (kill_requested=1).
 *
 * <p>Nhip tim moi {@value #HEARTBEAT_SECONDS}s cap nhat last_seen va ngat cac phien bi yeu cau.
 * Moi loi CSDL chi duoc ghi log: so dang ky hong khong bao gio lam hong phien FTP.
 */
public class SessionRegistry {

    static final int HEARTBEAT_SECONDS = 5;
    /** Dong co last_seen cu hon nguong nay luc khoi dong la rac do tien trinh truoc bi sap. */
    static final int STALE_SECONDS = 60;

    /** ISO-8601 UTC co mili giay, do dai co dinh nen so sanh chuoi dung thu tu thoi gian. */
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final Database db;
    private final String serverId = UUID.randomUUID().toString();
    private final Map<String, ClientSession> live = new ConcurrentHashMap<>();
    private ScheduledExecutorService heartbeat;

    public SessionRegistry(Database db) {
        this.db = db;
    }

    public String serverId() {
        return serverId;
    }

    static String ts(Instant t) {
        return TS.format(t);
    }

    /** Xoa dong rac (last_seen qua cu) roi bat nhip tim chay nen. */
    public synchronized void start() {
        purgeStale();
        if (heartbeat == null) {
            heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "session-heartbeat");
                t.setDaemon(true);
                return t;
            });
            heartbeat.scheduleWithFixedDelay(this::heartbeat, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
        }
    }

    /** Dung nhip tim va xoa moi dong cua tien trinh nay. */
    public synchronized void stop() {
        if (heartbeat != null) {
            heartbeat.shutdownNow();
            heartbeat = null;
        }
        exec("DELETE FROM ftp_sessions WHERE server_id=?", serverId);
    }

    void purgeStale() {
        exec("DELETE FROM ftp_sessions WHERE last_seen < ?", ts(Instant.now().minusSeconds(STALE_SECONDS)));
    }

    /** Phien moi mo ket noi dieu khien. Xoa dong cung id truoc (rac tu lan chay truoc, vi ma S1, S2... lap lai). */
    public void register(ClientSession session, String ip) {
        live.put(session.sessionId(), session);
        String now = ts(Instant.now());
        exec("DELETE FROM ftp_sessions WHERE id=?", session.sessionId());
        exec("INSERT INTO ftp_sessions(id, server_id, ip, username, tls, started_at, last_seen, kill_requested)"
                + " VALUES (?,?,?,NULL,0,?,?,0)", session.sessionId(), serverId, ip, now, now);
    }

    /** Dang nhap thanh cong (username) hoac REIN (null). */
    public void setUsername(String sessionId, String username) {
        exec("UPDATE ftp_sessions SET username=? WHERE id=? AND server_id=?", username, sessionId, serverId);
    }

    public void setTls(String sessionId) {
        exec("UPDATE ftp_sessions SET tls=1 WHERE id=? AND server_id=?", sessionId, serverId);
    }

    public void unregister(String sessionId) {
        live.remove(sessionId);
        exec("DELETE FROM ftp_sessions WHERE id=? AND server_id=?", sessionId, serverId);
    }

    /** Mot nhip: cap nhat last_seen, roi ngat cac phien co kill_requested=1. Khong bao gio nem ngoai le. */
    void heartbeat() {
        try {
            exec("UPDATE ftp_sessions SET last_seen=? WHERE server_id=?", ts(Instant.now()), serverId);
            for (String id : killRequested()) {
                ClientSession s = live.get(id);
                if (s == null) {
                    // phien da ket thuc nhung dong con sot lai
                    exec("DELETE FROM ftp_sessions WHERE id=? AND server_id=?", id, serverId);
                    continue;
                }
                s.terminateByAdmin();
                exec("UPDATE ftp_sessions SET kill_requested=0 WHERE id=? AND server_id=?", id, serverId);
            }
        } catch (RuntimeException e) {
            System.err.println("Session registry heartbeat failed: " + e.getMessage());
        }
    }

    private List<String> killRequested() {
        List<String> ids = new ArrayList<>();
        if (db == null) {
            return ids;
        }
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(
                "SELECT id FROM ftp_sessions WHERE server_id=? AND kill_requested=1")) {
            ps.setString(1, serverId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            System.err.println("Session registry query failed: " + e.getMessage());
        }
        return ids;
    }

    private void exec(String sql, String... params) {
        if (db == null) {
            return;
        }
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setString(i + 1, params[i]);
            }
            ps.executeUpdate();
        } catch (SQLException | RuntimeException e) {
            System.err.println("Session registry DB error: " + e.getMessage());
        }
    }
}
