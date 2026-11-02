package com.hapbang.appearance.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.appearance.app.dto.AppearanceRequestResDto;
import com.hapbang.appearance.domain.AppearanceErrorCode;
import com.hapbang.appearance.domain.AppearanceException;
import com.hapbang.appearance.domain.AppearanceReviewPolicy;
import com.hapbang.appearance.fixture.AppearanceRecruitFixture;
import com.hapbang.appearance.fixture.AppearanceRequestFixture;
import com.hapbang.appearance.fixture.AppearanceUserFixture;
import com.hapbang.appearance.support.AppearanceIntegrationTest;
import com.hapbang.appearance.support.AppearanceTestClock;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;

/**
 * 출연 리뷰 작성과 블라인드 공개를 DB로 확인한다 (#512, ISSUE.md APR-45).
 * 출연은 실제 순서대로 만든다: 요청서 수락(성립) → 시계를 방송 뒤로 → 호스트 확인 또는 자동 확인.
 * 작성 기한이 있는 경우는 운영 설정(무기한)을 바꾸지 않고, 기한 14일 정책으로 만든 UseCase를 같은 저장소·시계로 직접 부른다.
 */
@AppearanceIntegrationTest
@WithMockUser(roles = "INFLUENCER")
class AppearanceReviewFlowTest {

    private static final Long HOST = 1L;
    private static final Long GUEST = 2L;
    private static final Long OTHER = 3L;
    private static final Long GUEST2 = 4L;
    private static final long FEE = 30_000L;
    private static final Duration WRITE_PERIOD = Duration.ofDays(14);

    @Autowired
    AppearanceReviewFacade appearanceReviewFacade;

    @Autowired
    AppearanceFacade appearanceFacade;

    @Autowired
    AppearanceRequestFacade appearanceRequestFacade;

    @Autowired
    AppearanceRecruitFacade appearanceRecruitFacade;

    @Autowired
    AppearanceApplicationFacade appearanceApplicationFacade;

    @Autowired
    AppearanceUserFacade appearanceUserFacade;

    @Autowired
    AppearanceAutoConfirmUseCase appearanceAutoConfirmUseCase;

    @Autowired
    AppearanceRecruitDeadlineCloseUseCase appearanceRecruitDeadlineCloseUseCase;

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
        for (Long id : List.of(HOST, GUEST, OTHER, GUEST2)) {
            appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(id));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 준비

    /** 요청서가 수락돼 성립한 출연 ID */
    private Long established() {
        AppearanceRequestResDto sent = appearanceRequestFacade.create(HOST, GUEST,
                AppearanceRequestFixture.content(clock.now()));
        return appearanceRequestFacade.accept(GUEST, sent.id(), sent.version()).appearanceId();
    }

    /** 방송이 끝나 확인 기간에 들어선다 (시계를 방송 예정 일시 1시간 뒤로) */
    private void broadcastEnded(Long appearanceId) {
        clock.advanceTo(scheduledAtOf(appearanceId).plusHours(1));
    }

    /** 호스트가 확인한 출연 ID */
    private Long confirmed() {
        Long appearanceId = established();
        broadcastEnded(appearanceId);
        appearanceFacade.confirm(HOST, appearanceId);
        return appearanceId;
    }

    private LocalDateTime scheduledAtOf(Long appearanceId) {
        return jdbcTemplate.queryForObject("select scheduled_at from appearances.appearances where id = ?",
                LocalDateTime.class, appearanceId);
    }

    private LocalDateTime confirmedAtOf(Long appearanceId) {
        return jdbcTemplate.queryForObject("select confirmed_at from appearances.appearances where id = ?",
                LocalDateTime.class, appearanceId);
    }

    private LocalDateTime publishedAtOf(Long appearanceId, Long reviewerId) {
        return jdbcTemplate.queryForObject(
                "select published_at from appearances.reviews where appearance_id = ? and reviewer_id = ?",
                LocalDateTime.class, appearanceId, reviewerId);
    }

    private int reviewCount(Long appearanceId) {
        return jdbcTemplate.queryForObject("select count(*) from appearances.reviews where appearance_id = ?",
                Integer.class, appearanceId);
    }

    private void changeUser(Long userId, Role role, UserStatus status) {
        appearanceUserFacade.syncModified(AppearanceUserFixture.user(userId, role, status));
    }

