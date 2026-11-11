package com.hapbang.auxiliary.search.influencer.app;

import static com.hapbang.auxiliary.search.support.FakeMemberStatsSource.category;
import static com.hapbang.auxiliary.search.support.FakeMemberStatsSource.channel;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.auxiliary.search.influencer.app.InfluencerStatsSync.StatsSyncResult;
import com.hapbang.auxiliary.search.common.out.ConsistencyCheck;
import com.hapbang.auxiliary.search.influencer.out.InfluencerDocumentStore;
import com.hapbang.auxiliary.search.influencer.out.InfluencerIndexDefinition;
import com.hapbang.auxiliary.search.influencer.out.InfluencerIndexSetup;
import com.hapbang.auxiliary.search.influencer.out.MemberProfile;
import com.hapbang.auxiliary.search.support.FakeMemberStatsSource;
import com.hapbang.auxiliary.search.support.InMemoryConsistencyCheckStore;
import com.hapbang.auxiliary.search.support.SearchElasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;

/** 10분 통계·분야 이름 동기화(#771). 회원 앱은 가짜, ES는 nori 컨테이너다 */
class InfluencerStatsSyncTest {

    private final ElasticsearchClient client = SearchElasticsearch.client();

    private final InfluencerDocumentStore store = new InfluencerDocumentStore(client);

    private final FakeMemberStatsSource source = new FakeMemberStatsSource();

    private final InMemoryConsistencyCheckStore checks = new InMemoryConsistencyCheckStore();

    private final InfluencerStatsSync sync = new InfluencerStatsSync(new InfluencerIndexSetup(client), store, source,
            new ContentCategoryNames(source), null, checks);

    @BeforeEach
    void reset() throws Exception {
        SearchElasticsearch.reset(client);
        SearchElasticsearch.createIndex(client);
        source.categories(category("GAME", "게임"), category("MAPLESTORY", "메이플스토리"));
    }

    @Test
    @DisplayName("채널 이름은 모두, 팔로워와 대표 채널은 가장 큰 채널로 쓴다. 평점은 원래 평균·리뷰 수다")
    void appliesStats() throws Exception {
        store.show(1L, 1L, profile());
        source.influencer(1L, 4.5, 12, channel("본 채널", 30_000), channel("다시보기", 5_000));

        StatsSyncResult result = sync.sync();

        assertThat(result.updated()).isEqualTo(1);
        assertThat(source(1L)).containsEntry("channelNames", List.of("본 채널", "다시보기"))
                .containsEntry("representativeChannelName", "본 채널").containsEntry("followerCount", 30_000)
                .containsEntry("ratingAverage", 4.5).containsEntry("ratingCount", 12);
    }

    @Test
    @DisplayName("값이 같으면 다시 쓰지 않는다(noop). 리뷰·채널이 없으면 평균 없음·팔로워 0이다")
    void noopWhenSame() throws Exception {
        store.show(1L, 1L, profile());
        source.influencer(1L, null, 0);

        assertThat(sync.sync().updated()).isEqualTo(1);
        assertThat(source(1L)).containsEntry("followerCount", 0).containsEntry("ratingCount", 0)
                .containsEntry("channelNames", List.of());
        assertThat(source(1L).get("ratingAverage")).isNull();

        StatsSyncResult again = sync.sync();
        assertThat(again.updated()).isZero();
        assertThat(again.noop()).isEqualTo(1);
    }

    @Test
    @DisplayName("리뷰가 모두 사라지면(평균 없음) 이전 평균을 지운다")
    void clearsRating() throws Exception {
        store.show(1L, 1L, profile());
        source.influencer(1L, 4.5, 2);
        sync.sync();
        source.reset().influencer(1L, null, 0);

        sync.sync();

        assertThat(source(1L).get("ratingAverage")).isNull();
        assertThat(source(1L)).containsEntry("ratingCount", 0);
    }

