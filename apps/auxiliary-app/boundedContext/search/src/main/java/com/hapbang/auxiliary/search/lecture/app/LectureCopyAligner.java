package com.hapbang.auxiliary.search.lecture.app;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.hapbang.auxiliary.search.common.out.WriteOutcome;
import com.hapbang.auxiliary.search.lecture.out.IndexedLecture;
import com.hapbang.auxiliary.search.lecture.out.LectureDocumentStore;
import com.hapbang.auxiliary.search.lecture.out.LectureHangulKeys;
import com.hapbang.auxiliary.search.lecture.out.LectureIndexSetup;
import com.hapbang.auxiliary.search.lecture.out.LectureRating;
import com.hapbang.auxiliary.search.lecture.out.LectureReviewCopyStore;
import com.hapbang.auxiliary.search.lecture.out.SearchMemberCopyStore;
import com.hapbang.auxiliary.search.lecture.out.Seller;

import lombok.extern.slf4j.Slf4j;

/**
 * 강의 문서의 판매자·평점 칸을 사본과, 초성·자모 칸을 원문과 맞춘다(#884, #973). 모든 문서(숨긴 문서 포함)를 강의 ID 순서로 페이지마다 읽어, 그 판매자의 회원 사본과 그 강의의 리뷰
 * 사본 집계를 한 번에 읽고 다른 칸만 고친다.
 * <ul>
 *     <li><b>평점 칸 경합</b>: 평점 칸은 version 보호가 없어, 리뷰 반영과 다른 스레드(보정·따라잡기)가 같은 강의를 동시에 세면 먼저 읽고 늦게
 *     쓴 옛 평균이 남을 수 있다. 강의별 잠금은 모든 평점 쓰기가 DB 트랜잭션 안에서 ES를 기다리게 하고, 사본 갱신 번호는 커밋 순서가 번호 순서와
 *     다를 때 구멍이 남아 두지 않았다. 대신 이 비교가 다음 회차(최대 1시간)에 바로잡는다.</li>
 *     <li><b>판매자 칸</b>: 회원 사본에 없는 판매자는 건너뛴다(회원 이벤트·판매자 보정이 채운다). 쓰기는 회원 version 조건이라 문서가 더 새 값이면
 *     noop이다({@link LectureDocumentStore#writeSeller}).</li>
 *     <li>재색인 뒤 따라잡기에서는 채우는 동안 바뀐 사본 값(이벤트는 사본만 고치고 문서 쓰기를 건너뜀)을 새 index에 반영한다.</li>
 *     <li><b>초성·자모 칸</b>(#973): 칸이 생긴 뒤의 롤링 배포에서, 재색인이 alias를 옮긴 뒤에도 잠깐 살아 있던 옛 pod가 제목·닉네임만 바꾸고 초성·자모는
 *     옛 값으로 남길 수 있다. 같은 version이라 강의 보정은 다시 쓰지 않으므로, 모든 문서를 한 번 더 넘기며 원문으로 다시 만든 값과 다르면
 *     compare-and-set으로 고친다({@link LectureDocumentStore#writeHangulKeys}, 원문이 그사이 바뀌었으면 건드리지 않는다). version 규칙과는 상관없다.
 *     변환 규칙({@code HangulKeys})만 고치면 index 설정 해시가 바뀌지 않아 재색인이 없는데, 그때 기존 문서를 새 규칙으로 맞추는 것도 이 일이라 늘
 *     돈다. 실패해도(alias를 옮기기 전 칸이 없는 옛 index에 쓰다 거절 등) 판매자·평점 결과와 회차는 실패로 치지 않고 WARN만 남긴다(다음 회차).</li>
 * </ul>
 * 락은 부르는 쪽(리뷰 보정, 재색인 따라잡기)이 쥔다. 둘이 겹쳐 돌아도 같은 사본 값을 쓰므로 결과가 같다.
 */
@Slf4j
@Service
public class LectureCopyAligner {

    /** 한 번에 읽는 문서 수 */
    static final int PAGE_SIZE = 500;

    private final LectureIndexSetup indexSetup;
    private final LectureDocumentStore documents;
    private final SearchMemberCopyStore members;
    private final LectureReviewCopyStore reviews;

