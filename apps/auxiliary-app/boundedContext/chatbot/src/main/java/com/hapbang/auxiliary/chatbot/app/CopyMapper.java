package com.hapbang.auxiliary.chatbot.app;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

import com.hapbang.auxiliary.chatbot.domain.AppearanceCopy;
import com.hapbang.auxiliary.chatbot.domain.AppearanceReviewCopy;
import com.hapbang.auxiliary.chatbot.domain.CopyTimes;
import com.hapbang.auxiliary.chatbot.domain.LectureCopies;
import com.hapbang.auxiliary.chatbot.domain.MemberCopy;
import com.hapbang.shared.appearance.domain.AppearanceResult;
import com.hapbang.shared.appearance.dto.AppearanceReviewSyncDto;
import com.hapbang.shared.appearance.dto.AppearanceSyncDto;
import com.hapbang.shared.appearance.event.AppearanceReviewPublishedEvent;
import com.hapbang.shared.lecture.domain.LecturePurchaseResult;
import com.hapbang.shared.lecture.dto.CartItemSyncDto;
import com.hapbang.shared.lecture.dto.LectureContentSyncDto;
import com.hapbang.shared.lecture.dto.LecturePurchaseSyncDto;
import com.hapbang.shared.lecture.dto.LectureReviewSyncDto;
import com.hapbang.shared.lecture.dto.LectureSyncDto;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;

/**
 * 다른 모듈의 DTO·이벤트 → 사본 값 (#716, #717, #835). domain이 shared DTO·이벤트를 모르게 변환을 여기 한 곳에 둔다.
 * <ul>
 *     <li>이벤트와 싱크가 같은 DTO를 주므로 둘 다 여기서 만든다.</li>
 *     <li>다른 모듈이 판단한 값(출연 {@code result}, 강의 {@code onSale}·{@code result}·{@code counted})을 그대로 담고 다시 해석하지 않는다.</li>
 *     <li>해시는 받은 DTO에서 비교할 값만 골라 {@link CopyHashUtil}로 계산한다. version은 넣지 않는다.
 *     시각은 {@link CopyTimes}로 KST·마이크로초로 맞춘 뒤 넣어 offset이 달라도 같다.</li>
 * </ul>
 */
final class CopyMapper {

    private CopyMapper() {
    }

    static AppearanceCopy appearance(AppearanceSyncDto dto) {
        LocalDateTime scheduledAt = CopyTimes.kst(dto.scheduledAt());
        String hash = CopyHashUtil.of(new HashedAppearance(dto.id(), dto.hostId(), dto.guestId(), dto.result(), scheduledAt));
        return new AppearanceCopy(dto.id(), dto.hostId(), dto.guestId(), dto.result(), scheduledAt, dto.version(), hash);
    }

    /** 리뷰는 공개 뒤 바뀌지 않아 version이 없다. 받은 싱크 DTO 전체로 해시한다 */
    static AppearanceReviewCopy appearanceReview(AppearanceReviewSyncDto dto) {
        return new AppearanceReviewCopy(dto.reviewId(), dto.appearanceId(), dto.revieweeUserId(), dto.rating(),
                CopyHashUtil.of(dto));
    }

    /**
     * 리뷰 공개 이벤트에서 싱크 DTO({@link AppearanceReviewSyncDto})와 같은 4필드만 뽑는다(출연 #713 계약).
     * 그래야 이벤트로 받은 값과 싱크로 받은 값의 해시가 같다. 필수 값이 없으면 {@link NullPointerException}
     */
    static AppearanceReviewCopy appearanceReview(AppearanceReviewPublishedEvent event) {
        return appearanceReview(new AppearanceReviewSyncDto(event.reviewId(), event.appearanceId(),
                event.revieweeUserId(), event.rating()));
    }

    /** 닉네임은 저장하지 않지만 해시에는 넣는다(받은 DTO 전체로 계산) */
    static MemberCopy member(UserDto dto) {
        String hash = CopyHashUtil.of(new HashedMember(dto.id(), dto.nickname(), dto.role(), dto.status(),
                dto.contentCategories(), dto.bio()));
        return new MemberCopy(dto.id(), dto.role(), dto.status(), dto.contentCategories(), dto.bio(), dto.version(), hash);
    }

    /** 강의. 콘텐츠는 sequence 순 제목만 담고, 콘텐츠 id·sequence는 해시에만 반영한다 */
    static LectureCopies.Lecture lecture(LectureSyncDto dto) {
        List<LectureContentSyncDto> contents = dto.contents().stream()
                .sorted(Comparator.comparingInt(LectureContentSyncDto::sequence)).toList();
        String hash = CopyHashUtil.of(new HashedLecture(dto.id(), dto.sellerUserId(), dto.title(), dto.description(),
                dto.onSale(), dto.deleted(), contents));
        return new LectureCopies.Lecture(dto.id(), dto.sellerUserId(), dto.title(), dto.description(),
                contents.stream().map(LectureContentSyncDto::title).toList(), dto.onSale(), dto.deleted(),
                dto.version(), hash);
    }

    static LectureCopies.Purchase purchase(LecturePurchaseSyncDto dto) {
        LocalDateTime orderedAt = CopyTimes.kst(dto.orderedAt());
        LocalDateTime purchasedAt = CopyTimes.kst(dto.purchasedAt());
        String hash = CopyHashUtil.of(new HashedPurchase(dto.orderItemId(), dto.buyerUserId(), dto.lectureId(),
                dto.result(), orderedAt, purchasedAt));
        return new LectureCopies.Purchase(dto.orderItemId(), dto.buyerUserId(), dto.lectureId(), dto.result(), orderedAt,
                purchasedAt, dto.version(), hash);
    }

    static LectureCopies.CartItem cartItem(CartItemSyncDto dto) {
        LocalDateTime addedAt = CopyTimes.kst(dto.addedAt());
        String hash = CopyHashUtil.of(new HashedCartItem(dto.id(), dto.userId(), dto.lectureId(), dto.deleted(), addedAt));
        return new LectureCopies.CartItem(dto.id(), dto.userId(), dto.lectureId(), dto.deleted(), addedAt, dto.version(),
                hash);
    }

    static LectureCopies.Review lectureReview(LectureReviewSyncDto dto) {
        String hash = CopyHashUtil.of(new HashedReview(dto.id(), dto.lectureId(), dto.reviewerUserId(), dto.rating(),
                dto.counted(), dto.deleted()));
        return new LectureCopies.Review(dto.id(), dto.lectureId(), dto.reviewerUserId(), dto.rating(), dto.counted(),
                dto.deleted(), dto.version(), hash);
    }

    // 해시에 넣는 값. version은 넣지 않는다. 필드 이름이 JSON 키가 되므로 이름을 바꾸면 해시가 바뀐다
    private record HashedAppearance(Long id, Long hostId, Long guestId, AppearanceResult result,
            LocalDateTime scheduledAt) {
    }

    private record HashedMember(Long id, String nickname, Role role, UserStatus status, List<String> contentCategories,
            String bio) {
    }

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
