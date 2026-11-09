package com.hapbang.auxiliary.notification.app;

import static org.assertj.core.api.Assertions.*;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.hapbang.auxiliary.notification.app.NotificationUserSyncResult.Outcome;
import com.hapbang.auxiliary.notification.app.exception.NotificationErrorCode;
import com.hapbang.auxiliary.notification.app.exception.NotificationException;
import com.hapbang.auxiliary.notification.out.NotificationUserRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.testsupport.IntegrationTest;

/** 회원 이벤트를 알림 회원 복제본({@code notification.notification_user})에 반영하는 규칙(ND-018, ND-035, test-spec NT-USR-01·02) */
@IntegrationTest
class NotificationUserSyncServiceTest {

    private static final AtomicLong NEXT_ID = new AtomicLong(1_000);

    @Autowired
    private NotificationUserSyncService syncService;

    @Autowired
    private NotificationUserRepository repository;

    @Test
    void 가입하면_만들고_같은_가입을_다시_받으면_건너뛴다() {
        long id = NEXT_ID.incrementAndGet();

        assertThat(syncService.syncJoined(user(id, "게스트", 0L)).outcome()).isEqualTo(Outcome.CREATED);
        assertThat(syncService.syncJoined(user(id, "게스트", 0L)).outcome()).isEqualTo(Outcome.SKIPPED_EXISTING);

        assertThat(repository.count()).isEqualTo(1L);
        assertThat(repository.findById(id)).hasValueSatisfying(user -> {
            assertThat(user.getNickname()).isEqualTo("게스트");
            assertThat(user.getMemberVersion()).isZero();
        });
    }

    @Test
    void 변경은_더_큰_버전만_반영하고_옛_버전은_건너뛴다() {
        long id = NEXT_ID.incrementAndGet();
        syncService.syncJoined(user(id, "가입", 1L));

        assertThat(syncService.syncModified(user(id, "최신", 3L)).outcome()).isEqualTo(Outcome.UPDATED);
        assertThat(syncService.syncModified(user(id, "옛것", 2L)).outcome()).isEqualTo(Outcome.SKIPPED_STALE);

        assertThat(repository.findById(id)).hasValueSatisfying(user -> {
            assertThat(user.getNickname()).isEqualTo("최신");
            assertThat(user.getMemberVersion()).isEqualTo(3L);
        });
    }

    @Test
    void 복제본이_없을_때_온_변경은_만들지_않고_실패한다() {
        long id = NEXT_ID.incrementAndGet();

        assertThatThrownBy(() -> syncService.syncModified(user(id, "게스트", 1L)))
                .isInstanceOfSatisfying(NotificationException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(NotificationErrorCode.NOTIFICATION_USER_NOT_FOUND));
        assertThat(repository.findById(id)).isEmpty();
    }

    @Test
    void 복제할_수_없는_정보는_잘못된_요청으로_실패한다() {
        assertThatThrownBy(() -> syncService.syncJoined(new UserDto(NEXT_ID.incrementAndGet(), null, Role.USER,
                UserStatus.ACTIVE, 1L)))
                .isInstanceOfSatisfying(NotificationException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(NotificationErrorCode.INVALID_MEMBER_EVENT));
    }

    private static UserDto user(long id, String nickname, Long version) {
        return new UserDto(id, nickname, Role.INFLUENCER, UserStatus.ACTIVE, version);
    }
}
