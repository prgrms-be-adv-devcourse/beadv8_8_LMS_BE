package com.hapbang.auxiliary.chatbot.domain;

import java.time.LocalDateTime;
import java.util.List;

import com.hapbang.shared.lecture.domain.LecturePurchaseResult;

/**
 * 강의 쪽 사본 4종에 넣을 값 (#717). 강의 이벤트와 강의 싱크가 같은 DTO를 주고, app의 {@code CopyMapper}가 만든다.
 * <ul>
 *     <li>강의가 판단한 값({@code onSale}, {@code result}, {@code counted})을 그대로 담고 다시 해석하지 않는다.</li>
 *     <li>해시는 받은 DTO에서 version만 뺀 값으로 계산한다({@code CopyHash}). 시각은 KST로 맞춘 뒤 넣어 offset이 달라도 같다.</li>
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
    }

    /** 구매 = 주문 항목 */
    public record Purchase(long orderItemId, long buyerUserId, long lectureId, LecturePurchaseResult result,
            LocalDateTime orderedAt, LocalDateTime purchasedAt, long version, String hash) {
    }

    /** 장바구니 항목 */
    public record CartItem(long id, long userId, long lectureId, boolean deleted, LocalDateTime addedAt, long version,
            String hash) {
    }

    /** 강의 리뷰 별점 */
    public record Review(long id, long lectureId, long reviewerUserId, int rating, boolean counted, boolean deleted,
            long version, String hash) {
    }
}
