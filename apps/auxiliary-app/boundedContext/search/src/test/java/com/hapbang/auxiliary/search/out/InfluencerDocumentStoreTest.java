package com.hapbang.auxiliary.search.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.hapbang.auxiliary.search.support.SearchElasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;

/**
 * 회원 version 조건부 쓰기와 tombstone(#771). 시험 코드 {@code VersioningTest}의 대안 A(문서 필드 memberVersion + painless)를
 * 옮겼다. 시험의 external versioning은 409로 거절했지만 이 방식은 noop으로 끝난다. nori ES 컨테이너로 돈다.
 */
class InfluencerDocumentStoreTest {

    private static final long USER_ID = 1L;
    private static final Instant NOW = Instant.parse("2026-11-10T00:00:00Z");

    private final ElasticsearchClient client = SearchElasticsearch.client();

    private final InfluencerDocumentStore store = new InfluencerDocumentStore(client);

    @BeforeEach
    void prepareIndex() throws Exception {
        SearchElasticsearch.reset(client);
        SearchElasticsearch.createIndex(client);
    }

    @Nested
    @DisplayName("회원 version 비교")
    class Versions {

        @Test
        @DisplayName("더 큰 version만 반영한다. 순서가 뒤바뀐 v4와 같은 v5 두 번은 noop이다")
        void onlyNewer() throws Exception {
            assertThat(store.show(USER_ID, 5L, profile("페이커팬"))).isEqualTo(WriteOutcome.CREATED);
            assertThat(store.show(USER_ID, 4L, profile("옛 닉네임"))).isEqualTo(WriteOutcome.NOOP);
            assertThat(store.show(USER_ID, 5L, profile("페이커팬"))).isEqualTo(WriteOutcome.NOOP);
            assertThat(store.show(USER_ID, 6L, profile("새 닉네임"))).isEqualTo(WriteOutcome.UPDATED);

            assertThat(source()).containsEntry("nickname", "새 닉네임").containsEntry("memberVersion", 6);
        }

        @Test
        @DisplayName("통계 부분 갱신은 회원 version을 건드리지 않아, 그 뒤 회원 이벤트가 거절되지 않는다(external versioning의 문제)")
        void statsUpdateDoesNotBlockMemberEvent() throws Exception {
            store.show(USER_ID, 5L, profile("페이커팬"));
            client.update(u -> u.index(InfluencerIndexDefinition.ALIAS).id(String.valueOf(USER_ID))
                    .doc(Map.of("followerCount", 500)), Map.class);

            assertThat(store.show(USER_ID, 6L, profile("새 닉네임"))).isEqualTo(WriteOutcome.UPDATED);
            assertThat(source()).containsEntry("nickname", "새 닉네임").containsEntry("followerCount", 500);
        }

        @Test
        @DisplayName("version을 모르는 정보는 문서가 없거나 문서에 version이 없을 때만 반영하고, version이 있는 문서에는 무시한다")
        void nullVersion() throws Exception {
            assertThat(store.show(USER_ID, null, profile("버전 없음"))).isEqualTo(WriteOutcome.CREATED);
            assertThat(store.show(USER_ID, null, profile("버전 없음 2"))).isEqualTo(WriteOutcome.UPDATED);
            assertThat(store.show(USER_ID, 3L, profile("버전 있음"))).isEqualTo(WriteOutcome.UPDATED);
            assertThat(store.show(USER_ID, null, profile("옛 이벤트"))).isEqualTo(WriteOutcome.NOOP);

            assertThat(source()).containsEntry("nickname", "버전 있음");
        }

        @Test
        @DisplayName("새 문서는 팔로워·리뷰 수 0으로 시작하고 숨기지 않는다")
        void newDocumentDefaults() throws Exception {
            store.show(USER_ID, 1L, profile("페이커팬"));

            assertThat(source()).containsEntry("followerCount", 0).containsEntry("ratingCount", 0)
                    .containsEntry("hidden", false).containsEntry("verified", true)
                    .containsEntry("contentCategories",
                            List.of(Map.of("code", "GAME", "name", "게임"), mapOf("code", "VALORANT", "name", null)));
        }
    }

