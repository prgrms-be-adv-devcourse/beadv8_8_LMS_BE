package com.hapbang.auxiliary.chatbot.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.hapbang.auxiliary.chatbot.domain.ApplySource;
import com.hapbang.auxiliary.chatbot.domain.InfluencerPopularity.Stats;
import com.hapbang.auxiliary.chatbot.domain.InfluencerProfile;
import com.hapbang.auxiliary.chatbot.out.InfluencerRecommendStore;
import com.hapbang.auxiliary.chatbot.support.CopyRows;
import com.hapbang.shared.appearance.domain.AppearanceResult;
import com.hapbang.shared.appearance.dto.AppearanceSyncDto;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;

/** 인기 재료 집계 SQL (#719): 완료만 세고 원본 없음은 뺀다 */
class InfluencerRecommendStoreTest extends InfluencerIntegrationTestBase {

    @Autowired
    private InfluencerRecommendStore store;

    @Test
    @DisplayName("완료 출연만 센다(예정·취소·노쇼 제외), 호스트·게스트 모두, 원본 없음과 자기 자신과의 출연은 빼고")
    void completedCounts() {
        influencer(1L, "GAME", "롤");
        completed(100L, 1L, 2L);
        completed(101L, 3L, 1L);
        appearance(102L, 1L, 2L, AppearanceResult.SCHEDULED);
        appearance(103L, 1L, 2L, AppearanceResult.CANCELED);
        appearance(104L, 1L, 2L, AppearanceResult.NO_SHOW);
        completed(105L, 1L, 4L);
        completed(106L, 1L, 1L);
        appearance(107L, 5L, 1L, AppearanceResult.SCHEDULED);
        jdbcTemplate.update("UPDATE chatbot.appearance_copy SET missing_in_source = TRUE WHERE id = 105");

        assertThat(store.popularityStats(List.of(1L))).containsExactly(new Stats(1L, 2, 0, 0));
        assertThat(store.completedPartners(1L)).containsExactlyInAnyOrder(2L, 3L);
    }

    @Test
    @DisplayName("받은 리뷰: 원본 없음 리뷰와 원본 없음 출연의 리뷰는 빼고, 출연 사본이 아직 없는 리뷰는 센다. 전체 평균도 같은 기준")
    void reviews() {
        influencer(1L, "GAME", "롤");
        completed(100L, 2L, 1L);
        completed(101L, 3L, 1L);
        review(1L, 100L, 1L, 5);
        review(2L, 101L, 1L, 1);
        review(3L, 999L, 1L, 3);
        review(4L, 100L, 1L, 1);
        jdbcTemplate.update("UPDATE chatbot.appearance_copy SET missing_in_source = TRUE WHERE id = 101");
        jdbcTemplate.update("UPDATE chatbot.appearance_review_copy SET missing_in_source = TRUE WHERE review_id = 4");

        assertThat(store.popularityStats(List.of(1L))).containsExactly(new Stats(1L, 1, 2, 8));
        assertThat(store.overallMeanRating()).isCloseTo(4.0, within(1e-9));
    }

    @Test
    @DisplayName("추천 가능한 전원의 인기 재료: 정지·일반 회원·마스킹은 빠지고, 출연이 없는 회원은 0")
    void statsOfRecommendable() {
        influencer(1L, "GAME", "롤");
        influencer(2L, "MUSIC", "노래");
        member(3L, Role.INFLUENCER, UserStatus.SUSPENDED, List.of("GAME"), "롤");
        member(4L, Role.USER, UserStatus.ACTIVE, List.of(), null);
        completed(100L, 1L, 3L);

        assertThat(store.popularityStatsOfRecommendable())
                .containsExactly(new Stats(1L, 1, 0, 0), new Stats(2L, 0, 0, 0));
    }

    @Test
    @DisplayName("프로필: 분야 순서 그대로, 마스킹 표시, 사본 없는 ID는 빠진다. 추천 가능 프로필은 인플루언서·인증 인플루언서이고 "
            + "정상·마스킹 안 됨만. profilesAfter는 ID 다음부터 오름차순 size개 (#1003)")
    void profiles() {
        member(1L, Role.INFLUENCER, UserStatus.ACTIVE, List.of("MUSIC", "GAME", "COOKING"), "소개");
        member(2L, Role.VERIFIED_INFLUENCER, UserStatus.ACTIVE, List.of(), null);
        member(3L, Role.INFLUENCER, UserStatus.SUSPENDED, List.of("GAME"), "롤");
        member(4L, Role.USER, UserStatus.ACTIVE, List.of(), null);
        influencer(5L, "GAME", "롤");
        rows.masked(5L);

        Map<Long, InfluencerProfile> profiles = store.profiles(List.of(1L, 3L, 5L, 99L));

        assertThat(profiles).containsOnlyKeys(1L, 3L, 5L);
        assertThat(profiles.get(1L)).isEqualTo(new InfluencerProfile(1L, Role.INFLUENCER, UserStatus.ACTIVE,
                List.of("MUSIC", "GAME", "COOKING"), "소개", false));
        assertThat(profiles.get(3L).status()).isEqualTo(UserStatus.SUSPENDED);
        assertThat(profiles.get(5L).masked()).isTrue();
        assertThat(store.profiles(List.of())).isEmpty();
        assertThat(store.recommendableProfiles(List.of(1L, 2L, 3L, 4L, 5L, 99L))).containsOnlyKeys(1L, 2L);
        assertThat(store.recommendableProfiles(List.of())).isEmpty();
        assertThat(store.profilesAfter(1L, 2)).extracting(InfluencerProfile::id).containsExactly(2L, 3L);
    }

    @Test
    @DisplayName("주어진 회원의 인기 재료는 주어진 순서로, 사본이 없거나 출연이 없으면 0 (#1003)")
    void statsInGivenOrder() {
        influencer(1L, "GAME", "롤");
        influencer(2L, "MUSIC", "노래");
        completed(100L, 1L, 2L);
        completed(101L, 2L, 3L);
        review(1L, 101L, 2L, 4);

        assertThat(store.popularityStats(List.of(2L, 99L, 1L)))
                .containsExactly(new Stats(2L, 2, 1, 4), new Stats(99L, 0, 0, 0), new Stats(1L, 1, 0, 0));
        assertThat(store.popularityStats(List.of())).isEmpty();
    }

    private void appearance(long id, long hostId, long guestId, AppearanceResult result) {
        copyApplier.apply(new AppearanceSyncDto(id, hostId, guestId, result, CopyRows.SCHEDULED_AT, 0L), ApplySource.EVENT);
    }
}
