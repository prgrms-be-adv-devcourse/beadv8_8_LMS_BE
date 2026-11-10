package com.hapbang.auxiliary.chatbot.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.hapbang.auxiliary.chatbot.app.InfluencerRecommendUseCase.Candidate;
import com.hapbang.auxiliary.chatbot.app.InfluencerRecommendUseCase.InfluencerRecommendation;
import com.hapbang.auxiliary.chatbot.domain.InfluencerReason;
import com.hapbang.auxiliary.chatbot.support.FakeEmbeddingModel;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;

/**
 * 인플루언서 추천 후보 (#719). 가짜 임베딩으로 유사도를 정하고 실제 ES kNN과 사본 집계를 거친다.
 * <p>
 * 회원: 1 요청 인플루언서(게임, 축 0), 2 게임 비슷(축 0과 0.9), 3 음악(축 1), 4 요리(축 2), 5 정지된 게임(문서는 숨김).
 * 기본 설정: 5명, β 0.2, 상대 가중 0.5.
 */
class InfluencerRecommendUseCaseTest extends InfluencerIntegrationTestBase {

    @Autowired
    private InfluencerRecommendUseCase useCase;

    @BeforeEach
    void setUpInfluencers() {
        embeddingModel.put("분야: 게임\n소개: 발로란트", FakeEmbeddingModel.withCosine(0, 3, 0.9));
        influencer(1L, "GAME", "롤");
        influencer(2L, "GAME", "발로란트");
        influencer(3L, "MUSIC", "노래");
        influencer(4L, "COOKING", "요리");
        influencer(5L, "GAME", "롤");
        index(1L, 2L, 3L, 4L, 5L);
        member(5L, Role.INFLUENCER, UserStatus.SUSPENDED, List.of("GAME"), "롤");
        index(5L);
    }

    @Test
    @DisplayName("내 프로필이 있는 인플루언서: 유사도 순, 본인·숨김(정지) 제외, 동점은 회원 ID 내림차순, 모자라면 인기순으로 5명을 채운다")
    void ownProfile() {
        // 프로필이 비어 문서가 없는 인플루언서 둘: 인기순 채우기로만 들어온다
        member(7L, Role.INFLUENCER, UserStatus.ACTIVE, List.of(), null);
        member(8L, Role.INFLUENCER, UserStatus.ACTIVE, List.of(), null);

        InfluencerRecommendation result = useCase.recommend(1L, null);

        assertThat(result.needsInterest()).isFalse();
        assertThat(result.items()).extracting(Candidate::memberId).containsExactly(2L, 4L, 3L, 8L, 7L);
        assertThat(result.items().get(0).reasons()).containsExactly(InfluencerReason.SIMILAR_CATEGORY);
        assertThat(result.items().get(3).reasons()).containsExactly(InfluencerReason.POPULAR);
        assertThat(result.items().get(0).categories()).containsExactly("GAME");
    }

    @Test
    @DisplayName("프로필이 빈 인플루언서: 함께 마친 상대들 평균으로 찾는다. 숨김(정지) 상대의 벡터는 빼고, 이미 함께한 상대는 빼지 않는다(PAST_PARTNER)")
    void partnersOnly() {
        member(9L, Role.INFLUENCER, UserStatus.ACTIVE, List.of(), null);
        completed(100L, 9L, 3L);
        // 정지된 상대 5를 요리 벡터로 바꿔 둔다: 평균에 들어가면 요리(4)의 유사도가 0보다 커진다
        member(5L, Role.INFLUENCER, UserStatus.ACTIVE, List.of("COOKING"), "요리");
        index(5L);
        member(5L, Role.INFLUENCER, UserStatus.SUSPENDED, List.of("COOKING"), "요리");
        index(5L);
        completed(101L, 5L, 9L);

        InfluencerRecommendation result = useCase.recommend(9L, null);

        Candidate first = result.items().getFirst();
        assertThat(first.memberId()).isEqualTo(3L);
        assertThat(first.reasons()).containsExactlyInAnyOrder(InfluencerReason.SIMILAR_TO_PARTNERS,
                InfluencerReason.PAST_PARTNER);
        Candidate cooking = result.items().stream().filter(item -> item.memberId() == 4L).findFirst().orElseThrow();
        assertThat(cooking.score()).isZero();
        assertThat(result.items()).extracting(Candidate::memberId).doesNotContain(5L, 9L);
    }