    public LectureCopyAligner(LectureIndexSetup indexSetup, LectureDocumentStore documents,
            SearchMemberCopyStore members, LectureReviewCopyStore reviews) {
        this.indexSetup = indexSetup;
        this.documents = documents;
        this.members = members;
        this.reviews = reviews;
    }

    /**
     * 모든 강의 문서의 판매자·평점 칸을 사본과 맞춘다.
     *
     * @return 읽은 문서 수와 고친 칸 수({@link LectureReconcileKind#SELLER_FIXED}, {@link LectureReconcileKind#RATING_FIXED})
     * @throws com.hapbang.auxiliary.search.common.out.IndexNotReadyException alias가 없는 경우(처음 적재 전)
     * @throws IllegalStateException                                          ES를 읽거나 쓰지 못한 경우
     */
    public LectureReconcileResult align() {
        indexSetup.ensureReady();
        Map<LectureReconcileKind, Long> counts = new EnumMap<>(LectureReconcileKind.class);
        long read = 0;
        Long after = null;
        List<IndexedLecture> page;
        while (!(page = documents.page(after, PAGE_SIZE)).isEmpty()) {
            Map<Long, Seller> sellers = members.findSellers(
                    page.stream().map(doc -> doc.seller().userId()).distinct().toList());
            Map<Long, LectureRating> ratings = reviews.ratingsOf(page.stream().map(IndexedLecture::lectureId).toList());
            for (IndexedLecture doc : page) {
                alignSeller(doc, sellers.get(doc.seller().userId()), counts);
                alignRating(doc, ratings.get(doc.lectureId()), counts);
            }
            read += page.size();
            after = page.getLast().lectureId();
        }
        alignHangulKeys(counts);
        return new LectureReconcileResult(read, counts);
    }

    /**
     * 모든 문서의 초성·자모 칸을 원문과 맞춘다(#973). 판매자·평점 칸과 같은 페이지 읽기에 합치지 않고 한 번 더 넘긴다. 1시간에 한 번이고 강의 수백~수천
     * 개라 부담이 작으며, 합치면 보정이 함께 쓰는 비교 값({@link com.hapbang.auxiliary.search.lecture.out.IndexedLecture})을 넓혀야 한다.
     * 실패하면 WARN만 남긴다. 그 전에 고친 수는 지표에 남는다.
     */
    private void alignHangulKeys(Map<LectureReconcileKind, Long> counts) {
        Long after = null;
        try {
            List<LectureHangulKeys> page;
            while (!(page = documents.pageHangulKeys(after, PAGE_SIZE)).isEmpty()) {
                for (LectureHangulKeys doc : page) {
                    if (!doc.matchesSource() && documents.writeHangulKeys(doc) == WriteOutcome.UPDATED) {
                        counts.merge(LectureReconcileKind.HANGUL_KEYS_FIXED, 1L, Long::sum);
                        log.info("원문과 다른 강의 검색 문서의 초성·자모 칸을 고쳤다 lectureId={}", doc.lectureId());
                    }
                }
                after = page.getLast().lectureId();
            }
        } catch (RuntimeException e) {
            log.warn("강의 검색 문서의 초성·자모 칸을 맞추지 못해 다음 회차에 다시 한다 afterLectureId={}", after, e);
        }
    }

    private void alignSeller(IndexedLecture doc, Seller copy, Map<LectureReconcileKind, Long> counts) {
        if (copy == null || copy.equals(doc.seller())) {
            return;
        }
        if (documents.writeSeller(doc.lectureId(), copy) == WriteOutcome.UPDATED) {
            counts.merge(LectureReconcileKind.SELLER_FIXED, 1L, Long::sum);
            log.info("회원 사본과 다른 강의 검색 문서의 판매자 칸을 고쳤다 lectureId={}, sellerUserId={}, memberVersion={}",
                    doc.lectureId(), copy.userId(), copy.version());
        }
    }

    private void alignRating(IndexedLecture doc, LectureRating copy, Map<LectureReconcileKind, Long> counts) {
        if (copy.equals(doc.rating())) {
            return;
        }
        if (documents.writeRating(doc.lectureId(), copy) == WriteOutcome.UPDATED) {
            counts.merge(LectureReconcileKind.RATING_FIXED, 1L, Long::sum);
            log.info("리뷰 사본과 다른 강의 검색 문서의 평점 칸을 고쳤다 lectureId={}, reviewCount={}", doc.lectureId(),
                    copy.count());
        }
    }
}
