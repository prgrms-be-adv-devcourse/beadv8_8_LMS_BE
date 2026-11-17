package com.hapbang.chat.app;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import com.hapbang.chat.domain.ChatControlReason;
import com.hapbang.chat.domain.ChatControlType;
import com.hapbang.chat.domain.ChatPublishCheckpoint;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 회원 상태 변경·로그아웃에 따른 연결 제어 작업을 제어 outbox와 발행 체크포인트에 기록한다. 기록한 작업은 제어 Stream·제어
 * 채널을 거쳐 로컬 연결 재검사로 이어진다. 회원 반영과 같은 트랜잭션에서만 쓴다(MANDATORY). 전달 작업 기록
 * ({@link ChatDeliveryRecorder})과 같은 방식이다.
 * <p>
 * 재검사 작업의 중복은 유일 제약의 {@code ON CONFLICT DO NOTHING}으로 흡수해, 호출한 트랜잭션을 rollback-only로 만들지 않는다.
 * 로그아웃 작업은 중복 식별 없이 호출마다 넣는다. 계약: {@code apps/chat-app/docs/contracts/control-recording.md}
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatConnectionControl {

    private final ChatControlOutboxRepository chatControlOutboxRepository;
    private final ChatPublishCheckpointRepository chatPublishCheckpointRepository;

    /**
     * 회원의 로컬 연결 재검사 작업(RECHECK_USER)을 기록한다. 호출한 쪽의 트랜잭션 안에서만 쓴다(MANDATORY).
     * 같은 (회원, 원본 버전, 종류)는 한 번만 기록하고, 이미 있으면 아무것도 하지 않는다.
     *
     * @param memberVersion 반영한 회원 원본 버전. 버전 없는 회원 정보면 null
     */
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
                ChatPublishCheckpoint.pending(ChatJobStream.CONTROL.consumerGroup(), controlId, now));
        log.info("연결 제어 작업 기록 controlId={}, userId={}, memberVersion={}, reason={}", controlId, userId,
                memberVersion, reason);
    }

    /**
     * 회원 로그아웃에 따른 로컬 연결 전체 종료 작업(LOGOUT_USER)을 기록한다. 호출한 쪽의 트랜잭션 안에서만 쓴다(MANDATORY).
     * 원본 버전이 없고 같은 회원이 여러 번 로그아웃하므로 호출마다 한 행을 남긴다. 같은 이벤트를 다시 처리해 행이 늘어도 연결을
     * 다시 닫을 뿐이라 해가 없다(설계 문서 Q8).
     *
     * @param loggedOutAt 이 시각보다 먼저 인증한 연결만 닫는다. 늦게 다시 온 옛 로그아웃이 그 뒤에 다시 로그인한 연결을 닫지 않게 한다
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requestLogout(Long userId, Instant loggedOutAt) {
        Assert.notNull(userId, "userId가 필요합니다.");
        Assert.notNull(loggedOutAt, "loggedOutAt이 필요합니다.");
        UUID controlId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        chatControlOutboxRepository.insert(controlId, ChatControlType.LOGOUT_USER.name(), userId,
                ChatControlReason.MEMBER_LOGGED_OUT.name(), loggedOutAt, now);
        chatPublishCheckpointRepository.save(
                ChatPublishCheckpoint.pending(ChatJobStream.CONTROL.consumerGroup(), controlId, now));
        log.info("연결 제어 작업 기록 controlId={}, userId={}, type={}, loggedOutAt={}", controlId, userId,
                ChatControlType.LOGOUT_USER, loggedOutAt);
    }
}
