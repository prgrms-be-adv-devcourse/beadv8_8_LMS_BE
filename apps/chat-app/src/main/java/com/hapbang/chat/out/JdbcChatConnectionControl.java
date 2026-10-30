package com.hapbang.chat.out;

import com.hapbang.chat.app.ChatConnectionControl;
import com.hapbang.chat.domain.ChatControlReason;
import com.hapbang.chat.domain.ChatControlType;
import com.hapbang.chat.out.redis.ChatRedisKeys;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

/**
 * 연결 제어 작업을 제어 outbox와 발행 체크포인트에 기록한다. 회원 반영과 같은 트랜잭션에서만 쓴다.
 * 중복은 유일 제약의 {@code ON CONFLICT DO NOTHING}으로 흡수해, 호출한 트랜잭션을 rollback-only로 만들지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JdbcChatConnectionControl implements ChatConnectionControl {

    private final JdbcClient jdbcClient;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void requestRecheck(Long userId, Long memberVersion, ChatControlReason reason) {
        Assert.notNull(userId, "userId가 필요합니다.");
        Assert.notNull(reason, "reason이 필요합니다.");
        UUID controlId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        int inserted = jdbcClient.sql("""
                        insert into chats.chat_control_outbox (control_id, control_type, user_id, member_version, reason,
                                schema_version, status, attempt_count, next_attempt_at, requeue_count, created_at,
                                updated_at)
                        values (:controlId, :controlType, :userId, :memberVersion, :reason,
                                1, 'PENDING', 0, :now, 0, :now, :now)
                        on conflict (user_id, member_version, control_type) do nothing
                        """)
                .param("controlId", controlId)
                .param("controlType", ChatControlType.RECHECK_USER.name())
                .param("userId", userId)
                .param("memberVersion", memberVersion)
                .param("reason", reason.name())
                .param("now", now)
                .update();
        if (inserted == 0) {
            log.debug("이미 기록한 연결 제어 작업 userId={}, memberVersion={}", userId, memberVersion);
            return;
        }
        jdbcClient.sql("""
                        insert into chats.chat_publish_checkpoint (consumer_group, job_id, status, generation,
                                attempt_count, created_at, updated_at)
                        values (:group, :jobId, 'PENDING', 0, 0, :now, :now)
                        """)
                .param("group", ChatRedisKeys.CONTROL_GROUP)
                .param("jobId", controlId)
                .param("now", now)
                .update();
        log.info("연결 제어 작업 기록 controlId={}, userId={}, memberVersion={}, reason={}", controlId, userId,
                memberVersion, reason);
    }
}
