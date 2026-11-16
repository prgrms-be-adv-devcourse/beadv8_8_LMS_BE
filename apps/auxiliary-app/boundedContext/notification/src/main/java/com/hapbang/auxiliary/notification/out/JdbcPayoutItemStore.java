package com.hapbang.auxiliary.notification.out;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.notification.app.DailyPayout;
import com.hapbang.auxiliary.notification.app.PayoutItemStore;

/**
 * 하루 묶음 지급 기록을 {@code notification.notification_payout_item}에 둔다(ND-103). 같은 받는 사람의 처리는 받는 사람 행 잠금으로
 * 줄을 서므로(ND-053) 확인과 기록 사이에 끼어드는 같은 지급이 없고, PK가 마지막으로 막는다.
 */
@Component
class JdbcPayoutItemStore implements PayoutItemStore {

    private final JdbcTemplate jdbc;

    JdbcPayoutItemStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean exists(long settlementId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from notification.notification_payout_item where settlement_id = ?)",
                Boolean.class, settlementId));
    }

    @Override
    public void record(DailyPayout payout, long notificationId) {
        jdbc.update("""
                insert into notification.notification_payout_item
                    (settlement_id, notification_id, receiver_user_id, paid_amount_hp, paid_at, created_at)
                values (?, ?, ?, ?, ?, ?)
                """, payout.settlementId(), notificationId, payout.receiverUserId(), payout.paidAmountHp(),
                Timestamp.from(payout.paidAt()), LocalDateTime.now());
    }
}
