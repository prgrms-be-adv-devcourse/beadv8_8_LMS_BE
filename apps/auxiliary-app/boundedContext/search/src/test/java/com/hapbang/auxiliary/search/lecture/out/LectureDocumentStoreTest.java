package com.hapbang.auxiliary.search.lecture.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.auxiliary.search.common.out.IndexNotReadyException;
import com.hapbang.auxiliary.search.common.out.WriteOutcome;
import com.hapbang.auxiliary.search.support.SearchElasticsearch;
import com.hapbang.shared.lecture.dto.LectureContentSyncDto;
import com.hapbang.shared.lecture.dto.LectureSyncDto;

import co.elastic.clients.elasticsearch.ElasticsearchClient;

/** 강의 검색 문서 쓰기(#882): 강의 version 조건부 쓰기, tombstone, 판매자·평점 칸. nori ES 컨테이너로 돈다 */
class LectureDocumentStoreTest {

    private static final Instant NOW = Instant.parse("2026-11-12T06:00:00Z");
    private static final Seller SELLER = new Seller(7L, "편집장", true);
    private static final LectureRating RATING = new LectureRating(4.5, 2);

    private final ElasticsearchClient client = SearchElasticsearch.client();
    private final LectureDocumentStore store = new LectureDocumentStore(client);

    @BeforeEach
    void reset() throws Exception {
        SearchElasticsearch.reset(client);
        SearchElasticsearch.createLectureIndex(client);
    }

    @Test
    @DisplayName("판매 중인 강의는 보이는 문서로 만들고 강의·판매자·평점 칸을 모두 채운다")
    void createsVisibleDocument() throws Exception {
        assertThat(store.writeLecture(lecture(11L, "프리미어 컷편집", true, 1L), SELLER, RATING, NOW))
                .isEqualTo(WriteOutcome.CREATED);

        Map<String, Object> doc = source(11L);
        assertThat(doc).containsEntry("lectureId", 11).containsEntry("lectureVersion", 1).containsEntry("hidden", false)
                .containsEntry("title", "프리미어 컷편집").containsEntry("description", "소개")
                .containsEntry("contentTitles", List.of("1강 설치", "2강 컷편집"))
                .containsEntry("sellerUserId", 7).containsEntry("sellerNickname", "편집장")
                .containsEntry("sellerActive", true).containsEntry("averageRating", 4.5).containsEntry("reviewCount", 2)
                .doesNotContainKey("hiddenAt");
    }

    @Test
    @DisplayName("회원 사본에 판매자가 아직 없으면 닉네임 없이 정상 판매자로 넣는다")
    void unknownSeller() throws Exception {
        store.writeLecture(lecture(11L, "제목", true, 1L), null, LectureRating.NONE, NOW);

        Map<String, Object> doc = source(11L);
        assertThat(doc).containsEntry("sellerActive", true).containsEntry("reviewCount", 0);
        assertThat(doc.get("sellerNickname")).isNull();
        assertThat(doc.get("averageRating")).isNull();
    }

    @Test
    @DisplayName("더 새 version만 반영하고, 순서가 뒤바뀐 옛 version과 같은 이벤트 두 번은 noop이다")
    void lectureVersion() throws Exception {
        store.writeLecture(lecture(11L, "처음", true, 1L), SELLER, RATING, NOW);

        assertThat(store.writeLecture(lecture(11L, "수정", true, 3L), SELLER, RATING, NOW))
                .isEqualTo(WriteOutcome.UPDATED);
        assertThat(store.writeLecture(lecture(11L, "늦게 온 옛 수정", true, 2L), SELLER, RATING, NOW))
                .isEqualTo(WriteOutcome.NOOP);
        assertThat(store.writeLecture(lecture(11L, "수정", true, 3L), SELLER, RATING, NOW))
                .isEqualTo(WriteOutcome.NOOP);
        assertThat(source(11L)).containsEntry("title", "수정").containsEntry("lectureVersion", 3);
    }

