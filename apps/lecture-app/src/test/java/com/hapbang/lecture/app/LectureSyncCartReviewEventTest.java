package com.hapbang.lecture.app;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import com.hapbang.lecture.app.dto.OrderResponse;
import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.fake.FakeBillingApiClient;
import com.hapbang.lecture.fixture.ObjectionCommands;
import com.hapbang.lecture.out.LectureRepository;
import com.hapbang.lecture.out.LectureReviewRepository;
import com.hapbang.shared.lecture.dto.CartItemSyncDto;
import com.hapbang.shared.lecture.dto.LectureReviewSyncDto;
import com.hapbang.shared.lecture.event.CartItemChangedEvent;
import com.hapbang.shared.lecture.event.LectureReviewChangedEvent;
import com.hapbang.testsupport.IntegrationTest;

/**
 * AI 추천용 장바구니 항목·리뷰 동기화 이벤트(#712, D82)가 바뀌는 지점마다 바뀐 뒤 상태로 나가는지 Facade부터 DB까지 확인한다.
 * 각 테스트는 커밋 뒤 동기화 내부 API(DB에서 읽음)로 같은 ID를 다시 읽어 이벤트와 비교한다(버전·시각까지 같다).
 */
@IntegrationTest
// 장바구니·주문·리뷰는 구매자, 이의 제기·동기화 API는 내부 진입점이다
@WithMockUser(roles = {"USER", "INTERNAL"})
@RecordApplicationEvents
class LectureSyncCartReviewEventTest {

    private static final Long BUYER_ID = OTHER_USER_ID;
    private static final Long INQUIRY_ID = 500L;
    private static final String REASON = "강의 소개와 영상 내용이 다릅니다.";

    @Autowired
    private CartFacade cartFacade;

    @Autowired
    private LectureOrderFacade lectureOrderFacade;

    @Autowired
    private LectureReviewFacade lectureReviewFacade;

    @Autowired
    private LectureObjectionFacade lectureObjectionFacade;

    @Autowired
    private LectureSyncInternalFacade lectureSyncInternalFacade;

    @Autowired
    private LectureHpTaskBatchUseCase lectureHpTaskBatchUseCase;

    @Autowired
    private LectureRepository lectureRepository;

    @Autowired
    private LectureReviewRepository lectureReviewRepository;

    @Autowired
    private FakeBillingApiClient billing;

    @Autowired
    private ApplicationEvents events;

    private ObjectionCommands objections;

    @BeforeEach
    void setUp() {
        billing.reset();
        billing.setSettledHp(PRICE_HP);
        objections = new ObjectionCommands(lectureObjectionFacade);
    }

    @Test
    void 장바구니에_담으면_회원_ID와_담은_시각으로_나간다() {
        Lecture lecture = saveOnSale("OBS 방송 세팅");

        cartFacade.addItem(BUYER_ID, lecture.getId());

        CartItemSyncDto event = lastCartItemEvent();
        assertThat(event).isEqualTo(cartItemFromApi(event.id()));
        assertThat(event.userId()).isEqualTo(BUYER_ID);
        assertThat(event.lectureId()).isEqualTo(lecture.getId());
        assertThat(event.deleted()).isFalse();
        assertThat(event.addedAt()).isNotNull();
        assertThat(event.version()).isZero();
    }

    @Test
    void 장바구니에서_빼면_deleted_true와_오른_version으로_나가고_담지_않은_강의는_보내지_않는다() {
        Lecture lecture = saveOnSale("OBS 방송 세팅");
        cartFacade.addItem(BUYER_ID, lecture.getId());

        cartFacade.removeItem(BUYER_ID, lecture.getId());
        cartFacade.removeItem(BUYER_ID, lecture.getId());

        assertThat(events.stream(CartItemChangedEvent.class)).hasSize(2);
        CartItemSyncDto event = lastCartItemEvent();
        assertThat(event).isEqualTo(cartItemFromApi(event.id()));
        assertThat(event.deleted()).isTrue();
        assertThat(event.version()).isEqualTo(1);
    }

