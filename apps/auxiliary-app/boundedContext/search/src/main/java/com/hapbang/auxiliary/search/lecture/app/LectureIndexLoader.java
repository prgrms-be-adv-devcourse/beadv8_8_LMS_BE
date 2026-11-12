package com.hapbang.auxiliary.search.lecture.app;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.hapbang.auxiliary.search.common.app.IndexLoader;
import com.hapbang.auxiliary.search.common.out.WriteOutcome;
import com.hapbang.auxiliary.search.lecture.out.LectureDocumentStore;
import com.hapbang.auxiliary.search.lecture.out.LectureDocumentStore.LectureLoad;
import com.hapbang.auxiliary.search.lecture.out.LectureRating;
import com.hapbang.auxiliary.search.lecture.out.LectureReviewCopyStore;
import com.hapbang.auxiliary.search.lecture.out.LectureSyncSource;
import com.hapbang.auxiliary.search.lecture.out.SearchMemberCopyStore;
import com.hapbang.auxiliary.search.lecture.out.Seller;
import com.hapbang.auxiliary.search.out.MemberListSource;
import com.hapbang.shared.lecture.dto.LectureSyncDto;
import com.hapbang.shared.lecture.dto.LectureSyncPageDto;
import com.hapbang.shared.member.dto.MemberPageDto;
import com.hapbang.shared.member.dto.UserDto;

import lombok.extern.slf4j.Slf4j;

/**
 * 강의 재색인 채우기 부품(#884 L14). 처음 적재·재색인이 만든 새 index(alias 없음)를 아래 순서로 채운다.
 * <ol>
 *     <li><b>판매자 사본</b>: 회원 목록 API(모든 회원)를 끝까지 읽어 회원 사본에 반영한다(회원 version 비교, 이미 같으면 그대로). 강의 문서의 판매자
 *     칸을 채우기 전에 해야 기존 판매자의 닉네임·정지 상태가 빠지지 않는다.</li>
 *     <li><b>리뷰 사본</b>: 리뷰 동기화 API 전체로 리뷰 사본을 맞춘다(리뷰 보정과 같은 코드).</li>
 *     <li><b>강의</b>: 강의 동기화 API를 끝까지 읽어 페이지마다 그 판매자·평점 사본을 한 번에 읽고 bulk로 넣는다. 강의 이벤트와 같은 script라 판매 중이
 *     아닌 강의는 숨김 문서다.</li>
 * </ol>
 * 사본은 "비어 있을 때만"이 아니라 처음 적재·재색인마다 맞춘다(#884 Q2 a). 운영 사본은 #882 배포 뒤의 이벤트로 일부만 차 있어, 비었는지로 고르면 그 전
 * 판매자·리뷰가 빠진다. 처음 적재와 재색인이 같은 길이다.
 * <p>
 * 정보가 빠진 회원은 사본 맞추기에서 건너뛰고, ES가 그 강의 값 때문에 거절한 강의는 건너뛰고 센다(멈추면 처음 적재가 영영 끝나지 않는다). 그 밖의
 * 쓰기 실패(장애)는 멈춘다.
 */
@Slf4j
class LectureIndexLoader implements IndexLoader {

    /** 회원 목록 한 페이지 크기(회원 API 상한) */
    static final int MEMBER_PAGE_SIZE = MemberPageDto.MAX_SIZE;

    /** 강의 동기화 한 페이지 크기(강의 API 상한) */
    static final int LECTURE_PAGE_SIZE = LectureSyncPageDto.MAX_SIZE;

    private final LectureDocumentStore documents;
    private final SearchMemberCopyStore members;
    private final LectureReviewCopyStore reviews;
    private final MemberListSource memberList;
    private final LectureSyncSource lectures;
    private final LectureReviewReconciler reviewReconciler;
    private final Clock clock;

    LectureIndexLoader(LectureDocumentStore documents, SearchMemberCopyStore members, LectureReviewCopyStore reviews,
            MemberListSource memberList, LectureSyncSource lectures, LectureReviewReconciler reviewReconciler,
            Clock clock) {
        this.documents = documents;
        this.members = members;
        this.reviews = reviews;
        this.memberList = memberList;
        this.lectures = lectures;
        this.reviewReconciler = reviewReconciler;
        this.clock = clock;
    }