    @Test
    @DisplayName("판매 중이 아니면 숨기고, 삭제 뒤 늦게 온 판매 중 이벤트는 문서를 되살리지 못한다")
    void tombstone() throws Exception {
        store.writeLecture(lecture(11L, "제목", true, 1L), SELLER, RATING, NOW);

        store.writeLecture(lecture(11L, "제목", false, 3L), SELLER, RATING, NOW);
        assertThat(source(11L)).containsEntry("hidden", true).containsEntry("hiddenAt", NOW.toEpochMilli());

        assertThat(store.writeLecture(lecture(11L, "제목", true, 2L), SELLER, RATING, NOW))
                .isEqualTo(WriteOutcome.NOOP);
        assertThat(source(11L)).containsEntry("hidden", true);

        store.writeLecture(lecture(11L, "다시 판매", true, 4L), SELLER, RATING, NOW);
        assertThat(source(11L)).containsEntry("hidden", false).doesNotContainKey("hiddenAt");
    }

    @Test
    @DisplayName("문서가 없어도 판매 중이 아닌 강의는 숨김 문서로 남긴다(늦게 온 옛 이벤트를 막는다)")
    void tombstoneWithoutDocument() throws Exception {
        assertThat(store.writeLecture(lecture(11L, "삭제된", false, 5L), SELLER, RATING, NOW))
                .isEqualTo(WriteOutcome.CREATED);

        assertThat(store.writeLecture(lecture(11L, "옛", true, 4L), SELLER, RATING, NOW)).isEqualTo(WriteOutcome.NOOP);
        assertThat(source(11L)).containsEntry("hidden", true);
    }

    @Test
    @DisplayName("이미 있는 문서의 강의 쓰기는 판매자·평점 칸을 넘긴 값으로 되돌리지 않는다")
    void lectureWriteKeepsSellerAndRating() throws Exception {
        store.writeLecture(lecture(11L, "제목", true, 1L), SELLER, RATING, NOW);
        store.writeSeller(11L, new Seller(7L, "새닉네임", true));
        store.writeRating(11L, new LectureRating(3.0, 3));

        store.writeLecture(lecture(11L, "수정", true, 2L), SELLER, RATING, NOW);

        assertThat(source(11L)).containsEntry("title", "수정").containsEntry("sellerNickname", "새닉네임")
                .containsEntry("averageRating", 3.0).containsEntry("reviewCount", 3);
    }

    @Test
    @DisplayName("평점 칸은 바뀌면 고치고, 같으면 noop, 문서가 없으면 건너뛴다. 리뷰가 모두 빠지면 평균을 비운다")
    void writeRating() throws Exception {
        assertThat(store.writeRating(11L, RATING)).isEqualTo(WriteOutcome.MISSING);

        store.writeLecture(lecture(11L, "제목", true, 1L), SELLER, RATING, NOW);

        assertThat(store.writeRating(11L, RATING)).isEqualTo(WriteOutcome.NOOP);
        assertThat(store.writeRating(11L, new LectureRating(5.0, 3))).isEqualTo(WriteOutcome.UPDATED);
        assertThat(source(11L)).containsEntry("averageRating", 5.0).containsEntry("reviewCount", 3);

        assertThat(store.writeRating(11L, LectureRating.NONE)).isEqualTo(WriteOutcome.UPDATED);
        assertThat(source(11L).get("averageRating")).isNull();
        assertThat(source(11L)).containsEntry("reviewCount", 0);
    }

    @Test
    @DisplayName("판매자 칸 갱신은 그 판매자의 모든 강의(숨긴 문서·방금 만든 문서 포함)만 고친다")
    void updateSeller() throws Exception {
        store.writeLecture(lecture(11L, "판매 중", true, 1L), SELLER, RATING, NOW);
        store.writeLecture(lecture(12L, "판매 중지", false, 1L), SELLER, RATING, NOW);
        store.writeLecture(new LectureSyncDto(13L, 8L, "다른 판매자", null, true, false, List.of(), 1L),
                new Seller(8L, "다른", true), RATING, NOW);

        assertThat(store.updateSeller(new Seller(7L, "정지된", false))).isEqualTo(2);

        assertThat(source(11L)).containsEntry("sellerNickname", "정지된").containsEntry("sellerActive", false);
        assertThat(source(12L)).containsEntry("sellerNickname", "정지된").containsEntry("sellerActive", false);
        assertThat(source(13L)).containsEntry("sellerNickname", "다른").containsEntry("sellerActive", true);

        assertThat(store.updateSeller(new Seller(7L, "정지된", false))).isZero();
        assertThat(store.writeSeller(11L, new Seller(7L, "복귀", true))).isEqualTo(WriteOutcome.UPDATED);
        assertThat(store.writeSeller(99L, SELLER)).isEqualTo(WriteOutcome.MISSING);
    }