    private Long write(Long reviewerId, Long appearanceId) {
        return appearanceReviewFacade.write(reviewerId, appearanceId, 5, "좋았습니다.").id();
    }

    private static void assertErrorCode(Runnable action, AppearanceErrorCode errorCode) {
        assertThatThrownBy(action::run)
                .isInstanceOf(AppearanceException.class)
                .extracting("errorCode").isEqualTo(errorCode);
    }

    // ---------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("작성과 블라인드 공개")
    class WriteAndPublish {

        @Test
        @DisplayName("호스트가 먼저 쓰면 저장만 되고 공개되지 않는다 (별점·본문·받는 사람·역할 저장)")
        void firstIsHidden() {
            Long appearanceId = confirmed();

            Long reviewId = appearanceReviewFacade.write(HOST, appearanceId, 4, "  준비가 꼼꼼했습니다. ").id();

            assertThat(reviewId).isNotNull();
            assertThat(jdbcTemplate.queryForMap("""
                    select reviewer_id, reviewee_id, reviewee_role, rating, content, published_at
                    from appearances.reviews where id = ?""", reviewId))
                    .containsEntry("reviewer_id", HOST)
                    .containsEntry("reviewee_id", GUEST)
                    .containsEntry("reviewee_role", "GUEST")
                    .containsEntry("rating", 4)
                    .containsEntry("content", "준비가 꼼꼼했습니다.")
                    .containsEntry("published_at", null);
        }

        @Test
        @DisplayName("상대가 쓰면 두 리뷰가 같은 시각에 함께 공개된다 (공백뿐인 본문은 null)")
        void secondPublishesBoth() {
            Long appearanceId = confirmed();
            write(HOST, appearanceId);
            clock.advance(Duration.ofDays(3));

            appearanceReviewFacade.write(GUEST, appearanceId, 3, "   ");

            LocalDateTime publishedAt = clock.now();
            assertThat(publishedAtOf(appearanceId, HOST)).isEqualTo(publishedAtOf(appearanceId, GUEST));
            assertThat(publishedAtOf(appearanceId, GUEST)).isNotNull()
                    .isBeforeOrEqualTo(publishedAt).isAfter(publishedAt.minusMinutes(1));
            assertThat(jdbcTemplate.queryForObject(
                    "select content from appearances.reviews where appearance_id = ? and reviewer_id = ?",
                    String.class, appearanceId, GUEST)).isNull();
        }

        @Test
        @DisplayName("게스트가 먼저 써도 같다: 호스트가 쓰면 함께 공개")
        void guestFirst() {
            Long appearanceId = confirmed();
            write(GUEST, appearanceId);
            assertThat(publishedAtOf(appearanceId, GUEST)).isNull();

            write(HOST, appearanceId);

            assertThat(publishedAtOf(appearanceId, GUEST)).isNotNull()
                    .isEqualTo(publishedAtOf(appearanceId, HOST));
        }

        @Test
        @DisplayName("다른 출연의 리뷰는 공개에 섞이지 않는다 (같은 호스트, 다른 게스트)")
        void otherAppearanceNotPublished() {
            Long recruitId = appearanceRecruitFacade.create(HOST,
                    AppearanceRecruitFixture.content(clock.now(), FEE, 2)).id();
            Long first = acceptApplication(recruitId, GUEST);
            Long second = acceptApplication(recruitId, GUEST2);
            clock.advanceTo(jdbcTemplate.queryForObject(
                    "select recruit_deadline from appearances.recruits where id = ?", LocalDateTime.class, recruitId));
            appearanceRecruitDeadlineCloseUseCase.closeDue();
            broadcastEnded(first);
            appearanceFacade.confirm(HOST, first);
            appearanceFacade.confirm(HOST, second);

            write(HOST, first);
            write(GUEST2, second);

            assertThat(publishedAtOf(first, HOST)).isNull();
            assertThat(publishedAtOf(second, GUEST2)).isNull();
        }

        @Test
        @DisplayName("자동 확인된 출연에도 쓸 수 있다")
        void autoConfirmed() {
            Long appearanceId = established();
            clock.advanceTo(scheduledAtOf(appearanceId).plusDays(7));
            appearanceAutoConfirmUseCase.confirmDue();

            write(GUEST, appearanceId);

            assertThat(reviewCount(appearanceId)).isEqualTo(1);
        }

