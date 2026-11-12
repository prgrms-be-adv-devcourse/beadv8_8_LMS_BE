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
import com.hapbang.auxiliary.search.lecture.out.SearchMemberCopyStore;
import com.hapbang.auxiliary.search.lecture.out.Seller;
import com.hapbang.shared.lecture.dto.LectureReviewSyncDto;
import com.hapbang.shared.lecture.dto.LectureSyncDto;

/**
 * 강의·리뷰 정보를 강의 검색 문서에 반영한다(#882).
 * <ul>
 *     <li>강의({@link #apply(LectureSyncDto)}): 판매 중이면 보이는 문서, 아니면 숨김 문서(L8). 강의 version 비교는 문서 쓰기가 한다. 강의 칸만
 *     먼저 쓰고, 문서를 새로 만들었을 때만 판매자는 회원 사본, 평점은 리뷰 사본에서 읽어 채운다(L5·Q4·Q9).</li>
 *     <li>리뷰({@link #apply(LectureReviewSyncDto)}): 리뷰 사본에 적고 그 강의의 평균·리뷰 수를 다시 세어 문서의 평점 칸을 바꾼다(L7·Q4).</li>
 * </ul>
 * 사본(DB)은 alias가 없어도(처음 적재 전) 고친다. 처음 적재(#884)가 사본으로 문서를 만들기 때문이다(L14). 문서 쓰기만 {@link IndexNotReadyException}으로
 * 알리고, 부르는 쪽이 건너뛴다.
 */
@Service
public class LectureIndexer {

    private final LectureIndexSetup indexSetup;
    private final LectureDocumentStore documents;
    private final SearchMemberCopyStore members;
    private final LectureReviewCopyStore reviews;
    private final Clock clock;

    @Autowired
    public LectureIndexer(LectureIndexSetup indexSetup, LectureDocumentStore documents, SearchMemberCopyStore members,
            LectureReviewCopyStore reviews) {
        this(indexSetup, documents, members, reviews, Clock.system(SellerIndexer.ZONE));
    }

    LectureIndexer(LectureIndexSetup indexSetup, LectureDocumentStore documents, SearchMemberCopyStore members,
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
     * 강의 칸만 먼저 쓴다(새 문서면 판매자 닉네임 없이 정상 판매자, 평점 없음으로 만든다). 이미 있는 문서의 판매자·평점 칸은 회원·리뷰 반영이 맡으므로 사본을
     * 읽지 않는다(Q9). 문서를 <b>새로 만들었을 때만</b> 사본을 읽어 판매자·평점 칸을 채운다. 문서를 만든 뒤에 읽으므로, 그 전에 온 회원·리뷰 이벤트(없던
     * 문서를 고치지 못함)의 값도 빠지지 않는다. 채우기 전 잠깐(같은 처리 안)은 빈 값이지만 ES가 새 문서를 검색에 보이는 refresh(1초) 전이다.
     * <p>
     * 쓰기 결과가 {@code NOOP}(같은 이벤트를 다시 받음, 순서가 뒤바뀐 옛 이벤트)이어도 채운다. 문서를 만든 뒤 채우기가 실패해 같은 이벤트를 다시 받으면
     * 강의 쓰기는 이미 그 version이라 {@code NOOP}이기 때문이다(PR #906 리뷰). 사본의 지금 값을 쓰고 값이 같으면 noop이라 옛 이벤트에서 돌아도
     * 해가 없다. 보통의 수정({@code UPDATED})은 사본을 읽지 않는다.
     *
     * @throws IllegalArgumentException 강의 정보가 없는 경우(다시 처리해도 같다. 필수 값은 DTO 생성자가 막는다)
     * @throws IndexNotReadyException   alias가 없는 경우(처음 적재 전). 부르는 쪽이 건너뛴다
     * @throws IllegalStateException    ES에 쓰지 못한 경우(다시 처리하면 될 수 있다). ES가 요청 내용 때문에 거절했으면 하위 타입
     *                                  {@link com.hapbang.auxiliary.search.common.out.DocumentRejectedException}이다(다시 해도 같다)
     */
    public LectureIndexResult apply(LectureSyncDto lecture) {
        if (lecture == null) {
            throw new IllegalArgumentException("강의 정보가 없는 강의 이벤트는 색인할 수 없습니다.");
        }
        indexSetup.ensureReady();
        WriteOutcome outcome = documents.writeLecture(lecture, null, LectureRating.NONE, clock.instant());
        if (outcome == WriteOutcome.CREATED || outcome == WriteOutcome.NOOP) {
            fillFromCopies(lecture);
        }
        return new LectureIndexResult(lecture.id(), lecture.version(), lecture.onSale(), outcome);
    }

    /**
     * 리뷰를 사본에 적고 그 강의의 평점 칸을 다시 맞춘다. 사본이 이미 같거나 더 새 version이어도 평점은 다시 센다. 앞선 처리에서 사본은 커밋했는데 문서
     * 쓰기가 실패해 같은 이벤트를 다시 받은 경우에도 문서를 맞추기 위해서다(값이 같으면 문서 쓰기는 noop이다).
     *
     * @throws IllegalArgumentException 리뷰 정보가 없는 경우(다시 처리해도 같다. 필수 값은 DTO 생성자가 막는다)
     * @throws IndexNotReadyException   alias가 없는 경우(처음 적재 전). 사본은 이미 고쳤다. 부르는 쪽이 건너뛴다
     * @throws IllegalStateException    ES에 쓰지 못한 경우(다시 처리하면 될 수 있다)
     */
    public ReviewIndexResult apply(LectureReviewSyncDto review) {
        if (review == null) {
            throw new IllegalArgumentException("리뷰 정보가 없는 리뷰 이벤트는 반영할 수 없습니다.");
        }
        boolean copied = reviews.apply(review, LocalDateTime.now(clock));
        indexSetup.ensureReady();
        LectureRating rating = reviews.ratingOf(review.lectureId());
        WriteOutcome outcome = documents.writeRating(review.lectureId(), rating);
        return new ReviewIndexResult(review.id(), review.lectureId(), review.version(), copied, outcome);
    }

    /** 새로 만든 문서의 판매자·평점 칸을 사본으로 채운다. 사본에 판매자가 없거나 리뷰가 없으면 만든 값(정상 판매자, 평점 없음) 그대로 둔다 */
    private void fillFromCopies(LectureSyncDto lecture) {
        Optional<Seller> seller = members.findSeller(lecture.sellerUserId());
        if (seller.isPresent()) {
            documents.writeSeller(lecture.id(), seller.get());
        }
        LectureRating rating = reviews.ratingOf(lecture.id());
        if (!rating.equals(LectureRating.NONE)) {
            documents.writeRating(lecture.id(), rating);
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