    @Test
    @DisplayName("판매자 칸은 문서의 회원 version보다 작은 값으로 되돌리지 않는다. 같은 version은 다시 쓰고, version 없는 정보는 문서에 version이 있으면 쓰지 않는다(Q12)")
    void sellerVersion() throws Exception {
        store.writeLecture(lecture(11L, "제목", true, 1L), new Seller(7L, "편집장", true, 1L), RATING, NOW);
        assertThat(source(11L)).containsEntry("sellerVersion", 1);

        assertThat(store.updateSeller(new Seller(7L, "정지된", false, 3L))).isEqualTo(1);
        assertThat(store.writeSeller(11L, new Seller(7L, "편집장", true, 2L))).isEqualTo(WriteOutcome.NOOP);
        assertThat(store.updateSeller(new Seller(7L, "편집장", true, 2L))).isZero();
        assertThat(store.writeSeller(11L, new Seller(7L, "편집장", true))).isEqualTo(WriteOutcome.NOOP);
        assertThat(source(11L)).containsEntry("sellerNickname", "정지된").containsEntry("sellerActive", false)
                .containsEntry("sellerVersion", 3);

        assertThat(store.writeSeller(11L, new Seller(7L, "정지된", false, 3L))).isEqualTo(WriteOutcome.NOOP);
        assertThat(store.writeSeller(11L, new Seller(7L, "복귀", true, 4L))).isEqualTo(WriteOutcome.UPDATED);
        assertThat(source(11L)).containsEntry("sellerActive", true).containsEntry("sellerVersion", 4);
    }

    @Test
    @DisplayName("tombstone 정리는 오래된 숨김 문서만 지운다. 판매자가 정지된 강의는 숨김이 아니라 정지가 길어도 남는다(Q2)")
    void cleanupKeepsSuspendedSellerLectures() throws Exception {
        Instant old = NOW.minus(Duration.ofDays(8));
        store.writeLecture(lecture(11L, "오래전 판매 중지", false, 1L), SELLER, RATING, old);
        store.writeLecture(lecture(12L, "최근 판매 중지", false, 1L), SELLER, RATING, NOW);
        store.writeLecture(new LectureSyncDto(13L, 8L, "정지된 판매자", null, true, false, List.of(), 1L),
                new Seller(8L, "정지", false), RATING, old);
        client.indices().refresh(r -> r.index(LectureIndexDefinition.ALIAS));

        assertThat(store.writes().deleteHiddenBefore(NOW.minus(Duration.ofDays(7)))).isEqualTo(1);

        assertThat(client.exists(e -> e.index(LectureIndexDefinition.ALIAS).id("11")).value()).isFalse();
        assertThat(source(12L)).containsEntry("hidden", true);
        assertThat(source(13L)).containsEntry("hidden", false).containsEntry("sellerActive", false);
    }

    @Test
    @DisplayName("alias가 없으면(처음 적재 전) 쓰기를 IndexNotReadyException으로 알린다")
    void notReady() throws Exception {
        SearchElasticsearch.reset(client);

        assertThatThrownBy(() -> store.writeLecture(lecture(11L, "제목", true, 1L), SELLER, RATING, NOW))
                .isInstanceOf(IndexNotReadyException.class);
        assertThatThrownBy(() -> store.updateSeller(SELLER)).isInstanceOf(IndexNotReadyException.class);
        assertThatThrownBy(() -> store.writeRating(11L, RATING)).isInstanceOf(IndexNotReadyException.class);
        assertThat(client.indices().exists(e -> e.index(LectureIndexDefinition.ALIAS)).value()).isFalse();
    }

    private static LectureSyncDto lecture(long id, String title, boolean onSale, long version) {
        return new LectureSyncDto(id, 7L, title, "소개", onSale, false,
                List.of(new LectureContentSyncDto(1L, "1강 설치", 1), new LectureContentSyncDto(2L, "2강 컷편집", 2)),
                version);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> source(long id) throws Exception {
        return client.get(g -> g.index(LectureIndexDefinition.ALIAS).id(String.valueOf(id)), Map.class).source();
    }
}