        @Test
        @DisplayName("작성 기한이 없으면(운영 설정) 확인 1년 뒤에도 쓸 수 있다")
        void unlimitedByDefault() {
            Long appearanceId = confirmed();
            clock.advance(Duration.ofDays(365));

            write(HOST, appearanceId);

            assertThat(reviewCount(appearanceId)).isEqualTo(1);
        }

        @Test
        @DisplayName("로그인 뒤 일반 유저로 강등된 작성자는 403 NOT_INFLUENCER (출연은 인플루언서끼리만, 로그인 역할 USER는 E2E)")
        void demotedReviewer() {
            Long appearanceId = confirmed();
            changeUser(GUEST, Role.USER, UserStatus.ACTIVE);

            assertErrorCode(() -> write(GUEST, appearanceId), AppearanceErrorCode.NOT_INFLUENCER);
            assertThat(reviewCount(appearanceId)).isZero();
        }

        @Test
        @DisplayName("상대가 일반 유저로 강등돼도 정상 상태면 쓸 수 있다 (받는 쪽은 상태만 본다)")
        void demotedReviewee() {
            Long appearanceId = confirmed();
            changeUser(GUEST, Role.USER, UserStatus.ACTIVE);

            write(HOST, appearanceId);

            assertThat(reviewCount(appearanceId)).isEqualTo(1);
        }

        private Long acceptApplication(Long recruitId, Long guestId) {
            Long applicationId = appearanceApplicationFacade.apply(guestId, recruitId, "지원합니다.").id();
            return appearanceApplicationFacade.accept(HOST, applicationId).appearanceId();
        }
    }

    // ---------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("확인 순서 (앞 단계 오류가 먼저 난다)")
    class CheckOrder {

        @Test
        @DisplayName("1. 없는 출연은 404 APPEARANCE_NOT_FOUND")
        void notFound() {
            assertErrorCode(() -> write(HOST, 999_999L), AppearanceErrorCode.APPEARANCE_NOT_FOUND);
        }

        @Test
        @DisplayName("1. 당사자가 아니면 없는 출연과 같은 404 APPEARANCE_NOT_FOUND (출연이 확인 전이고 작성자가 정지돼도 이 오류가 먼저)")
        void notParticipant() {
            Long confirmedId = confirmed();
            Long establishedId = established();
            changeUser(OTHER, Role.INFLUENCER, UserStatus.SUSPENDED);

            assertErrorCode(() -> write(OTHER, confirmedId), AppearanceErrorCode.APPEARANCE_NOT_FOUND);
            assertErrorCode(() -> write(OTHER, establishedId), AppearanceErrorCode.APPEARANCE_NOT_FOUND);
        }

        @Test
        @DisplayName("2. 작성자가 정지·탈퇴면 403 APPEARANCE_USER_NOT_ACTIVE (출연이 확인 전이어도 이 오류가 먼저)")
        void reviewerNotActive() {
            Long confirmedId = confirmed();
            Long establishedId = established();
            changeUser(GUEST, Role.INFLUENCER, UserStatus.SUSPENDED);
            changeUser(HOST, Role.INFLUENCER, UserStatus.WITHDRAWN);

            assertErrorCode(() -> write(GUEST, confirmedId), AppearanceErrorCode.APPEARANCE_USER_NOT_ACTIVE);
            assertErrorCode(() -> write(GUEST, establishedId), AppearanceErrorCode.APPEARANCE_USER_NOT_ACTIVE);
            assertErrorCode(() -> write(HOST, confirmedId), AppearanceErrorCode.APPEARANCE_USER_NOT_ACTIVE);
        }

        @Test
        @DisplayName("3. 성립 상태(확인 전)면 409 APPEARANCE_NOT_REVIEWABLE (상대가 정지돼도 이 오류가 먼저)")
        void establishedOnly() {
            Long appearanceId = established();
            changeUser(GUEST, Role.INFLUENCER, UserStatus.SUSPENDED);

            assertErrorCode(() -> write(HOST, appearanceId), AppearanceErrorCode.APPEARANCE_NOT_REVIEWABLE);
        }

