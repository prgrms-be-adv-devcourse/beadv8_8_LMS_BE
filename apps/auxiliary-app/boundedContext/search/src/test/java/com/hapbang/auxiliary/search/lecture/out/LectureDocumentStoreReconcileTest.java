package com.hapbang.auxiliary.search.lecture.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.auxiliary.search.common.out.IndexNotReadyException;
import com.hapbang.auxiliary.search.common.out.WriteOutcome;
import com.hapbang.auxiliary.search.lecture.out.LectureDocumentStore.LectureLoad;
import com.hapbang.auxiliary.search.support.SearchElasticsearch;
import com.hapbang.shared.lecture.dto.LectureContentSyncDto;
import com.hapbang.shared.lecture.dto.LectureSyncDto;

import co.elastic.clients.elasticsearch.ElasticsearchClient;

/** 강의 검색 문서의 처음 적재(bulk)·보정용 읽기·숨기기(#884). nori ES 컨테이너로 돈다 */
class LectureDocumentStoreReconcileTest {

    private static final Instant NOW = Instant.parse("2026-11-12T06:00:00Z");
    private static final Seller SELLER = new Seller(7L, "편집장", true, 3L);
    private static final LectureRating RATING = new LectureRating(4.5, 2);

    private final ElasticsearchClient client = SearchElasticsearch.client();
    private final LectureDocumentStore store = new LectureDocumentStore(client);
    private final LectureIndexSetup setup = new LectureIndexSetup(client);

    @BeforeEach
    void reset() throws Exception {
        SearchElasticsearch.reset(client);
        setup.syncSynonyms();
    }

    @Test
    @DisplayName("처음 적재는 alias 없는 새 index에 강의 이벤트와 같은 모양으로 넣는다: 판매 중은 보이고 아니면 숨김, 판매자·평점은 사본 값")
    void loadLectures() throws Exception {
        String index = setup.createIndex();

        Map<Long, WriteOutcome> outcomes = store.loadLectures(index, List.of(
                new LectureLoad(lecture(11L, true, 2L), SELLER, RATING),
                new LectureLoad(lecture(12L, false, 5L), null, LectureRating.NONE)), NOW);
        setup.refresh(index);

        assertThat(outcomes).containsOnly(Map.entry(11L, WriteOutcome.CREATED), Map.entry(12L, WriteOutcome.CREATED));
        assertThat(source(index, 11L)).containsEntry("lectureVersion", 2).containsEntry("hidden", false)
                .containsEntry("title", "강의11").containsEntry("contentTitles", List.of("1강"))
                .containsEntry("sellerNickname", "편집장").containsEntry("sellerActive", true)
                .containsEntry("sellerVersion", 3).containsEntry("averageRating", 4.5).containsEntry("reviewCount", 2);
        assertThat(source(index, 12L)).containsEntry("hidden", true).containsEntry("hiddenAt", NOW.toEpochMilli())
                .containsEntry("sellerActive", true).containsEntry("reviewCount", 0);
        assertThat(store.writes().countVisible(index)).isEqualTo(1);
        assertThat(store.loadLectures(index, List.of(), NOW)).isEmpty();
    }

    @Test
    @DisplayName("보정은 문서들의 강의 version·숨김·판매자·평점 칸을 한 번에 읽고, 문서가 없는 강의는 빠진다")
    void findLectures() {
        SearchElasticsearch.createLectureIndex(client);
        store.writeLecture(lecture(11L, true, 2L), SELLER, RATING, NOW);
        store.writeLecture(lecture(12L, false, 5L), null, LectureRating.NONE, NOW);

        Map<Long, IndexedLecture> found = store.findLectures(List.of(11L, 12L, 99L));

        assertThat(found).containsOnly(
                Map.entry(11L, new IndexedLecture(11L, 2L, false, SELLER, RATING)),
                Map.entry(12L, new IndexedLecture(12L, 5L, true, new Seller(7L, null, true, null),
                        LectureRating.NONE)));
        assertThat(store.findLectures(List.of())).isEmpty();
    }

    @Test
    @DisplayName("문서를 강의 ID 오름차순으로 숨긴 문서까지 페이지로 넘기며 읽는다")
    void page() throws Exception {
        String index = SearchElasticsearch.createLectureIndex(client);
        for (long id : List.of(13L, 11L, 12L)) {
            store.writeLecture(lecture(id, id != 12L, 1L), SELLER, RATING, NOW);
        }
        setup.refresh(index);

        List<IndexedLecture> first = store.page(null, 2);
        List<IndexedLecture> second = store.page(first.getLast().lectureId(), 2);

        assertThat(first).extracting(IndexedLecture::lectureId).containsExactly(11L, 12L);
        assertThat(first.get(1).hidden()).isTrue();
        assertThat(second).extracting(IndexedLecture::lectureId).containsExactly(13L);
        assertThat(store.page(13L, 2)).isEmpty();
    }

    @Test
    @DisplayName("원본에 없는 강의를 숨길 때 강의 version을 지워 같은 version으로도 다시 보이게 하고, 이미 숨겼으면 noop, 문서가 없으면 건너뛴다")
    void hideLecture() throws Exception {
        SearchElasticsearch.createLectureIndex(client);
        store.writeLecture(lecture(11L, true, 2L), SELLER, RATING, NOW);
        Instant later = NOW.plusSeconds(60);

        assertThat(store.hideLecture(11L, later)).isEqualTo(WriteOutcome.UPDATED);
        assertThat(store.hideLecture(11L, later.plusSeconds(60))).isEqualTo(WriteOutcome.NOOP);
        assertThat(store.hideLecture(99L, later)).isEqualTo(WriteOutcome.MISSING);

        assertThat(source(LectureIndexDefinition.ALIAS, 11L)).containsEntry("hidden", true)
                .containsEntry("hiddenAt", later.toEpochMilli()).containsEntry("sellerNickname", "편집장")
                .doesNotContainKey("lectureVersion");
        // 원본에 다시 나타나면(늦게 커밋된 강의를 잘못 숨긴 경우) 같은 version의 강의 쓰기로 다시 보이고, 판매자·평점 칸은 그대로다
        assertThat(store.writeLecture(lecture(11L, true, 2L), null, LectureRating.NONE, NOW))
                .isEqualTo(WriteOutcome.UPDATED);
        assertThat(source(LectureIndexDefinition.ALIAS, 11L)).containsEntry("hidden", false)
                .containsEntry("lectureVersion", 2).containsEntry("sellerNickname", "편집장")
                .containsEntry("reviewCount", 2);
    }

    @Test
    @DisplayName("alias가 없으면(처음 적재 전) 보정용 읽기·숨기기를 IndexNotReadyException으로 알린다")
    void notReady() {
        assertThatThrownBy(() -> store.findLectures(List.of(11L))).isInstanceOf(IndexNotReadyException.class);
        assertThatThrownBy(() -> store.page(null, 10)).isInstanceOf(IndexNotReadyException.class);
        assertThatThrownBy(() -> store.hideLecture(11L, NOW)).isInstanceOf(IndexNotReadyException.class);
    }

    private static LectureSyncDto lecture(long id, boolean onSale, long version) {
        return new LectureSyncDto(id, 7L, "강의" + id, "소개", 10_000L, onSale, false,
                List.of(new LectureContentSyncDto(1L, "1강", 1)), version);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> source(String index, long id) throws Exception {
        return client.get(g -> g.index(index).id(String.valueOf(id)), Map.class).source();
    }
}
