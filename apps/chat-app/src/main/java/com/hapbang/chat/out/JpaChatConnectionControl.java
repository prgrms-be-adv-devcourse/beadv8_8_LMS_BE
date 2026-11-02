package com.hapbang.chat.out;

import com.hapbang.chat.app.ChatConnectionControl;
import com.hapbang.chat.domain.ChatControlReason;
import com.hapbang.chat.domain.ChatControlType;
import com.hapbang.chat.domain.ChatPublishCheckpoint;
import com.hapbang.chat.out.redis.ChatRedisKeys;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

/**
 * 연결 제어 작업을 제어 outbox와 발행 체크포인트에 기록한다. 회원 반영과 같은 트랜잭션에서만 쓴다.
 * 재검사 작업의 중복은 유일 제약의 {@code ON CONFLICT DO NOTHING}으로 흡수해, 호출한 트랜잭션을 rollback-only로 만들지 않는다.
 * 로그아웃 작업은 중복 식별 없이 호출마다 넣는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JpaChatConnectionControl implements ChatConnectionControl {

    private final ChatControlOutboxRepository chatControlOutboxRepository;
    private final ChatPublishCheckpointRepository chatPublishCheckpointRepository;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void requestRecheck(Long userId, Long memberVersion, ChatControlReason reason) {
        Assert.notNull(userId, "userId가 필요합니다.");
        Assert.notNull(reason, "reason이 필요합니다.");
        UUID controlId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        int inserted = chatControlOutboxRepository.insertIfAbsent(controlId, ChatControlType.RECHECK_USER.name(),
                userId, memberVersion, reason.name(), now);
        if (inserted == 0) {
            log.debug("이미 기록한 연결 제어 작업 userId={}, memberVersion={}", userId, memberVersion);
            return;
        }
        chatPublishCheckpointRepository.save(
                ChatPublishCheckpoint.pending(ChatRedisKeys.CONTROL_GROUP, controlId, now));
        log.info("연결 제어 작업 기록 controlId={}, userId={}, memberVersion={}, reason={}", controlId, userId,
                memberVersion, reason);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void requestLogout(Long userId, Instant loggedOutAt) {
        Assert.notNull(userId, "userId가 필요합니다.");
        Assert.notNull(loggedOutAt, "loggedOutAt이 필요합니다.");
        UUID controlId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        chatControlOutboxRepository.insert(controlId, ChatControlType.LOGOUT_USER.name(), userId,
                ChatControlReason.MEMBER_LOGGED_OUT.name(), loggedOutAt, now);
        chatPublishCheckpointRepository.save(
                ChatPublishCheckpoint.pending(ChatRedisKeys.CONTROL_GROUP, controlId, now));
        log.info("연결 제어 작업 기록 controlId={}, userId={}, type={}, loggedOutAt={}", controlId, userId,
                ChatControlType.LOGOUT_USER, loggedOutAt);
    }
}
