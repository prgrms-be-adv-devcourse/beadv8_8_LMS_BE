package com.hapbang.auxiliary.chatbot.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.hapbang.auxiliary.chatbot.config.ChatbotInfluencerRecommendProperties;
import com.hapbang.auxiliary.chatbot.domain.IndexAction;
import com.hapbang.auxiliary.chatbot.domain.IndexType;
import com.hapbang.auxiliary.chatbot.out.InfluencerRecommendStore;
import com.hapbang.auxiliary.chatbot.support.ElasticsearchTestcontainersConfig;
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

    @Autowired
    private ChatbotEmbedder embedder;

    @Autowired
    private InfluencerRecommendStore store;

    @Autowired
    private ChatbotInfluencerRecommendProperties properties;

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
    @DisplayName("정지 처리 바로 뒤에 복구가 와도 숨김을 푼다(처리기가 쓴 묶음 끝에 refresh해 다음 묶음이 숨김 상태를 본다)")
    void restoreRightAfterSuspend() throws Exception {
        influencer(INFLUENCER, "GAME", "롤");
        index(INFLUENCER);
        // 자동 refresh(1초)를 끈다: 시간과 관계없이 처리기 자신의 refresh가 있어야만 숨김 상태가 보인다
        disableAutoRefresh();

        member(INFLUENCER, Role.INFLUENCER, UserStatus.SUSPENDED, List.of("GAME"), "롤");
        handler.handle(docs(INFLUENCER));
        member(INFLUENCER, Role.INFLUENCER, UserStatus.ACTIVE, List.of("GAME"), "롤");
        handler.handle(docs(INFLUENCER));

        // 확인 읽기도 검색이라 저장된 상태를 보도록 refresh한다(처리기가 숨김 해제를 놓쳤으면 여기서 true가 보인다)
        index.refresh(IndexType.INFLUENCER);
        assertThat(document(INFLUENCER).hidden()).isFalse();
    }

    @Test
    @DisplayName("탈퇴·강등하며 분야·소개가 비어도 문서는 지우지 않고 숨긴다(문서 삭제는 마스킹 때만)")
    void hiddenEvenIfProfileEmptied() {
        influencer(INFLUENCER, "GAME", "롤");
        index(INFLUENCER);

        member(INFLUENCER, Role.INFLUENCER, UserStatus.WITHDRAWN, List.of(), null);
        index(INFLUENCER);

        assertThat(document(INFLUENCER)).isNotNull();
        assertThat(document(INFLUENCER).hidden()).isTrue();
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

    @Test
    @DisplayName("회원 API(분야 이름)가 기동 때부터 죽어 있어도 마스킹 문서는 지우고 정지는 숨긴다. 이름이 필요한 문서(임베딩)만 실패로 돌린다")
    void categoryNamesDown() {
        influencer(INFLUENCER, "GAME", "롤");
        influencer(12L, "MUSIC", "노래");
        index(INFLUENCER, 12L);
        memberMasking.mask(new UserMaskedEvent(INFLUENCER, Instant.parse("2026-11-10T01:00:00Z")));
        member(12L, Role.INFLUENCER, UserStatus.SUSPENDED, List.of("MUSIC"), "노래");
        influencer(13L, "GAME", "새로 온 인플루언서");
        memberSource.failCategoryNames(new IllegalStateException("회원 API 장애"));
        // 표를 한 번도 받지 못한 처리기(기동 직후)
        InfluencerIndexTaskHandler starting = new InfluencerIndexTaskHandler(index, embedder, store,
                new ContentCategoryNames(memberSource, properties, clockBean));

        Map<String, RuntimeException> failures = starting.handle(docs(INFLUENCER, 12L, 13L));
        index.refresh(IndexType.INFLUENCER);

        assertThat(failures).containsOnlyKeys("13");
        assertThat(document(INFLUENCER)).isNull();
        assertThat(document(12L).hidden()).isTrue();
        assertThat(document(13L)).isNull();
    }

    // refresh 없이 처리기만 부른다(처리기 자신의 refresh를 확인한다)
    private static SequencedMap<String, IndexAction> docs(long... ids) {
        SequencedMap<String, IndexAction> docs = new LinkedHashMap<>();
        for (long id : ids) {
            docs.put(String.valueOf(id), IndexAction.UPSERT);
        }
        return docs;
    }

    private static void disableAutoRefresh() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create(ElasticsearchTestcontainersConfig.uri() + "/chatbot-influencers-test-8/_settings"))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"index\":{\"refresh_interval\":\"-1\"}}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
    }
}