    @Nested
    @DisplayName("tombstone")
    class Tombstone {

        @Test
        @DisplayName("숨기면 개인정보를 지우고 hidden과 시각을 남긴다. 늦게 온 옛 이벤트는 되살리지 못하고, 더 새 이벤트는 다시 보이게 한다")
        void hideAndRevive() throws Exception {
            store.show(USER_ID, 5L, profile("페이커팬"));
            client.update(u -> u.index(InfluencerIndexDefinition.ALIAS).id(String.valueOf(USER_ID))
                    .doc(Map.of("channelNames", List.of("페이커팬 채널"), "representativeChannelName", "페이커팬 채널",
                            "followerCount", 500)), Map.class);

            assertThat(store.hide(USER_ID, 6L, NOW)).isEqualTo(WriteOutcome.UPDATED);
            assertThat(source()).containsEntry("hidden", true).containsEntry("memberVersion", 6)
                    .containsEntry("hiddenAt", NOW.toEpochMilli())
                    .doesNotContainKeys("nickname", "bio", "channelNames", "representativeChannelName");

            assertThat(store.show(USER_ID, 5L, profile("페이커팬"))).isEqualTo(WriteOutcome.NOOP);
            assertThat(source()).containsEntry("hidden", true);

            assertThat(store.show(USER_ID, 7L, profile("다시 승급"))).isEqualTo(WriteOutcome.UPDATED);
            assertThat(source()).containsEntry("hidden", false).containsEntry("nickname", "다시 승급")
                    .doesNotContainKey("hiddenAt");
        }

        @Test
        @DisplayName("문서가 없어도 숨김 문서를 남겨, 강등(v6)이 승급(v5)보다 먼저 와도 v5가 문서를 보이게 만들지 못한다(Q6)")
        void tombstoneWithoutDocument() throws Exception {
            assertThat(store.hide(USER_ID, 6L, NOW)).isEqualTo(WriteOutcome.CREATED);
            assertThat(store.show(USER_ID, 5L, profile("페이커팬"))).isEqualTo(WriteOutcome.NOOP);

            assertThat(source()).containsEntry("hidden", true).containsEntry("memberVersion", 6)
                    .doesNotContainKey("nickname");
        }

        @Test
        @DisplayName("7일 기준보다 먼저 숨긴 문서만 지운다. 최근 숨긴 문서와 보이는 문서는 남는다")
        void deleteHiddenBefore() throws Exception {
            store.hide(1L, 1L, NOW.minus(Duration.ofDays(8)));
            store.hide(2L, 1L, NOW.minus(Duration.ofDays(1)));
            store.show(3L, 1L, profile("보이는 회원"));
            client.indices().refresh(r -> r.index(InfluencerIndexDefinition.ALIAS));

            assertThat(store.deleteHiddenBefore(NOW.minus(Duration.ofDays(7)))).isEqualTo(1);

            assertThat(exists(1L)).isFalse();
            assertThat(exists(2L)).isTrue();
            assertThat(exists(3L)).isTrue();
        }
    }

    @Test
    @DisplayName("alias가 없으면(ES를 새로 만듦) 쓰지 않고 IndexNotReadyException이다. ES가 alias 이름으로 index를 멋대로 만들지 않는다")
    void requiresAlias() throws Exception {
        SearchElasticsearch.reset(client);

        assertThatThrownBy(() -> store.show(USER_ID, 1L, profile("페이커팬"))).isInstanceOf(IndexNotReadyException.class);
        assertThatThrownBy(() -> store.hide(USER_ID, 1L, NOW)).isInstanceOf(IndexNotReadyException.class);
        assertThatThrownBy(() -> store.findMembers(List.of(USER_ID))).isInstanceOf(IndexNotReadyException.class);
        assertThatThrownBy(store::countVisible).isInstanceOf(IndexNotReadyException.class);
        assertThatThrownBy(() -> store.deleteHiddenBefore(NOW)).isInstanceOf(IndexNotReadyException.class);
        assertThatThrownBy(() -> store.syncCategoryNames(Map.of())).isInstanceOf(IndexNotReadyException.class);
        assertThat(client.indices().exists(e -> e.index(InfluencerIndexDefinition.ALIAS)).value()).isFalse();
    }

