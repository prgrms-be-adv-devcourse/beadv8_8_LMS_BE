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
 * 회원 이벤트를 알림 회원 복제본에 반영한다(ND-018). 메서드마다 {@code notification} schema 안의 로컬 트랜잭션 하나다(ND-007).
 * <ul>
 *     <li>가입: 만들기만 한다. 이미 있으면 건너뛴다(같은 가입 이벤트의 재처리).</li>
 *     <li>변경: 있는 복제본만 회원 버전이 더 클 때 갱신한다. 없으면(가입 이벤트가 아직 처리되지 않음) 만들지 않고 실패해, 리스너의
 *     블로킹 재시도 뒤 DLT로 간다(ND-035).</li>
 * </ul>
 * 같은 회원을 동시에 만들거나 고치면 PK 유일 제약·복제본 낙관적 락으로 늦은 쪽이 실패하고, 재처리 때 위 규칙으로 정리된다.
 * 첫 배포 때 이미 가입한 회원의 Backfill은 정하지 않았다(U17, ND-048).
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
     * @throws NotificationException 복제할 수 없는 정보면 {@code INVALID_MEMBER_EVENT}, 복제본이 없으면
     *                               {@code NOTIFICATION_USER_NOT_FOUND}(만들지 않는다)
     */
    public NotificationUserSyncResult syncModified(UserDto user) {
        validate(user);
        NotificationUser replica = repository.findById(user.id())
                .orElseThrow(() -> new NotificationException(NotificationErrorCode.NOTIFICATION_USER_NOT_FOUND));
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
