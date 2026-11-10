package com.hapbang.auxiliary.chatbot.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.hapbang.auxiliary.chatbot.domain.IndexType;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.event.UserMaskedEvent;

/**
 * 인플루언서 색인 처리기 (#719). 처리기는 요청 종류가 아니라 지금 사본 상태로 맞춘다.
 * 숨김·복구는 벡터를 다시 만들지 않는다(가짜 임베딩의 호출 수로 확인).
 */
class InfluencerIndexTaskHandlerTest extends InfluencerIntegrationTestBase {

    private static final long INFLUENCER = 11L;

    @Autowired
    private MemberMasking memberMasking;

    @Test
    @DisplayName("추천 가능한 인플루언서: 분야 이름 + 정리한 소개를 임베딩해 hidden=false로 넣는다. 공급자에는 이메일·전화번호가 가지 않는다")
    void indexesInfluencer() {
        influencer(INFLUENCER, "GAME", "롤 방송합니다. 연락 hello@example.com 010-1234-5678");

        assertThat(index(INFLUENCER)).isEmpty();

        assertThat(document(INFLUENCER).hidden()).isFalse();
        assertThat(index.vectors(IndexType.INFLUENCER, List.of("11"))).containsKey("11");
        assertThat(embeddingModel.texts()).containsExactly("분야: 게임\n소개: 롤 방송합니다. 연락 [이메일] [전화번호]");
    }

    @Test
    @DisplayName("정지·강등은 hidden=true, 복구는 hidden=false로만 바꾼다(다시 임베딩하지 않는다). 탈퇴도 숨긴다")
    void hideAndShowWithoutReembedding() {
        influencer(INFLUENCER, "GAME", "롤");
        index(INFLUENCER);
        int embedded = embeddingModel.embeddedTexts();

        member(INFLUENCER, Role.INFLUENCER, UserStatus.SUSPENDED, List.of("GAME"), "롤");
        index(INFLUENCER);
        assertThat(document(INFLUENCER).hidden()).isTrue();

        member(INFLUENCER, Role.INFLUENCER, UserStatus.ACTIVE, List.of("GAME"), "롤");
        index(INFLUENCER);
        assertThat(document(INFLUENCER).hidden()).isFalse();

        member(INFLUENCER, Role.USER, UserStatus.ACTIVE, List.of("GAME"), "롤");
        index(INFLUENCER);
        assertThat(document(INFLUENCER).hidden()).isTrue();

        member(INFLUENCER, Role.VERIFIED_INFLUENCER, UserStatus.WITHDRAWN, List.of("GAME"), "롤");
        index(INFLUENCER);
        assertThat(document(INFLUENCER).hidden()).isTrue();

        assertThat(embeddingModel.embeddedTexts()).isEqualTo(embedded);
        assertThat(index.vectors(IndexType.INFLUENCER, List.of("11"))).containsKey("11");
    }

    @Test
    @DisplayName("글이 같으면 다시 임베딩하지 않고(닉네임만 바뀐 회원 변경 등), 소개가 바뀌면 다시 임베딩한다")
    void reembedOnlyWhenTextChanges() {
        influencer(INFLUENCER, "GAME", "롤");
        index(INFLUENCER);
        int embedded = embeddingModel.embeddedTexts();

        index(INFLUENCER);
        assertThat(embeddingModel.embeddedTexts()).isEqualTo(embedded);

        influencer(INFLUENCER, "GAME", "발로란트");
        index(INFLUENCER);
        assertThat(embeddingModel.embeddedTexts()).isEqualTo(embedded + 1);
    }

    @Test
    @DisplayName("마스킹하면 문서를 지운다(마스킹이 적은 DELETE 요청이든 UPSERT 요청이든 지금 사본 상태로 판단)")
    void maskedIsDeleted() {
        influencer(INFLUENCER, "GAME", "롤");
        index(INFLUENCER);

        memberMasking.mask(new UserMaskedEvent(INFLUENCER, Instant.parse("2026-11-10T01:00:00Z")));
        index(INFLUENCER);

        assertThat(document(INFLUENCER)).isNull();
    }

    @Test
    @DisplayName("분야·소개가 모두 없으면 문서를 만들지 않고, 있던 문서는 지운다. 일반 회원은 문서를 만들지 않는다. 사본이 없으면 그대로")
    void emptyAndNonInfluencer() {
        member(INFLUENCER, Role.INFLUENCER, UserStatus.ACTIVE, List.of(), null);
        member(12L, Role.USER, UserStatus.ACTIVE, List.of("GAME"), "롤");

        assertThat(index(INFLUENCER, 12L, 999L)).isEmpty();
        assertThat(document(INFLUENCER)).isNull();
        assertThat(document(12L)).isNull();
        assertThat(embeddingModel.embeddedTexts()).isZero();

        influencer(INFLUENCER, "GAME", "롤");
        index(INFLUENCER);
        member(INFLUENCER, Role.INFLUENCER, UserStatus.ACTIVE, List.of(), " ");
        index(INFLUENCER);
        assertThat(document(INFLUENCER)).isNull();
    }

    @Test
    @DisplayName("임베딩이 실패하면 임베딩할 문서만 실패로 돌려주고, 숨김만 바꾸는 문서는 처리한다")
    void partialFailure() {
        influencer(INFLUENCER, "GAME", "롤");
        influencer(12L, "MUSIC", "노래");
        index(INFLUENCER);
        member(INFLUENCER, Role.INFLUENCER, UserStatus.SUSPENDED, List.of("GAME"), "롤");
        embeddingModel.failWith(new IllegalStateException("임베딩 장애"));

        Map<String, RuntimeException> failures = index(INFLUENCER, 12L);

        assertThat(failures).containsOnlyKeys("12");
        assertThat(document(INFLUENCER).hidden()).isTrue();
        assertThat(document(12L)).isNull();
    }
}
