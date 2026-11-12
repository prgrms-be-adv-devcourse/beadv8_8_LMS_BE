package com.hapbang.auxiliary.search.lecture.app;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.hapbang.auxiliary.search.common.out.IndexNotReadyException;
import com.hapbang.auxiliary.search.common.out.WriteOutcome;
import com.hapbang.auxiliary.search.lecture.out.LectureDocumentStore;
import com.hapbang.auxiliary.search.lecture.out.LectureIndexSetup;
import com.hapbang.auxiliary.search.lecture.out.LectureRating;
import com.hapbang.auxiliary.search.lecture.out.LectureReviewCopyStore;
import com.hapbang.auxiliary.search.lecture.out.MemberCopyStore;
import com.hapbang.auxiliary.search.lecture.out.Seller;
import com.hapbang.shared.lecture.dto.LectureReviewSyncDto;
import com.hapbang.shared.lecture.dto.LectureSyncDto;

/**
 * 강의·리뷰 정보를 강의 검색 문서에 반영한다(#882).
 * <ul>
 *     <li>강의({@link #apply(LectureSyncDto)}): 판매 중이면 보이는 문서, 아니면 숨김 문서(L8). 강의 version 비교는 문서 쓰기가 한다. 문서를 처음
 *     만들 때 판매자는 회원 사본, 평점은 리뷰 사본에서 채운다(L5·Q4).</li>
 *     <li>리뷰({@link #apply(LectureReviewSyncDto)}): 리뷰 사본에 적고 그 강의의 평균·리뷰 수를 다시 세어 문서의 평점 칸을 바꾼다(L7·Q4).</li>
 * </ul>
 * 사본(DB)은 alias가 없어도(처음 적재 전) 고친다. 처음 적재(#884)가 사본으로 문서를 만들기 때문이다(L14). 문서 쓰기만 {@link IndexNotReadyException}으로
 * 알리고, 부르는 쪽이 건너뛴다.
 */
@Service
public class LectureIndexer {

    private final LectureIndexSetup indexSetup;
    private final LectureDocumentStore documents;
    private final MemberCopyStore members;
    private final LectureReviewCopyStore reviews;
    private final Clock clock;

    @Autowired
    public LectureIndexer(LectureIndexSetup indexSetup, LectureDocumentStore documents, MemberCopyStore members,
            LectureReviewCopyStore reviews) {
        this(indexSetup, documents, members, reviews, Clock.system(SellerIndexer.ZONE));
    }

    LectureIndexer(LectureIndexSetup indexSetup, LectureDocumentStore documents, MemberCopyStore members,
            LectureReviewCopyStore reviews, Clock clock) {
        this.indexSetup = indexSetup;
        this.documents = documents;
        this.members = members;
        this.reviews = reviews;
        this.clock = clock;
    }

    /**
     * 강의 정보를 문서에 반영한다.
     * <p>
     * 문서를 처음 만들었으면 판매자·평점 사본을 한 번 더 읽어 다르면 맞춘다. 사본을 읽은 뒤 문서를 만들기 전에 회원·리뷰 이벤트가 사본을 바꾸면, 그
     * 이벤트는 아직 없던 문서를 고치지 못하고 이 쓰기는 옛 값을 넣기 때문이다.
     *
     * @throws IllegalArgumentException 강의 ID·판매자 ID·제목이 없는 정보(다시 처리해도 같다)
     * @throws IndexNotReadyException   alias가 없는 경우(처음 적재 전). 부르는 쪽이 건너뛴다
     * @throws IllegalStateException    ES에 쓰지 못한 경우(다시 처리하면 될 수 있다). ES가 요청 내용 때문에 거절했으면 하위 타입
     *                                  {@link com.hapbang.auxiliary.search.common.out.DocumentRejectedException}이다(다시 해도 같다)
     */
    public LectureIndexResult apply(LectureSyncDto lecture) {
        if (lecture == null || lecture.id() == null || lecture.sellerUserId() == null || lecture.title() == null) {
            throw new IllegalArgumentException("강의 ID·판매자 ID·제목이 없는 강의 정보는 색인할 수 없습니다.");
        }
        indexSetup.ensureReady();
        Seller seller = members.findSeller(lecture.sellerUserId()).orElse(null);
        LectureRating rating = reviews.ratingOf(lecture.id());
        WriteOutcome outcome = documents.writeLecture(lecture, seller, rating, clock.instant());
        if (outcome == WriteOutcome.CREATED) {
            catchUpCopies(lecture, seller, rating);
        }
        return new LectureIndexResult(lecture.id(), lecture.version(), lecture.onSale(), outcome);
    }

    /**
     * 리뷰를 사본에 적고 그 강의의 평점 칸을 다시 맞춘다. 사본이 이미 같거나 더 새 version이어도 평점은 다시 센다. 앞선 처리에서 사본은 커밋했는데 문서
     * 쓰기가 실패해 같은 이벤트를 다시 받은 경우에도 문서를 맞추기 위해서다(값이 같으면 문서 쓰기는 noop이다).
     *
     * @throws IllegalArgumentException 리뷰 ID·강의 ID가 없는 정보(다시 처리해도 같다)
     * @throws IndexNotReadyException   alias가 없는 경우(처음 적재 전). 사본은 이미 고쳤다. 부르는 쪽이 건너뛴다
     * @throws IllegalStateException    ES에 쓰지 못한 경우(다시 처리하면 될 수 있다)
     */
    public ReviewIndexResult apply(LectureReviewSyncDto review) {
        if (review == null || review.id() == null || review.lectureId() == null) {
            throw new IllegalArgumentException("리뷰 ID·강의 ID가 없는 리뷰 정보는 반영할 수 없습니다.");
        }
        boolean copied = reviews.apply(review, LocalDateTime.now(clock));
        indexSetup.ensureReady();
        LectureRating rating = reviews.ratingOf(review.lectureId());
        WriteOutcome outcome = documents.writeRating(review.lectureId(), rating);
        return new ReviewIndexResult(review.id(), review.lectureId(), review.version(), copied, outcome);
    }

    private void catchUpCopies(LectureSyncDto lecture, Seller seller, LectureRating rating) {
        Optional<Seller> latestSeller = members.findSeller(lecture.sellerUserId());
        if (latestSeller.isPresent() && !latestSeller.get().equals(seller)) {
            documents.writeSeller(lecture.id(), latestSeller.get());
        }
        LectureRating latestRating = reviews.ratingOf(lecture.id());
        if (!latestRating.equals(rating)) {
            documents.writeRating(lecture.id(), latestRating);
        }
    }

    /**
     * @param lectureId 강의 ID
     * @param version   강의 version
     * @param visible   검색에 보이게 썼는지(판매 중)
     * @param outcome   쓰기 결과
     */
    public record LectureIndexResult(long lectureId, long version, boolean visible, WriteOutcome outcome) {
    }

    /**
     * @param reviewId  리뷰 ID
     * @param lectureId 강의 ID
     * @param version   리뷰 version
     * @param copied    리뷰 사본에 반영했는지(false면 같거나 옛 version)
     * @param outcome   평점 칸 쓰기 결과(문서가 없으면 {@link WriteOutcome#MISSING})
     */
    public record ReviewIndexResult(long reviewId, long lectureId, long version, boolean copied,
            WriteOutcome outcome) {
    }
}