        @Test
        @DisplayName("3. 불참 처리된 출연은 409 APPEARANCE_NOT_REVIEWABLE")
        void absent() {
            Long appearanceId = established();
            broadcastEnded(appearanceId);
            appearanceFacade.markAbsent(HOST, appearanceId, null);

            assertErrorCode(() -> write(HOST, appearanceId), AppearanceErrorCode.APPEARANCE_NOT_REVIEWABLE);
            assertErrorCode(() -> write(GUEST, appearanceId), AppearanceErrorCode.APPEARANCE_NOT_REVIEWABLE);
        }

        @Test
        @DisplayName("3. 호스트가 일방 취소한 출연·합의 취소한 출연은 409 APPEARANCE_NOT_REVIEWABLE")
        void canceled() {
            Long byHost = established();
            appearanceFacade.hostCancel(HOST, byHost, false, null);
            Long byAgreement = established();
            appearanceFacade.requestCancel(GUEST, byAgreement, "일정이 생겼습니다.");
            appearanceFacade.acceptCancel(HOST, byAgreement);

            assertErrorCode(() -> write(GUEST, byHost), AppearanceErrorCode.APPEARANCE_NOT_REVIEWABLE);
            assertErrorCode(() -> write(GUEST, byAgreement), AppearanceErrorCode.APPEARANCE_NOT_REVIEWABLE);
        }

        @Test
        @DisplayName("5. 상대가 정지·탈퇴면 409 REVIEWEE_NOT_ACTIVE (이미 썼어도 이 오류가 먼저)")
        void revieweeNotActive() {
            Long appearanceId = confirmed();
            Long other = confirmed();
            write(HOST, appearanceId);
            changeUser(GUEST, Role.INFLUENCER, UserStatus.SUSPENDED);

            assertErrorCode(() -> write(HOST, appearanceId), AppearanceErrorCode.REVIEWEE_NOT_ACTIVE);

            changeUser(GUEST, Role.INFLUENCER, UserStatus.WITHDRAWN);
            assertErrorCode(() -> write(HOST, other), AppearanceErrorCode.REVIEWEE_NOT_ACTIVE);
        }

        @Test
        @DisplayName("6. 이미 썼으면 409 ALREADY_REVIEWED, 리뷰는 1건 그대로")
        void alreadyReviewed() {
            Long appearanceId = confirmed();
            write(GUEST, appearanceId);

            assertErrorCode(() -> write(GUEST, appearanceId), AppearanceErrorCode.ALREADY_REVIEWED);
            assertThat(reviewCount(appearanceId)).isEqualTo(1);
        }

        @Test
        @DisplayName("별점·본문 형식이 틀리면 400 INVALID_REQUEST, 저장하지 않는다")
        void invalidContent() {
            Long appearanceId = confirmed();

            assertErrorCode(() -> appearanceReviewFacade.write(HOST, appearanceId, 6, null),
                    AppearanceErrorCode.INVALID_REQUEST);
            assertErrorCode(() -> appearanceReviewFacade.write(HOST, appearanceId, null, null),
                    AppearanceErrorCode.INVALID_REQUEST);
            assertErrorCode(() -> appearanceReviewFacade.write(HOST, appearanceId, 5, "가".repeat(1001)),
                    AppearanceErrorCode.INVALID_REQUEST);
            assertThat(reviewCount(appearanceId)).isZero();
        }
    }

    // ---------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("작성 기한 14일 (기한을 켠 경우)")
    class WithWritePeriod {

        /** 기한 14일 정책으로 만든 UseCase를 한 트랜잭션에서 부른다 (Facade와 같은 경계) */
        private Long writeWithPeriod(Long reviewerId, Long appearanceId) {
            AppearanceReviewEligibility eligibility = new AppearanceReviewEligibility(appearanceReviewSupport,
                    appearanceUserSupport, new AppearanceReviewPolicy(WRITE_PERIOD));
            AppearanceReviewWriteUseCase useCase = new AppearanceReviewWriteUseCase(appearanceSupport,
                    appearanceReviewSupport, eligibility, clock);
            return new TransactionTemplate(transactionManager)
                    .execute(status -> useCase.write(reviewerId, appearanceId, 5, null));
        }

        @Test
        @DisplayName("확인 시각 + 14일 직전까지는 쓸 수 있다")
        void beforeDeadline() {
            Long appearanceId = confirmed();
            clock.advanceTo(confirmedAtOf(appearanceId).plus(WRITE_PERIOD).minusSeconds(1));

            assertThat(writeWithPeriod(HOST, appearanceId)).isNotNull();
        }

