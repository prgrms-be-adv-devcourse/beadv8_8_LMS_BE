package com.hapbang.auxiliary.search.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.auxiliary.search.app.InfluencerMemberIndexer.MemberIndexResult;
import com.hapbang.auxiliary.search.out.InfluencerDocumentStore;
import com.hapbang.auxiliary.search.out.InfluencerIndexDefinition;
import com.hapbang.auxiliary.search.out.InfluencerIndexSetup;
import com.hapbang.auxiliary.search.out.WriteOutcome;
import com.hapbang.auxiliary.search.support.SearchElasticsearch;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;

import co.elastic.clients.elasticsearch.ElasticsearchClient;

/** 회원 정보 → 검색 문서: 색인 대상 판단, 분야 이름 붙이기, 잘못된 정보 거절(#771). nori ES 컨테이너로 돈다 */
class InfluencerMemberIndexerTest {

    private static final Instant NOW = Instant.parse("2026-11-10T00:00:00Z");

    private final ElasticsearchClient client = SearchElasticsearch.client();

    private final ContentCategoryNames categoryNames = new ContentCategoryNames();

    private final InfluencerMemberIndexer indexer = new InfluencerMemberIndexer(new InfluencerIndexSetup(client),
            new InfluencerDocumentStore(client), categoryNames, Clock.fixed(NOW, ZoneId.of("Asia/Seoul")));

    @BeforeEach
    void reset() throws Exception {
        SearchElasticsearch.reset(client);
        categoryNames.replaceAll(Map.of("GAME", "게임", "MAPLESTORY", "메이플스토리"));
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("정상 인플루언서는 보이게 넣고, 분야 코드에 이름을 붙인다(모르는 코드는 이름 없이). index가 없으면 먼저 만든다")
    void showsInfluencer() throws Exception {
        MemberIndexResult result = indexer.apply(user(1L, Role.INFLUENCER, UserStatus.ACTIVE, 3L));

        assertThat(result).isEqualTo(new MemberIndexResult(1L, 3L, true, WriteOutcome.CREATED));
        assertThat(source(1L)).containsEntry("nickname", "메이플장인").containsEntry("role", "INFLUENCER")
                .containsEntry("verified", false).containsEntry("hidden", false);
        List<Object> categories = (List<Object>) source(1L).get("contentCategories");
        assertThat(categories).hasSize(3).contains(Map.of("code", "MAPLESTORY", "name", "메이플스토리"));
    }

    @Test
    @DisplayName("인증된 인플루언서는 verified=true다")
    void verified() throws Exception {
        indexer.apply(user(1L, Role.VERIFIED_INFLUENCER, UserStatus.ACTIVE, 3L));

        assertThat(source(1L)).containsEntry("verified", true);
    }

    @Test
    @DisplayName("일반 유저·관리자, 정지·탈퇴한 인플루언서는 숨긴다")
    void hidesOthers() throws Exception {
        assertThat(indexer.apply(user(1L, Role.USER, UserStatus.ACTIVE, 1L)).visible()).isFalse();
        assertThat(indexer.apply(user(2L, Role.ADMIN, UserStatus.ACTIVE, 1L)).visible()).isFalse();
        for (UserStatus status : UserStatus.values()) {
            if (status != UserStatus.ACTIVE) {
                assertThat(indexer.apply(user(3L, Role.INFLUENCER, status, (long) status.ordinal() + 10)).visible())
                        .isFalse();
            }
        }

        assertThat(source(1L)).containsEntry("hidden", true).containsEntry("hiddenAt", NOW.toEpochMilli());
        assertThat(source(3L)).containsEntry("hidden", true).doesNotContainKey("nickname");
    }

    @Test
    @DisplayName("회원 ID·역할·상태가 없으면 IllegalArgumentException(재시도 없이 DLT)")
    void rejectsInvalid() {
        assertThatThrownBy(() -> indexer.apply(new UserDto(null, "닉", Role.INFLUENCER, UserStatus.ACTIVE, 1L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> indexer.apply(new UserDto(1L, "닉", null, UserStatus.ACTIVE, 1L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> indexer.apply(new UserDto(1L, "닉", Role.INFLUENCER, null, 1L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> indexer.apply(null)).isInstanceOf(IllegalArgumentException.class);
    }

    private static UserDto user(long id, Role role, UserStatus status, Long version) {
        return new UserDto(id, "메이플장인", role, status, version, List.of("GAME", "MAPLESTORY", "NEW_CODE"), "메이플 방송");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> source(long userId) throws Exception {
        return client.get(g -> g.index(InfluencerIndexDefinition.ALIAS).id(String.valueOf(userId)), Map.class).source();
    }
}