    @Test
    void 결제가_끝나_장바구니에서_자동으로_빠진_항목도_deleted_true로_나간다() {
        Lecture ordered = saveOnSale("OBS 방송 세팅");
        Lecture kept = saveOnSale("마이크 고르기");
        cartFacade.addItem(BUYER_ID, ordered.getId());
        cartFacade.addItem(BUYER_ID, kept.getId());

        order(ordered);

        List<CartItemSyncDto> cartItems = events.stream(CartItemChangedEvent.class)
                .map(CartItemChangedEvent::cartItem).toList();
        assertThat(cartItems).hasSize(3);
        CartItemSyncDto removed = cartItems.getLast();
        assertThat(removed.lectureId()).isEqualTo(ordered.getId());
        assertThat(removed.deleted()).isTrue();
        assertThat(removed).isEqualTo(cartItemFromApi(removed.id()));
    }

    @Test
    void 리뷰를_쓰면_counted_true로_나간다() {
        Lecture lecture = saveOnSale("OBS 방송 세팅");
        Long itemId = confirmedItem(lecture);

        Long reviewId = lectureReviewFacade.write(BUYER_ID, lecture.getId(), itemId, 5, "좋아요");

        LectureReviewSyncDto event = lastReviewEvent();
        assertThat(event).isEqualTo(reviewFromApi(reviewId));
        assertThat(event).isEqualTo(new LectureReviewSyncDto(reviewId, lecture.getId(), BUYER_ID, 5, true, false, 0));
    }

    @Test
    void 리뷰를_고치면_바뀐_별점과_오른_version으로_나간다() {
        Lecture lecture = saveOnSale("OBS 방송 세팅");
        Long reviewId = lectureReviewFacade.write(BUYER_ID, lecture.getId(), confirmedItem(lecture), 5, "좋아요");

        lectureReviewFacade.edit(BUYER_ID, reviewId, 3, "다시 보니 보통이에요");

        LectureReviewSyncDto event = lastReviewEvent();
        assertThat(event).isEqualTo(reviewFromApi(reviewId));
        assertThat(event.rating()).isEqualTo(3);
        assertThat(event.counted()).isTrue();
        assertThat(event.version()).isEqualTo(1);
    }

    @Test
    void 리뷰를_지우면_deleted_true_counted_false로_나간다() {
        Lecture lecture = saveOnSale("OBS 방송 세팅");
        Long reviewId = lectureReviewFacade.write(BUYER_ID, lecture.getId(), confirmedItem(lecture), 5, "좋아요");

        lectureReviewFacade.delete(BUYER_ID, reviewId);

        LectureReviewSyncDto event = lastReviewEvent();
        assertThat(event).isEqualTo(reviewFromApi(reviewId));
        assertThat(event.deleted()).isTrue();
        assertThat(event.counted()).isFalse();
        assertThat(event.version()).isEqualTo(1);
    }

    @Test
    void 이의_제기가_인정되면_그_항목의_리뷰가_counted_false로_version이_오른_채_다시_나가고_수정_시각은_그대로다() {
        Lecture lecture = saveOnSale("OBS 방송 세팅");
        Long itemId = confirmedItem(lecture);
        Long reviewId = lectureReviewFacade.write(BUYER_ID, lecture.getId(), itemId, 5, "좋아요");
        LocalDateTime updatedAt = lectureReviewRepository.findById(reviewId).orElseThrow().getUpdatedAt();
        objections.file(itemId, BUYER_ID, INQUIRY_ID, REASON);

        objections.resolve(itemId, INQUIRY_ID, true, ADMIN_ID);

        LectureReviewSyncDto event = lastReviewEvent();
        assertThat(event).isEqualTo(reviewFromApi(reviewId));
        assertThat(event.counted()).isFalse();
        assertThat(event.deleted()).isFalse();
        assertThat(event.version()).isEqualTo(1);
        assertThat(lectureReviewRepository.findById(reviewId).orElseThrow().getUpdatedAt()).isEqualTo(updatedAt);
    }

