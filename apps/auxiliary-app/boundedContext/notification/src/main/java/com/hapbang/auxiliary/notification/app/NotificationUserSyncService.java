package com.hapbang.auxiliary.notification.app;

import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.auxiliary.notification.app.NotificationUserSyncResult.Outcome;
import com.hapbang.auxiliary.notification.app.exception.NotificationErrorCode;
import com.hapbang.auxiliary.notification.app.exception.NotificationException;
import com.hapbang.auxiliary.notification.domain.NotificationUser;
import com.hapbang.auxiliary.notification.out.NotificationUserRepository;
import com.hapbang.shared.member.dto.UserDto;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 회원 이벤트를 알림 회원 복제본에 반영한다(ND-018, ND-049). 메서드마다 {@code notification} schema 안의 로컬 트랜잭션 하나다(ND-007).
 * <ul>
 *     <li>가입: 없으면 만든다. 이미 있으면 그대로 둔다(같은 가입 이벤트의 재처리, 변경으로 먼저 만든 뒤 늦게 온 가입).</li>
 *     <li>변경: 없으면 그 이벤트의 회원 정보로 만든다(ND-049. 보존 기간보다 먼저 가입해 가입 이벤트가 토픽에 없는 회원, 가입보다 먼저
 *     도착한 변경). 있으면 회원 버전이 더 클 때만 갱신한다.</li>
 *     <li>복제할 수 없는 정보(ID·닉네임·역할·상태 없음)는 가입·변경 모두 {@code INVALID_MEMBER_EVENT}로 실패한다.</li>
 * </ul>
 * 같은 회원을 동시에 만들거나 고치면 PK 유일 제약·복제본 낙관적 락으로 늦은 쪽이 실패하고, 리스너의 블로킹 재시도 때 위 규칙으로
 * 정리된다. 변경 이벤트로 만드는 것은 Backfill을 대체하지 않는다: 변경이 오지 않는 회원은 복제본이 없다(U17).
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class NotificationUserSyncService {

    private final NotificationUserRepository repository;

    /**
     * @throws NotificationException 복제할 수 없는 정보면 {@code INVALID_MEMBER_EVENT}
     */
    public NotificationUserSyncResult syncJoined(UserDto user) {
        validate(user);
        return repository.findById(user.id())
                .map(existing -> {
                    log.debug("이미 복제된 알림 회원이라 가입 이벤트를 건너뜀 userId={}", user.id());
                    return result(existing, Outcome.SKIPPED_EXISTING);
                })
                .orElseGet(() -> result(repository.save(NotificationUser.create(user)), Outcome.CREATED));
    }

    /**
     * @throws NotificationException 복제할 수 없는 정보면 {@code INVALID_MEMBER_EVENT}
     */
    public NotificationUserSyncResult syncModified(UserDto user) {
        validate(user);
        return repository.findById(user.id())
                .map(replica -> apply(replica, user))
                .orElseGet(() -> {
                    log.info("알림 회원 복제본이 없어 변경 이벤트로 만든다 userId={}, memberVersion={}", user.id(),
                            user.version());
                    return result(repository.save(NotificationUser.create(user)), Outcome.CREATED_FROM_MODIFIED);
                });
    }

    // 버전 규칙은 ReplicaUser.syncFrom이 판단한다. 반영 전 버전이 없으면 언제나 반영되고, 있으면 반영될 때만 버전이 바뀐다
    private static NotificationUserSyncResult apply(NotificationUser replica, UserDto user) {
        Long before = replica.getMemberVersion();
        replica.syncFrom(user);
        boolean applied = before == null || !Objects.equals(before, replica.getMemberVersion());
        if (!applied) {
            log.debug("이미 반영한 회원 버전이라 변경 이벤트를 건너뜀 userId={}, memberVersion={}, incoming={}", user.id(),
                    before, user.version());
            return result(replica, Outcome.SKIPPED_STALE);
        }
        return result(replica, Outcome.UPDATED);
    }

    private static NotificationUserSyncResult result(NotificationUser replica, Outcome outcome) {
        return new NotificationUserSyncResult(replica.getId(), outcome, replica.getMemberVersion());
    }

    private static void validate(UserDto user) {
        if (!NotificationUser.isReplicable(user)) {
            throw new NotificationException(NotificationErrorCode.INVALID_MEMBER_EVENT);
        }
    }
}
