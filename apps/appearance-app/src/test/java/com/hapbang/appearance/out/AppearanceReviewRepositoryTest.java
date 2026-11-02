package com.hapbang.appearance.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.hapbang.appearance.domain.Appearance;
import com.hapbang.appearance.domain.AppearancePolicy;
import com.hapbang.appearance.domain.AppearanceRequest;
import com.hapbang.appearance.domain.AppearanceReview;
import com.hapbang.appearance.domain.AppearanceRole;
import com.hapbang.appearance.fixture.AppearanceRequestFixture;
import com.hapbang.testsupport.RepositoryTest;

/**
 * 출연 리뷰 테이블 {@code appearances.reviews}의 저장·조회와 DB 제약 (#512).
 */
@RepositoryTest
class AppearanceReviewRepositoryTest {

    private static final Long HOST = 1L;
    private static final Long GUEST = 2L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 14, 12, 0);
    private static final AppearancePolicy POLICY = new AppearancePolicy(20_000, 30, Duration.ofDays(7), 2, 10);

    @Autowired
    AppearanceReviewRepository appearanceReviewRepository;

    @Autowired
    AppearanceRepository appearanceRepository;

    @Autowired
    AppearanceRequestRepository appearanceRequestRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    /** 호스트 1 → 게스트 2 요청서가 수락돼 성립하고, 방송 뒤 호스트가 확인한 출연 */
    private Appearance confirmed() {
        AppearanceRequest request = AppearanceRequest.create(HOST, GUEST, AppearanceRequestFixture.content(NOW),
                POLICY, NOW);
        request.markRequested();
        appearanceRequestRepository.saveAndFlush(request);
        request.accept(GUEST, request.getVersion(), NOW.plusHours(1));
        appearanceRequestRepository.saveAndFlush(request);
        Appearance appearance = Appearance.establishFrom(request, NOW.plusHours(1));
        appearance.confirmByHost(HOST, POLICY, appearance.getScheduledAt().plusHours(1));
        return appearanceRepository.saveAndFlush(appearance);
    }

    /** 제약을 직접 확인하려고 엔티티 규칙을 거치지 않고 넣는다 */
    private void insert(Long appearanceId, Long reviewerId, Long revieweeId, int rating, String content) {
        jdbcTemplate.update("""
                insert into appearances.reviews
                    (appearance_id, reviewer_id, reviewee_id, reviewee_role, rating, content, version, created_at, updated_at)
                values (?, ?, ?, 'GUEST', ?, ?, 0, now(), now())
                """, appearanceId, reviewerId, revieweeId, rating, content);
    }

    @Test
    @DisplayName("appearances.reviews에 저장하고, 출연·작성자로 찾고 있는지 확인한다 (공개 전 published_at null)")
    void saveAndFind() {
        Appearance appearance = confirmed();
        AppearanceReview saved = appearanceReviewRepository.saveAndFlush(
                AppearanceReview.write(appearance, HOST, 5, "좋았습니다."));

        AppearanceReview found = appearanceReviewRepository.findByAppearanceIdAndReviewerId(appearance.getId(), HOST)
                .orElseThrow();
        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getRevieweeId()).isEqualTo(GUEST);
        assertThat(found.getRevieweeRole()).isEqualTo(AppearanceRole.GUEST);
        assertThat(found.getRating()).isEqualTo(5);
        assertThat(found.getContent()).isEqualTo("좋았습니다.");
        assertThat(found.getPublishedAt()).isNull();
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getVersion()).isNotNull();
        assertThat(appearanceReviewRepository.existsByAppearanceIdAndReviewerId(appearance.getId(), HOST)).isTrue();
        assertThat(appearanceReviewRepository.existsByAppearanceIdAndReviewerId(appearance.getId(), GUEST)).isFalse();
        assertThat(appearanceReviewRepository.findByAppearanceIdAndReviewerId(appearance.getId(), GUEST)).isEmpty();
    }

    @Test
    @DisplayName("출연 하나에 작성자 1명당 한 번만 쓴다 (uk_appearance_reviews_appearance_reviewer)")
    void uniqueAppearanceReviewer() {
        Appearance appearance = confirmed();
        appearanceReviewRepository.saveAndFlush(AppearanceReview.write(appearance, HOST, 5, null));

        assertThatThrownBy(() -> appearanceReviewRepository.saveAndFlush(
                AppearanceReview.write(appearance, HOST, 3, "두 번째")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("uk_appearance_reviews_appearance_reviewer");
    }

    @Test
    @DisplayName("같은 출연에 호스트·게스트가 각각 쓸 수 있다 (출연당 최대 2건)")
    void bothDirections() {
        Appearance appearance = confirmed();
        appearanceReviewRepository.saveAndFlush(AppearanceReview.write(appearance, HOST, 5, null));
        appearanceReviewRepository.saveAndFlush(AppearanceReview.write(appearance, GUEST, 4, null));

        assertThat(appearanceReviewRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("별점 0은 DB가 막는다 (ck_appearance_reviews_rating)")
    void ratingBelowRange() {
        Long appearanceId = confirmed().getId();

        assertThatThrownBy(() -> insert(appearanceId, HOST, GUEST, 0, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("ck_appearance_reviews_rating");
    }

    @Test
    @DisplayName("별점 6은 DB가 막는다 (ck_appearance_reviews_rating)")
    void ratingAboveRange() {
        Long appearanceId = confirmed().getId();

        assertThatThrownBy(() -> insert(appearanceId, HOST, GUEST, 6, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("ck_appearance_reviews_rating");
    }

    @Test
    @DisplayName("자기 자신에게 쓴 리뷰는 DB가 막는다 (ck_appearance_reviews_reviewer_reviewee)")
    void reviewerIsNotReviewee() {
        Long appearanceId = confirmed().getId();

        assertThatThrownBy(() -> insert(appearanceId, HOST, HOST, 5, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("ck_appearance_reviews_reviewer_reviewee");
    }

    @Test
    @DisplayName("본문은 1,000자까지 들어가고 1,001자는 DB가 막는다")
    void contentLength() {
        Long appearanceId = confirmed().getId();
        insert(appearanceId, HOST, GUEST, 5, "가".repeat(1000));

        assertThatThrownBy(() -> insert(appearanceId, GUEST, HOST, 5, "가".repeat(1001)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("없는 출연의 리뷰는 DB가 막는다 (fk_appearance_reviews_appearance)")
    void appearanceMustExist() {
        assertThatThrownBy(() -> insert(999_999L, HOST, GUEST, 5, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("fk_appearance_reviews_appearance");
    }
}