    @Test
    void 리뷰가_없는_항목의_이의_제기_인정과_기각은_리뷰_이벤트를_보내지_않는다() {
        Lecture lecture = saveOnSale("OBS 방송 세팅");
        Lecture other = saveOnSale("마이크 고르기");
        Long acceptedId = confirmedItem(lecture);
        Long rejectedId = confirmedItem(other);
        lectureReviewFacade.write(BUYER_ID, other.getId(), rejectedId, 5, "좋아요");
        objections.file(acceptedId, BUYER_ID, INQUIRY_ID, REASON);
        objections.file(rejectedId, BUYER_ID, INQUIRY_ID + 1, REASON);

        objections.resolve(acceptedId, INQUIRY_ID, true, ADMIN_ID);
        objections.resolve(rejectedId, INQUIRY_ID + 1, false, ADMIN_ID);

        // 리뷰 이벤트는 기각된 항목에 리뷰를 쓸 때 한 번뿐이다
        assertThat(events.stream(LectureReviewChangedEvent.class)).hasSize(1);
    }

    @Test
    void 반환된_항목의_리뷰를_새_리뷰로_바꾸면_기존_리뷰_삭제와_새_리뷰가_둘_다_나간다() {
        Lecture lecture = saveOnSale("OBS 방송 세팅");
        Long returnedItemId = confirmedItem(lecture);
        Long oldReviewId = lectureReviewFacade.write(BUYER_ID, lecture.getId(), returnedItemId, 5, "좋아요");
        objections.file(returnedItemId, BUYER_ID, INQUIRY_ID, REASON);
        objections.resolve(returnedItemId, INQUIRY_ID, true, ADMIN_ID);
        lectureHpTaskBatchUseCase.runDue();
        Long newItemId = confirmedItem(lecture);

        Long newReviewId = lectureReviewFacade.write(BUYER_ID, lecture.getId(), newItemId, 2, "다시 사 봤어요");

        List<LectureReviewSyncDto> reviews = events.stream(LectureReviewChangedEvent.class)
                .map(LectureReviewChangedEvent::review).toList();
        LectureReviewSyncDto deleted = reviews.get(reviews.size() - 2);
        LectureReviewSyncDto written = reviews.getLast();
        assertThat(deleted.id()).isEqualTo(oldReviewId);
        assertThat(deleted.deleted()).isTrue();
        assertThat(deleted.counted()).isFalse();
        assertThat(deleted).isEqualTo(reviewFromApi(oldReviewId));
        assertThat(written.id()).isEqualTo(newReviewId);
        assertThat(written.counted()).isTrue();
        assertThat(written).isEqualTo(reviewFromApi(newReviewId));
    }

    private Lecture saveOnSale(String title) {
        Lecture lecture = Lecture.register(SELLER_ID, title, DESCRIPTION, PRICE_HP, null, contentSpecs(), NOW);
        lecture.approve(ADMIN_ID, NOW);
        return lectureRepository.save(lecture);
    }

    private OrderResponse order(Lecture lecture) {
        return lectureOrderFacade.createOrder(BUYER_ID, List.of(lecture.getId()));
    }

    /** 사서 직접 구매 확정한 주문 항목. 리뷰를 쓸 수 있다 */
    private Long confirmedItem(Lecture lecture) {
        OrderResponse order = order(lecture);
        Long itemId = order.items().getFirst().orderItemId();
        lectureOrderFacade.confirm(BUYER_ID, order.orderId(), itemId);
        return itemId;
    }

    private CartItemSyncDto lastCartItemEvent() {
        return events.stream(CartItemChangedEvent.class).map(CartItemChangedEvent::cartItem)
                .reduce((first, second) -> second).orElseThrow();
    }

    private LectureReviewSyncDto lastReviewEvent() {
        return events.stream(LectureReviewChangedEvent.class).map(LectureReviewChangedEvent::review)
                .reduce((first, second) -> second).orElseThrow();
    }

    /** 커밋된 행을 동기화 내부 API와 같은 방법으로 읽는다 */
    private CartItemSyncDto cartItemFromApi(Long cartItemId) {
        return lectureSyncInternalFacade.getCartItems(cartItemId - 1, 1).items().getFirst();
    }

    private LectureReviewSyncDto reviewFromApi(Long reviewId) {
        return lectureSyncInternalFacade.getReviews(reviewId - 1, 1).items().getFirst();
    }
}
