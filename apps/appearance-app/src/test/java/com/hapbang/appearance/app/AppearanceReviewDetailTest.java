package com.hapbang.appearance.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.appearance.app.dto.AppearanceRequestResDto;
import com.hapbang.appearance.app.dto.AppearanceResDto;
import com.hapbang.appearance.app.dto.AppearanceReviewResDto;
import com.hapbang.appearance.domain.Appearance;
import com.hapbang.appearance.domain.AppearanceReviewPolicy;
import com.hapbang.appearance.fixture.AppearanceRequestFixture;
import com.hapbang.appearance.fixture.AppearanceUserFixture;
import com.hapbang.appearance.support.AppearanceIntegrationTest;
import com.hapbang.appearance.support.AppearanceTestClock;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;

/**
 * 출연 상세의 리뷰 정보(#514)를 DB로 확인한다: {@code reviewable}(작성 API와 같은 기준), {@code myReview},
 * {@code receivedReview}(두 리뷰가 함께 공개된 뒤에만).
 * 출연은 실제 순서대로 만든다: 요청서 수락(성립) → 시계를 방송 뒤로 → 호스트 확인.
 */
@AppearanceIntegrationTest
@WithMockUser(roles = "INFLUENCER")
class AppearanceReviewDetailTest {

    private static final Long HOST = 1L;
    private static final Long GUEST = 2L;
    private static final Duration WRITE_PERIOD = Duration.ofDays(14);

    @Autowired
    AppearanceFacade appearanceFacade;

    @Autowired
    AppearanceReviewFacade appearanceReviewFacade;

    @Autowired
    AppearanceRequestFacade appearanceRequestFacade;

    @Autowired
    AppearanceUserFacade appearanceUserFacade;

    @Autowired
    AppearanceSupport appearanceSupport;

    @Autowired
    AppearanceReviewSupport appearanceReviewSupport;

    @Autowired
    AppearanceUserSupport appearanceUserSupport;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    AppearanceTestClock clock;