    @Test
    @DisplayName("문서가 없는 회원은 건너뛰고(검색 2의 보정), 숨긴 문서에는 채널 이름을 다시 넣지 않는다")
    void skipsMissingAndHidden() throws Exception {
        store.hide(2L, 1L, Instant.parse("2026-11-10T00:00:00Z"));
        source.influencer(1L, 4.0, 1, channel("없는 문서", 10)).influencer(2L, 4.0, 1, channel("숨긴 문서", 10));

        StatsSyncResult result = sync.sync();

        assertThat(result.missing()).isEqualTo(1);
        assertThat(result.noop()).isEqualTo(1);
        assertThat(client.exists(e -> e.index(InfluencerIndexDefinition.ALIAS).id("1")).value()).isFalse();
        assertThat(source(2L)).doesNotContainKey("channelNames").containsEntry("hidden", true);
    }

    @Test
    @DisplayName("통계를 페이지 끝까지 읽는다")
    void readsAllPages() throws Exception {
        for (long id = 1; id <= 5; id++) {
            store.show(id, 1L, profile());
            source.influencer(id, null, 0, channel("채널" + id, (int) id));
        }
        source.pageSize(2);

        StatsSyncResult result = sync.sync();

        assertThat(result.pages()).isEqualTo(3);
        assertThat(result.updated()).isEqualTo(5);
        assertThat(source(5L)).containsEntry("followerCount", 5);
    }

    @Test
    @DisplayName("분야 이름이 바뀌거나, 이름을 모른 채 색인한 분야가 있으면 문서의 이름을 맞춘다")
    void syncsCategoryNames() throws Exception {
        store.show(1L, 1L, new MemberProfile("닉", null, "INFLUENCER", false,
                List.of(new MemberProfile.Category("GAME", "게임"), new MemberProfile.Category("MAPLESTORY", null))));
        store.show(2L, 1L, new MemberProfile("닉2", null, "INFLUENCER", false, List.of()));
        client.indices().refresh(r -> r.index(InfluencerIndexDefinition.ALIAS));
        source.categories(category("GAME", "게임 방송"), category("MAPLESTORY", "메이플스토리"));

        StatsSyncResult result = sync.sync();

        assertThat(result.categoryDocuments()).isEqualTo(1);
        assertThat(source(1L).get("contentCategories")).isEqualTo(List.of(
                Map.of("code", "GAME", "name", "게임 방송"), Map.of("code", "MAPLESTORY", "name", "메이플스토리")));

        client.indices().refresh(r -> r.index(InfluencerIndexDefinition.ALIAS));
        assertThat(sync.sync().categoryDocuments()).isZero();
    }

    @Test
    @DisplayName("분야 목록을 읽지 못해도 통계는 맞춘다(분야 이름 맞추기만 건너뜀)")
    void statsContinueWhenCategoriesFail() throws Exception {
        store.show(1L, 1L, profile());
        source.failCategories().influencer(1L, 4.5, 2, channel("본 채널", 100));

        StatsSyncResult result = sync.sync();

        assertThat(result.categoryDocuments()).isEqualTo(-1);
        assertThat(result.updated()).isEqualTo(1);
        assertThat(source(1L)).containsEntry("followerCount", 100);
    }

    @Test
    @DisplayName("숨긴 문서의 분야 이름은 훑지 않는다(다시 보이게 될 때 회원 이벤트가 쓴다)")
    void categoryNamesSkipHidden() throws Exception {
        store.show(1L, 1L, profile());
        store.hide(1L, 2L, Instant.parse("2026-11-10T00:00:00Z"));
        client.indices().refresh(r -> r.index(InfluencerIndexDefinition.ALIAS));
        source.categories(category("GAME", "게임 방송"));

        assertThat(sync.sync().categoryDocuments()).isZero();
        assertThat(source(1L).get("contentCategories")).isEqualTo(List.of(Map.of("code", "GAME", "name", "게임")));
    }

