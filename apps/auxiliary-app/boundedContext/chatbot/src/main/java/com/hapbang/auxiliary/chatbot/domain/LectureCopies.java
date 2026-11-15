package com.hapbang.auxiliary.chatbot.domain;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

import com.hapbang.shared.lecture.domain.LecturePurchaseResult;
import com.hapbang.shared.lecture.dto.CartItemSyncDto;
import com.hapbang.shared.lecture.dto.LectureContentSyncDto;
import com.hapbang.shared.lecture.dto.LecturePurchaseSyncDto;
import com.hapbang.shared.lecture.dto.LectureReviewSyncDto;
import com.hapbang.shared.lecture.dto.LectureSyncDto;

/**
 * 강의 쪽 사본 4종에 넣을 값 (#717). 강의 이벤트와 강의 싱크가 같은 DTO를 준다.
 * <ul>
 *     <li>강의가 판단한 값({@code onSale}, {@code result}, {@code counted})을 그대로 담고 다시 해석하지 않는다.</li>
 *     <li>해시는 받은 DTO에서 version만 뺀 값으로 계산한다({@link CopyHash}). 시각은 KST로 맞춘 뒤 넣어 offset이 달라도 같다.</li>
 * </ul>
 */
public final class LectureCopies {

    private LectureCopies() {
    }

    /**
     * 강의. 콘텐츠는 sequence 순 제목만 담고, 콘텐츠 id·sequence는 해시에만 반영한다.
     */
    public record Lecture(long id, long sellerUserId, String title, String description, List<String> contentTitles,
            boolean onSale, boolean deleted, long version, String hash) {

        public static Lecture from(LectureSyncDto dto) {
            List<LectureContentSyncDto> contents = dto.contents().stream()
                    .sorted(Comparator.comparingInt(LectureContentSyncDto::sequence)).toList();
            String hash = CopyHash.of(new HashedLecture(dto.id(), dto.sellerUserId(), dto.title(), dto.description(),
                    dto.onSale(), dto.deleted(), contents));
            return new Lecture(dto.id(), dto.sellerUserId(), dto.title(), dto.description(),
                    contents.stream().map(LectureContentSyncDto::title).toList(), dto.onSale(), dto.deleted(),
                    dto.version(), hash);
        }
    }

    /** 구매 = 주문 항목 */
    public record Purchase(long orderItemId, long buyerUserId, long lectureId, LecturePurchaseResult result,
            LocalDateTime orderedAt, LocalDateTime purchasedAt, long version, String hash) {

        public static Purchase from(LecturePurchaseSyncDto dto) {
            LocalDateTime orderedAt = CopyTimes.kst(dto.orderedAt());
            LocalDateTime purchasedAt = CopyTimes.kst(dto.purchasedAt());
            String hash = CopyHash.of(new HashedPurchase(dto.orderItemId(), dto.buyerUserId(), dto.lectureId(),
                    dto.result(), orderedAt, purchasedAt));
            return new Purchase(dto.orderItemId(), dto.buyerUserId(), dto.lectureId(), dto.result(), orderedAt,
                    purchasedAt, dto.version(), hash);
        }
    }

    /** 장바구니 항목 */
    public record CartItem(long id, long userId, long lectureId, boolean deleted, LocalDateTime addedAt, long version,
            String hash) {

        public static CartItem from(CartItemSyncDto dto) {
            LocalDateTime addedAt = CopyTimes.kst(dto.addedAt());
            String hash = CopyHash.of(new HashedCartItem(dto.id(), dto.userId(), dto.lectureId(), dto.deleted(), addedAt));
            return new CartItem(dto.id(), dto.userId(), dto.lectureId(), dto.deleted(), addedAt, dto.version(), hash);
        }
    }

    /** 강의 리뷰 별점 */
    public record Review(long id, long lectureId, long reviewerUserId, int rating, boolean counted, boolean deleted,
            long version, String hash) {

        public static Review from(LectureReviewSyncDto dto) {
            String hash = CopyHash.of(new HashedReview(dto.id(), dto.lectureId(), dto.reviewerUserId(), dto.rating(),
                    dto.counted(), dto.deleted()));
            return new Review(dto.id(), dto.lectureId(), dto.reviewerUserId(), dto.rating(), dto.counted(), dto.deleted(),
                    dto.version(), hash);
        }
    }

    // 해시에 넣는 값. version은 넣지 않는다
    private record HashedLecture(Long id, Long sellerUserId, String title, String description, boolean onSale,
            boolean deleted, List<LectureContentSyncDto> contents) {
    }

    private record HashedPurchase(Long orderItemId, Long buyerUserId, Long lectureId, LecturePurchaseResult result,
            LocalDateTime orderedAt, LocalDateTime purchasedAt) {
    }

    private record HashedCartItem(Long id, Long userId, Long lectureId, boolean deleted, LocalDateTime addedAt) {
    }

    private record HashedReview(Long id, Long lectureId, Long reviewerUserId, int rating, boolean counted,
            boolean deleted) {
    }
}