    @Nested
    @DisplayName("이름을 정한 index에 쓰기(#773 처음 적재·재색인)")
    class TargetIndex {

        private final InfluencerIndexSetup setup = new InfluencerIndexSetup(client);

        @Test
        @SuppressWarnings("unchecked")
        @DisplayName("alias가 없는 새 index에 회원을 bulk로 넣고(version 조건부, 숨김 문서 포함), 통계를 채우고, 보이는 문서를 센다. alias 쪽 index는 그대로다")
        void loadsIntoNamedIndex() throws Exception {
            String fresh = freshIndex();

            Map<Long, WriteOutcome> loaded = store.loadMembers(fresh, List.of(
                    MemberDocument.visible(1L, 5L, profile("페이커팬")), MemberDocument.visible(2L, 1L, profile("메이플장인")),
                    MemberDocument.hidden(3L, 2L, NOW)));
            assertThat(loaded).containsEntry(1L, WriteOutcome.CREATED).containsEntry(2L, WriteOutcome.CREATED)
                    .containsEntry(3L, WriteOutcome.CREATED);
            assertThat(store.loadMembers(fresh, List.of(MemberDocument.visible(1L, 4L, profile("옛 닉네임")))))
                    .containsEntry(1L, WriteOutcome.NOOP);

            Map<Long, WriteOutcome> stats = store.applyStats(fresh, List.of(
                    new InfluencerStats(1L, List.of("페이커팬TV"), "페이커팬TV", 1000L, 4.5, 2L),
                    new InfluencerStats(9L, List.of("없는회원"), "없는회원", 1L, null, 0L)));
            assertThat(stats).containsEntry(1L, WriteOutcome.UPDATED).containsEntry(9L, WriteOutcome.MISSING);

            setup.refresh(fresh);
            assertThat(store.countVisible(fresh)).isEqualTo(2);
            assertThat(store.countVisible()).isZero();
            Map<String, Object> source = client.get(g -> g.index(fresh).id("1"), Map.class).source();
            assertThat(source).containsEntry("nickname", "페이커팬").containsEntry("memberVersion", 5)
                    .containsEntry("followerCount", 1000).containsEntry("hidden", false);
            Map<String, Object> tombstone = client.get(g -> g.index(fresh).id("3"), Map.class).source();
            assertThat(tombstone).containsEntry("hidden", true).containsEntry("memberVersion", 2)
                    .containsEntry("hiddenAt", NOW.toEpochMilli()).doesNotContainKey("nickname");
        }

        @Test
        @DisplayName("빈 목록은 ES를 부르지 않는다")
        void empty() {
            assertThat(store.loadMembers(InfluencerIndexDefinition.INDEX_PREFIX + "none", List.of())).isEmpty();
        }

        /** alias가 지금 index를 가리키는 채로, alias 없는 다른 index를 하나 더 만든다 */
        private String freshIndex() throws Exception {
            String fresh = InfluencerIndexDefinition.INDEX_PREFIX + "fresh0000000";
            String body = InfluencerIndexDefinition.load().indexBody();
            client.indices().create(c -> c.index(fresh).withJson(new java.io.StringReader(body)));
            return fresh;
        }
    }

    private static MemberProfile profile(String nickname) {
        return new MemberProfile(nickname, "롤 방송합니다", "VERIFIED_INFLUENCER", true,
                List.of(new MemberProfile.Category("GAME", "게임"), new MemberProfile.Category("VALORANT", null)));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> source() throws Exception {
        return client.get(g -> g.index(InfluencerIndexDefinition.ALIAS).id(String.valueOf(USER_ID)), Map.class).source();
    }

    private boolean exists(long userId) throws Exception {
        return client.exists(e -> e.index(InfluencerIndexDefinition.ALIAS).id(String.valueOf(userId))).value();
    }

    private static Map<String, Object> mapOf(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> map = new java.util.HashMap<>();
        map.put(k1, v1);
        map.put(k2, v2);
        return map;
    }
}
