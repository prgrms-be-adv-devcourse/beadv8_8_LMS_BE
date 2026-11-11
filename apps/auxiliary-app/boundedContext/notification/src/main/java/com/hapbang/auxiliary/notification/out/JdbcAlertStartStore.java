package com.hapbang.auxiliary.notification.out;

import java.time.Instant;
import java.time.LocalDateTime;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.notification.app.AlertStartStore;

/**
 * 알림 시작 시각을 {@code notification.notification_runtime_setting}에 둔다(ND-095). 없을 때만 쓰고({@code ON CONFLICT DO NOTHING})
 * 다시 읽으므로, 여러 pod가 처음 동시에 떠도 먼저 쓴 값 하나를 모두 쓴다. 값은 {@link Instant#toString()}(ISO-8601 UTC)이다.
 */
@Component
class JdbcAlertStartStore implements AlertStartStore {

    private final JdbcTemplate jdbc;

    JdbcAlertStartStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Instant recordIfAbsent(String key, Instant now) {
        jdbc.update("""
                insert into notification.notification_runtime_setting (setting_key, setting_value, created_at)
                values (?, ?, ?)
                on conflict (setting_key) do nothing
                """, key, now.toString(), LocalDateTime.now());
        return Instant.parse(jdbc.queryForObject(
                "select setting_value from notification.notification_runtime_setting where setting_key = ?",
                String.class, key));
    }
}