    @Test
    @DisplayName("정합성 점검: 원본 정상 인플루언서 수와 보이는 문서 수가 같으면 차이 0이다. 숨긴 문서는 세지 않는다")
    void consistencyMatches() throws Exception {
        store.show(1L, 1L, profile());
        store.show(2L, 1L, profile());
        store.hide(3L, 1L, Instant.parse("2026-11-10T00:00:00Z"));
        source.influencer(1L, null, 0).influencer(2L, null, 0);
        refresh();

        StatsSyncResult result = sync.sync();

        assertThat(result.sourceInfluencers()).isEqualTo(2);
        assertThat(result.indexVisible()).isEqualTo(2);
        assertThat(result.difference()).isZero();
    }

    @Test
    @DisplayName("정합성 점검: index에 빠진 인플루언서가 있으면 차이가 양수, 보이면 안 되는 문서가 있으면 음수다")
    void consistencyDifference() throws Exception {
        store.show(1L, 1L, profile());
        source.influencer(1L, null, 0).influencer(2L, null, 0);
        refresh();
        assertThat(sync.sync().difference()).isEqualTo(1);
        assertThat(savedDifference()).isEqualTo(1);

        store.show(3L, 1L, profile());
        store.show(4L, 1L, profile());
        refresh();
        assertThat(sync.sync().difference()).isEqualTo(-1);
        assertThat(savedDifference()).isEqualTo(-1);
    }

    @Test
    @DisplayName("정합성 점검: 보이는 문서 수를 세지 못해도 통계 동기화는 끝내고 점검만 건너뛴다(-1). 지난 차이는 지표에서 지운다")
    void consistencySkippedWhenCountFails() throws Exception {
        store.show(1L, 1L, profile());
        source.influencer(1L, 4.5, 2);
        refresh();
        sync.sync();
        assertThat(savedDifference()).isZero();
        source.reset().influencer(1L, 4.0, 3);
        InfluencerDocumentStore countFails = new InfluencerDocumentStore(client) {
            @Override
            public long countVisible() {
                throw new IllegalStateException("ES 장애");
            }
        };
        InfluencerStatsSync failing = new InfluencerStatsSync(new InfluencerIndexSetup(client), countFails, source,
                new ContentCategoryNames(source), null, checks);

        StatsSyncResult result = failing.sync();

        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.consistencyChecked()).isFalse();
        assertThat(result.indexVisible()).isEqualTo(-1);
        assertThat(checks.find(ConsistencyCheck.INFLUENCER)).isEmpty();
    }

    @Test
    @DisplayName("정합성 점검: 결과를 DB에 남기지 못해도 통계 동기화는 성공으로 끝내고 지난 결과를 지운다")
    void consistencySaveFailureDoesNotFailSync() throws Exception {
        store.show(1L, 1L, profile());
        source.influencer(1L, 4.5, 2);
        refresh();
        sync.sync();
        assertThat(checks.find(ConsistencyCheck.INFLUENCER)).isPresent();
        checks.failSave();

        StatsSyncResult result = sync.sync();

        assertThat(result.consistencyChecked()).isTrue();
        assertThat(checks.find(ConsistencyCheck.INFLUENCER)).isEmpty();
    }

    private long savedDifference() {
        return checks.find(ConsistencyCheck.INFLUENCER).orElseThrow().difference();
    }

    private void refresh() throws Exception {
        client.indices().refresh(r -> r.index(InfluencerIndexDefinition.ALIAS));
    }

    private static MemberProfile profile() {
        return new MemberProfile("닉네임", null, "INFLUENCER", false, List.of(new MemberProfile.Category("GAME", "게임")));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> source(long userId) throws Exception {
        return client.get(g -> g.index(InfluencerIndexDefinition.ALIAS).id(String.valueOf(userId)), Map.class).source();
    }
}