    @Test
    @DisplayName("내 프로필과 상대가 모두 있으면 섞는다. 이유는 후보마다 더 가까운 쪽(내 분야 또는 함께한 상대들)")
    void ownProfileAndPartners() {
        completed(100L, 1L, 3L);

        InfluencerRecommendation result = useCase.recommend(1L, null);

        Candidate music = result.items().stream().filter(item -> item.memberId() == 3L).findFirst().orElseThrow();
        Candidate game = result.items().stream().filter(item -> item.memberId() == 2L).findFirst().orElseThrow();
        assertThat(music.reasons()).containsExactlyInAnyOrder(InfluencerReason.SIMILAR_TO_PARTNERS,
                InfluencerReason.PAST_PARTNER);
        assertThat(game.reasons()).containsExactly(InfluencerReason.SIMILAR_CATEGORY);
        // 취향 = (게임 + 음악)/2. 유사도 음악 0.71·게임 비슷 0.64·요리 0, 함께 마친 출연이 있는 음악(3)은 인기도 1이라 맨 앞
        assertThat(result.items()).extracting(Candidate::memberId).startsWith(3L, 2L, 4L);
    }

    @Test
    @DisplayName("일반 회원: 인기순만, needsInterest. 완료 출연 수로 정렬하고 원본 없음 출연은 세지 않는다")
    void generalUserGetsPopular() {
        member(9L, Role.USER, UserStatus.ACTIVE, List.of(), null);
        completed(200L, 4L, 9L);
        completed(201L, 4L, 9L + 100);
        completed(202L, 3L, 9L);
        completed(203L, 2L, 9L);
        completed(204L, 2L, 9L + 100);
        completed(205L, 2L, 9L + 200);
        jdbcTemplate.update("UPDATE chatbot.appearance_copy SET missing_in_source = TRUE WHERE id IN (203, 204, 205)");

        InfluencerRecommendation result = useCase.recommend(9L, null);

        assertThat(result.needsInterest()).isTrue();
        assertThat(result.items()).extracting(Candidate::memberId).containsExactly(4L, 3L, 2L, 1L);
        assertThat(result.items()).allSatisfy(item -> assertThat(item.reasons()).contains(InfluencerReason.POPULAR));
        assertThat(result.items().getFirst().completedAppearances()).isEqualTo(2);
    }

    @Test
    @DisplayName("관심사 문장(자유 질문 Tool)이 있으면 그 문장으로 찾는다")
    void interest() {
        embeddingModel.put(FakeEmbeddingModel.query("요리하는 사람"), FakeEmbeddingModel.axis(2));
        member(9L, Role.USER, UserStatus.ACTIVE, List.of(), null);

        InfluencerRecommendation result = useCase.recommend(9L, "요리하는 사람");

        assertThat(result.needsInterest()).isFalse();
        assertThat(result.items().getFirst().memberId()).isEqualTo(4L);
        assertThat(result.items().getFirst().reasons()).containsExactly(InfluencerReason.MATCHES_INTEREST);
    }

    @Test
    @DisplayName("색인이 늦어 DB로는 정지인데 문서가 아직 보이는 회원은 빠진다")
    void staleIndexIsFilteredByCopy() {
        member(2L, Role.INFLUENCER, UserStatus.SUSPENDED, List.of("GAME"), "발로란트");

        assertThat(useCase.recommend(1L, null).items()).extracting(Candidate::memberId).doesNotContain(2L);
    }

    @Test
    @DisplayName("요청 회원이 탈퇴(숨김)이면 빈 결과")
    void withdrawnRequester() {
        member(1L, Role.INFLUENCER, UserStatus.WITHDRAWN, List.of("GAME"), "롤");

        InfluencerRecommendation result = useCase.recommend(1L, null);

        assertThat(result.items()).isEmpty();
        assertThat(result.needsInterest()).isFalse();
    }

    @Test
    @DisplayName("임베딩이 실패하면 인기순으로 답한다(needsInterest는 false: 입력이 없어서가 아니다)")
    void embeddingFailureFallsBackToPopular() {
        member(9L, Role.USER, UserStatus.ACTIVE, List.of(), null);
        embeddingModel.failWith(new IllegalStateException("임베딩 장애"));

        InfluencerRecommendation result = useCase.recommend(9L, "요리하는 사람");

        assertThat(result.needsInterest()).isFalse();
        assertThat(result.items()).hasSize(4).allSatisfy(item -> assertThat(item.reasons()).contains(InfluencerReason.POPULAR));
    }
}
