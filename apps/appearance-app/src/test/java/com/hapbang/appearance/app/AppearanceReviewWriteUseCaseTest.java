package com.hapbang.appearance.app;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import com.hapbang.appearance.domain.Appearance;
import com.hapbang.appearance.domain.AppearanceErrorCode;
import com.hapbang.appearance.domain.AppearanceException;
import com.hapbang.appearance.domain.AppearancePolicy;
import com.hapbang.appearance.domain.AppearanceRequest;
import com.hapbang.appearance.domain.AppearanceReviewPolicy;
import com.hapbang.appearance.fixture.AppearanceRequestFixture;

/**
 * 같은 회원의 리뷰가 앞선 확인을 동시에 통과해 DB 유니크 제약에 걸린 경우를 확인한다 (#512).
 * 운영에서는 출연 행 잠금이 먼저 막아 재현할 수 없으므로 저장소 대역으로 제약 위반을 만든다.
 */
class AppearanceReviewWriteUseCaseTest {

    private static final Long HOST = 1L;
    private static final Long GUEST = 2L;
    private static final Long APPEARANCE_ID = 30L;
    private static final LocalDateTime SENT_AT = LocalDateTime.of(2026, 10, 13, 12, 0);
    private static final AppearancePolicy POLICY = new AppearancePolicy(20_000, 30, Duration.ofDays(7), 2, 10);

    private final AppearanceSupport appearanceSupport = mock(AppearanceSupport.class);
    private final AppearanceReviewSupport appearanceReviewSupport = mock(AppearanceReviewSupport.class);
    private final AppearanceUserSupport appearanceUserSupport = mock(AppearanceUserSupport.class);
    private final Clock clock = Clock.fixed(SENT_AT.plusDays(10).atZone(ZoneId.of("Asia/Seoul")).toInstant(),
            ZoneId.of("Asia/Seoul"));
    private final AppearanceReviewWriteUseCase useCase = new AppearanceReviewWriteUseCase(appearanceSupport,
            appearanceReviewSupport, appearanceUserSupport, AppearanceReviewPolicy.unlimited(), clock);

    @BeforeEach
    void setUp() {
        AppearanceRequest request = AppearanceRequest.create(HOST, GUEST,
                AppearanceRequestFixture.content(SENT_AT, 30_000), POLICY, SENT_AT);
        request.markRequested();
        ReflectionTestUtils.setField(request, "id", 12L);
        ReflectionTestUtils.setField(request, "version", 0L);
        request.accept(GUEST, 0L, SENT_AT.plusDays(1));
        Appearance appearance = Appearance.establishFrom(request, SENT_AT.plusDays(1));
        ReflectionTestUtils.setField(appearance, "id", APPEARANCE_ID);
        appearance.confirmByHost(HOST, POLICY, SENT_AT.plusDays(7).plusHours(1));

        given(appearanceSupport.getForUpdate(APPEARANCE_ID)).willReturn(appearance);
        given(appearanceUserSupport.isActive(anyLong())).willReturn(true);
        given(appearanceReviewSupport.existsByAppearanceAndReviewer(APPEARANCE_ID, GUEST)).willReturn(false);
    }

    @Test
    @DisplayName("유니크 제약(uk_appearance_reviews_appearance_reviewer) 위반은 409 ALREADY_REVIEWED로 바꾼다")
    void uniqueViolationIsAlreadyReviewed() {
        given(appearanceReviewSupport.saveNow(any())).willThrow(new DataIntegrityViolationException("중복",
                new SQLException("duplicate key value violates unique constraint "
                        + "\"uk_appearance_reviews_appearance_reviewer\"")));

        assertThatThrownBy(() -> useCase.write(GUEST, APPEARANCE_ID, 5, null))
                .isInstanceOf(AppearanceException.class)
                .extracting("errorCode").isEqualTo(AppearanceErrorCode.ALREADY_REVIEWED);
    }

    @Test
    @DisplayName("다른 제약 위반은 바꾸지 않고 그대로 던진다 (결함을 업무 오류로 숨기지 않음)")
    void otherViolationPropagates() {
        DataIntegrityViolationException other = new DataIntegrityViolationException("다른 제약",
                new SQLException("new row violates check constraint \"ck_appearance_reviews_rating\""));
        given(appearanceReviewSupport.saveNow(any())).willThrow(other);

        assertThatThrownBy(() -> useCase.write(GUEST, APPEARANCE_ID, 5, null)).isSameAs(other);
    }
}
