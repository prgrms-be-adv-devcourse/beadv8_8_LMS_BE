package com.hapbang.auxiliary.chatbot.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.hapbang.auxiliary.chatbot.config.ChatbotInfluencerRecommendProperties;
import com.hapbang.auxiliary.chatbot.domain.IndexType;
import com.hapbang.auxiliary.chatbot.out.IndexTaskStore;
import com.hapbang.auxiliary.chatbot.out.InfluencerRecommendStore;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.shared.member.event.UserMaskedEvent;

/**
 * 인플루언서 색인 요청이 언제 적히는지 (#719): 회원 사본이 바뀐 트랜잭션, 그리고 정합 점검(처음 전체 색인 겸).
 */
class InfluencerIndexRequestTest extends InfluencerIntegrationTestBase {

    @Autowired
    private InfluencerIndexChecker checker;

    @Autowired
    private MemberMasking memberMasking;

    @Autowired
    private InfluencerRecommendStore store;

    @Autowired
    private IndexTaskStore indexTaskStore;

    @Autowired
    private ChatbotInfluencerRecommendProperties properties;

    @Test
    @DisplayName("회원 사본의 값이 바뀌면 INFLUENCER UPSERT를 적고, 값이 같은 싱크 반영은 적지 않는다. 일반 회원도 적는다(강등 문서 숨김)")
    void applierWritesRequestOnChange() {
        UserDto first = new UserDto(11L, "회원11", Role.INFLUENCER, UserStatus.ACTIVE, 1L, List.of("GAME"), "롤");
        copyApplier.apply(first);
        copyApplier.apply(first);
        copyApplier.apply(new UserDto(11L, "회원11", Role.USER, UserStatus.ACTIVE, 2L, List.of("GAME"), "롤"));
        copyApplier.apply(new UserDto(12L, "회원12", Role.USER, UserStatus.ACTIVE, 1L, List.of(), null));

        assertThat(influencerTasks()).containsExactly("11", "11", "12");
        assertThat(rows.indexTasks()).allSatisfy(task -> assertThat(task).containsEntry("action", "UPSERT"));
    }

    @Test
    @DisplayName("마스킹 회원의 회원 변경은 UPSERT를 적지 않는다(마스킹이 이미 DELETE를 적었다)")
    void maskedMemberWritesNoUpsert() {
        copyApplier.apply(new UserDto(11L, "회원11", Role.INFLUENCER, UserStatus.ACTIVE, 1L, List.of("GAME"), "롤"));
        memberMasking.mask(new UserMaskedEvent(11L, Instant.parse("2026-11-10T01:00:00Z")));

        copyApplier.apply(new UserDto(11L, "회원11", Role.INFLUENCER, UserStatus.WITHDRAWN, 2L, List.of("GAME"), "롤"));

        assertThat(rows.indexTasks()).extracting(task -> task.get("action")).containsExactly("UPSERT", "DELETE");
    }

    @Test
    @DisplayName("정합 점검: 문서가 없으면 처음 전체 색인 요청을 적고, 맞으면 적지 않는다. 숨김·글이 어긋나거나 남은 문서도 요청을 적는다")
    void checkerQueuesMismatches() {
        influencer(11L, "GAME", "롤");
        influencer(12L, "MUSIC", "노래");
        member(13L, Role.USER, UserStatus.ACTIVE, List.of("GAME"), "롤");
        member(14L, Role.INFLUENCER, UserStatus.ACTIVE, List.of(), null);
        clearTasks();

        // 처음: 추천 가능하고 글이 있는 회원만
        assertThat(checker.check()).isEqualTo(2);
        assertThat(influencerTasks()).containsExactlyInAnyOrder("11", "12");

        index(11L, 12L, 13L, 14L);
        clearTasks();
        assertThat(checker.check()).isZero();

        // 숨김 어긋남(이벤트를 놓쳐 색인이 늦음), 마스킹 회원의 남은 문서
        index.update(IndexType.INFLUENCER, "11", Map.of(InfluencerIndexPlan.HIDDEN, true));
        index.refresh(IndexType.INFLUENCER);
        memberMasking.mask(new UserMaskedEvent(12L, Instant.parse("2026-11-10T01:00:00Z")));
        clearTasks();
        assertThat(checker.check()).isEqualTo(2);
        assertThat(influencerTasks()).containsExactlyInAnyOrder("11", "12");
    }

    @Test
    @DisplayName("정합 점검이 적은 요청을 처리기가 처리하면 색인이 사본과 맞는다(점검 → 처리 → 다시 점검 0)")
    void checkThenHandle() {
        influencer(11L, "GAME", "롤");
        influencer(12L, "MUSIC", "노래");
        clearTasks();
        checker.check();

        index(influencerTasks().stream().mapToLong(Long::parseLong).toArray());
        clearTasks();

        assertThat(checker.check()).isZero();
        assertThat(document(11L).hidden()).isFalse();
        assertThat(document(12L).hidden()).isFalse();
    }

    @Test
    @DisplayName("정합 점검: 분야 이름 표가 없어도(회원 API 장애) 마스킹 삭제·정지 숨김 요청은 적고, 이름이 필요한 회원만 건너뛴 뒤 실패로 끝낸다")
    void checkerWithoutCategoryNames() {
        influencer(11L, "GAME", "롤");
        influencer(12L, "MUSIC", "노래");
        index(11L, 12L);
        influencer(14L, "COOKING", "요리");
        index(14L);
        memberMasking.mask(new UserMaskedEvent(12L, Instant.parse("2026-11-10T01:00:00Z")));
        influencer(13L, "GAME", "새로 온 인플루언서");
        member(14L, Role.INFLUENCER, UserStatus.SUSPENDED, List.of("COOKING"), "요리");
        clearTasks();
        memberSource.failCategoryNames(new IllegalStateException("회원 API 장애"));
        InfluencerIndexChecker starting = new InfluencerIndexChecker(handler, index, store,
                new ContentCategoryNames(memberSource, properties, clockBean), indexTaskStore, properties, clockBean);

        // 11(글 비교)·13(새 문서)은 이름이 필요해 건너뛴다
        assertThatThrownBy(starting::check).isInstanceOf(IllegalStateException.class).hasMessageContaining("skipped=2");
        // 12(마스킹 삭제)·14(정지 숨김)는 이름 없이 판단한다
        assertThat(influencerTasks()).containsExactlyInAnyOrder("12", "14");
    }

    private List<String> influencerTasks() {
        return rows.indexTasks().stream()
                .filter(task -> "INFLUENCER".equals(task.get("index_type")))
                .map(task -> (String) task.get("doc_id"))
                .toList();
    }

    private void clearTasks() {
        jdbcTemplate.update("DELETE FROM chatbot.index_task");
    }
}