    @Override
    public LoadResult load(String index) {
        syncSellerCopies();
        LectureReconcileResult reviewCopies = reviewReconciler.syncCopies();
        log.info("강의 검색 처음 적재·재색인 전에 리뷰 사본을 맞췄다 index={}, read={}, copied={}, deleted={}", index,
                reviewCopies.read(), reviewCopies.count(LectureReconcileKind.COPIED),
                reviewCopies.count(LectureReconcileKind.REVIEW_DELETED));

        long read = 0;
        long visible = 0;
        long hidden = 0;
        long skipped = 0;
        Long afterId = null;
        do {
            LectureSyncPageDto page = lectures.lectures(afterId, LECTURE_PAGE_SIZE);
            List<LectureSyncDto> items = page.items();
            Map<Long, Seller> sellers = members.findSellers(
                    items.stream().map(LectureSyncDto::sellerUserId).distinct().toList());
            Map<Long, LectureRating> ratings = reviews.ratingsOf(items.stream().map(LectureSyncDto::id).toList());
            List<LectureLoad> loads = new ArrayList<>();
            for (LectureSyncDto lecture : items) {
                loads.add(new LectureLoad(lecture, sellers.get(lecture.sellerUserId()), ratings.get(lecture.id())));
            }
            Map<Long, WriteOutcome> outcomes = documents.loadLectures(index, loads, clock.instant());
            requireNoFailure(outcomes, index);
            for (LectureSyncDto lecture : items) {
                if (outcomes.get(lecture.id()) == WriteOutcome.REJECTED) {
                    // ES가 이 강의의 값 때문에 거절했다. 다시 넣어도 같으므로 건너뛴다(멈추면 처음 적재가 영영 끝나지 않는다)
                    log.warn("ES가 거절해 강의 검색 처음 적재에서 건너뛴다 index={}, lectureId={}", index, lecture.id());
                    skipped++;
                } else if (lecture.onSale()) {
                    visible++;
                } else {
                    hidden++;
                }
            }
            read += items.size();
            afterId = page.nextAfterId();
        } while (afterId != null);
        return new LoadResult(read, visible, hidden, skipped);
    }

    /** 회원 목록 전체로 회원 사본을 맞춘다(Q2 a). 회원 version 비교라 이미 같은 회원은 그대로다 */
    private void syncSellerCopies() {
        long read = 0;
        long skipped = 0;
        Long afterId = null;
        do {
            MemberPageDto page = memberList.members(afterId, MEMBER_PAGE_SIZE);
            LocalDateTime now = LocalDateTime.now(clock);
            for (UserDto user : page.items()) {
                try {
                    members.apply(user, now);
                } catch (NullPointerException e) {
                    // 회원 ID·상태가 빠졌다. 다시 읽어도 같으므로 건너뛴다(인플루언서 처음 적재와 같다)
                    log.warn("회원 정보가 빠져 강의 검색 회원 사본 맞추기에서 건너뛴다 userId={}",
                            user == null ? null : user.id(), e);
                    skipped++;
                }
            }
            read += page.items().size();
            afterId = page.nextAfterId();
        } while (afterId != null);
        log.info("강의 검색 처음 적재·재색인 전에 회원 사본을 맞췄다 read={}, skipped={}", read, skipped);
    }

    /** 넣지 못한 강의가 있으면(장애) 멈춘다. 그 강의가 빠진 채 alias를 옮기면 다음 보정까지 검색에 보이지 않는다 */
    private static void requireNoFailure(Map<Long, WriteOutcome> outcomes, String index) {
        List<Long> failed = outcomes.entrySet().stream().filter(e -> e.getValue() == WriteOutcome.FAILED)
                .map(Map.Entry::getKey).sorted().toList();
        if (!failed.isEmpty()) {
            throw new IllegalStateException("새 index에 강의를 넣지 못했습니다 index=" + index + ", lectureIds=" + failed);
        }
    }
}