    @BeforeEach
    void setUp() {
        for (Long id : List.of(HOST, GUEST)) {
            appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(id));
        }
    }

    /** 요청서가 수락돼 성립한 출연 ID */
    private Long established() {
        AppearanceRequestResDto sent = appearanceRequestFacade.create(HOST, GUEST,
                AppearanceRequestFixture.content(clock.now()));
        return appearanceRequestFacade.accept(GUEST, sent.id(), sent.version()).appearanceId();
    }

    private void broadcastEnded(Long appearanceId) {
        clock.advanceTo(jdbcTemplate.queryForObject("select scheduled_at from appearances.appearances where id = ?",
                LocalDateTime.class, appearanceId).plusHours(1));
    }

    /** 호스트가 확인한 출연 ID */
    private Long confirmed() {
        Long appearanceId = established();
        broadcastEnded(appearanceId);
        appearanceFacade.confirm(HOST, appearanceId);
        return appearanceId;
    }

    private AppearanceResDto detail(Long userId, Long appearanceId) {
        return appearanceFacade.getDetail(userId, appearanceId);
    }

    private void changeUser(Long userId, UserStatus status) {
        appearanceUserFacade.syncModified(AppearanceUserFixture.user(userId, Role.INFLUENCER, status));
    }

    @Test
    @DisplayName("출연 확인 전(성립)에는 두 사람 모두 쓸 수 없고 리뷰도 없다")
    void beforeConfirm() {
        Long appearanceId = established();

        for (Long userId : List.of(HOST, GUEST)) {
            AppearanceResDto detail = detail(userId, appearanceId);
            assertThat(detail.reviewable()).isFalse();
            assertThat(detail.myReview()).isNull();
            assertThat(detail.receivedReview()).isNull();
        }
    }

    @Test
    @DisplayName("출연 확인 응답부터 쓸 수 있다고 알려 준다 (확인한 호스트·상대 게스트 모두)")
    void confirmedIsReviewable() {
        Long appearanceId = established();
        broadcastEnded(appearanceId);

        AppearanceResDto confirmResponse = appearanceFacade.confirm(HOST, appearanceId);

        assertThat(confirmResponse.reviewable()).isTrue();
        assertThat(detail(GUEST, appearanceId).reviewable()).isTrue();
    }

    @Test
    @DisplayName("한쪽만 쓰면: 쓴 사람은 내 리뷰(공개 전)만 보고 다시 쓸 수 없다. 상대는 쓸 수 있고 받은 리뷰가 보이지 않는다 (블라인드)")
    void oneSideWritten() {
        Long appearanceId = confirmed();

        Long reviewId = appearanceReviewFacade.write(HOST, appearanceId, 4, "준비가 꼼꼼했습니다.").id();

        AppearanceResDto host = detail(HOST, appearanceId);
        assertThat(host.reviewable()).isFalse();
        assertThat(host.myReview()).isNotNull();
        assertThat(host.myReview().id()).isEqualTo(reviewId);
        assertThat(host.myReview().rating()).isEqualTo(4);
        assertThat(host.myReview().content()).isEqualTo("준비가 꼼꼼했습니다.");
        assertThat(host.myReview().published()).isFalse();
        assertThat(host.myReview().publishedAt()).isNull();
        assertThat(host.myReview().createdAt()).isNotNull();
        assertThat(host.receivedReview()).isNull();

        AppearanceResDto guest = detail(GUEST, appearanceId);
        assertThat(guest.reviewable()).isTrue();
        assertThat(guest.myReview()).isNull();
        assertThat(guest.receivedReview()).isNull();
    }

    @Test
    @DisplayName("둘 다 쓰면: 각자 내 리뷰(공개)와 상대가 쓴 받은 리뷰를 같은 공개 시각으로 보고, 더는 쓸 수 없다")
    void bothWritten() {
        Long appearanceId = confirmed();
        Long hostReviewId = appearanceReviewFacade.write(HOST, appearanceId, 4, "준비가 꼼꼼했습니다.").id();
        clock.advance(Duration.ofDays(2));
        Long guestReviewId = appearanceReviewFacade.write(GUEST, appearanceId, 5, null).id();

        AppearanceResDto host = detail(HOST, appearanceId);
        AppearanceResDto guest = detail(GUEST, appearanceId);

        assertThat(host.reviewable()).isFalse();
        assertThat(guest.reviewable()).isFalse();
        assertThat(host.myReview().id()).isEqualTo(hostReviewId);
        assertThat(host.myReview().published()).isTrue();
        assertThat(host.receivedReview().id()).isEqualTo(guestReviewId);
        assertThat(host.receivedReview().rating()).isEqualTo(5);
        assertThat(host.receivedReview().content()).isNull();
        assertThat(guest.myReview().id()).isEqualTo(guestReviewId);
        assertThat(guest.receivedReview().id()).isEqualTo(hostReviewId);
        assertThat(guest.receivedReview().content()).isEqualTo("준비가 꼼꼼했습니다.");

        AppearanceReviewResDto received = guest.receivedReview();
        assertThat(received.published()).isTrue();
        assertThat(received.publishedAt()).isNotNull().isEqualTo(guest.myReview().publishedAt());
    }

    @Test
    @DisplayName("상대가 정지되면 쓸 수 없다고 보여 주고(작성 API의 REVIEWEE_NOT_ACTIVE), 정상으로 돌아오면 다시 쓸 수 있다")
    void counterpartSuspended() {
        Long appearanceId = confirmed();

        changeUser(GUEST, UserStatus.SUSPENDED);
        assertThat(detail(HOST, appearanceId).reviewable()).isFalse();

        changeUser(GUEST, UserStatus.ACTIVE);
        assertThat(detail(HOST, appearanceId).reviewable()).isTrue();
    }

    @Test
    @DisplayName("작성자가 정지되면 자격을 보지 않는 출연 확인 응답에서도 쓸 수 없다고 보여 준다")
    void reviewerSuspended() {
        Long appearanceId = established();
        broadcastEnded(appearanceId);
        changeUser(HOST, UserStatus.SUSPENDED);

        AppearanceResDto confirmResponse = appearanceFacade.confirm(HOST, appearanceId);

        assertThat(confirmResponse.reviewable()).isFalse();
    }

    @Test
    @DisplayName("작성 기한을 켜면 확인 시각 + 14일 직전까지만 쓸 수 있다고 본다 (작성 API의 REVIEW_PERIOD_ENDED와 같은 기준)")
    void writePeriod() {
        Long appearanceId = confirmed();
        LocalDateTime confirmedAt = jdbcTemplate.queryForObject(
                "select confirmed_at from appearances.appearances where id = ?", LocalDateTime.class, appearanceId);
        AppearanceReviewEligibility withPeriod = new AppearanceReviewEligibility(appearanceReviewSupport,
                appearanceUserSupport, new AppearanceReviewPolicy(WRITE_PERIOD));

        Boolean[] writable = new TransactionTemplate(transactionManager).execute(status -> {
            Appearance appearance = appearanceSupport.getById(appearanceId);
            return new Boolean[]{
                    withPeriod.canWrite(GUEST, appearance, confirmedAt.plus(WRITE_PERIOD).minusSeconds(1)),
                    withPeriod.canWrite(GUEST, appearance, confirmedAt.plus(WRITE_PERIOD))};
        });

        assertThat(writable).containsExactly(true, false);
    }
}