        @Test
        @DisplayName("4. 확인 시각 + 14일부터 409 REVIEW_PERIOD_ENDED (상대가 정지돼도 이 오류가 먼저)")
        void afterDeadline() {
            Long appearanceId = confirmed();
            clock.advanceTo(confirmedAtOf(appearanceId).plus(WRITE_PERIOD));
            changeUser(GUEST, Role.INFLUENCER, UserStatus.SUSPENDED);

            assertErrorCode(() -> writeWithPeriod(HOST, appearanceId), AppearanceErrorCode.REVIEW_PERIOD_ENDED);
            assertThat(reviewCount(appearanceId)).isZero();
        }

        @Test
        @DisplayName("기한 안에 두 사람이 모두 쓰면 함께 공개된다")
        void bothWithinPeriod() {
            Long appearanceId = confirmed();
            writeWithPeriod(HOST, appearanceId);
            clock.advanceTo(confirmedAtOf(appearanceId).plusDays(13));

            writeWithPeriod(GUEST, appearanceId);

            assertThat(publishedAtOf(appearanceId, HOST)).isNotNull().isEqualTo(publishedAtOf(appearanceId, GUEST));
        }
    }

    // ---------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("동시 작성 (출연 행 잠금)")
    class Concurrent {

        @Test
        @DisplayName("호스트·게스트가 동시에 쓰면 둘 다 저장되고 둘 다 공개된다")
        void bothAtOnce() throws Exception {
            for (int round = 0; round < 5; round++) {
                Long appearanceId = confirmed();

                List<Object> results = runConcurrently(List.of(
                        () -> attempt(HOST, appearanceId),
                        () -> attempt(GUEST, appearanceId)));

                assertThat(results).allMatch(result -> result instanceof Long);
                assertThat(reviewCount(appearanceId)).isEqualTo(2);
                assertThat(publishedAtOf(appearanceId, HOST)).isNotNull()
                        .isEqualTo(publishedAtOf(appearanceId, GUEST));
            }
        }

        @Test
        @DisplayName("같은 사람이 동시에 두 번 쓰면 1건만 저장되고 나머지는 409 ALREADY_REVIEWED")
        void sameReviewerTwice() throws Exception {
            for (int round = 0; round < 5; round++) {
                Long appearanceId = confirmed();

                List<Object> results = runConcurrently(List.of(
                        () -> attempt(GUEST, appearanceId),
                        () -> attempt(GUEST, appearanceId),
                        () -> attempt(GUEST, appearanceId)));

                assertThat(results).filteredOn(result -> result instanceof Long).hasSize(1);
                assertThat(results).filteredOn(result -> result == AppearanceErrorCode.ALREADY_REVIEWED).hasSize(2);
                assertThat(reviewCount(appearanceId)).isEqualTo(1);
                assertThat(publishedAtOf(appearanceId, GUEST)).isNull();
            }
        }

        /** 성공하면 리뷰 ID, 업무 오류면 오류 코드 (그 밖의 예외는 테스트 실패) */
        private Object attempt(Long reviewerId, Long appearanceId) {
            try {
                return write(reviewerId, appearanceId);
            } catch (AppearanceException exception) {
                return exception.getErrorCode();
            }
        }

        private List<Object> runConcurrently(List<Callable<Object>> tasks) throws Exception {
            ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
            CountDownLatch start = new CountDownLatch(1);
            try {
                List<Future<Object>> futures = new ArrayList<>();
                for (Callable<Object> task : tasks) {
                    futures.add(executor.submit(asInfluencer(start, task)));
                }
                start.countDown();
                List<Object> results = new ArrayList<>();
                for (Future<Object> future : futures) {
                    results.add(future.get());
                }
                return results;
            } finally {
                executor.shutdown();
            }
        }

        private Callable<Object> asInfluencer(CountDownLatch start, Callable<Object> task) {
            return () -> {
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("test",
                        null, List.of(new SimpleGrantedAuthority("ROLE_INFLUENCER"))));
                try {
                    start.await();
                    return task.call();
                } finally {
                    SecurityContextHolder.clearContext();
                }
            };
        }
    }
}
