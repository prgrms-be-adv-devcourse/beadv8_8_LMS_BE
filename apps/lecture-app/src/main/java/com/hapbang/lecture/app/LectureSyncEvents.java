package com.hapbang.lecture.app;

import java.util.Collection;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.lecture.domain.CartItem;
import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureReview;
import com.hapbang.lecture.domain.OrderItem;
import com.hapbang.lecture.domain.OrderItemStatus;
import com.hapbang.lecture.out.LectureOrderRepository;
import com.hapbang.lecture.out.LectureReviewRepository;
import com.hapbang.shared.lecture.event.CartItemChangedEvent;
import com.hapbang.shared.lecture.event.LectureChangedEvent;
import com.hapbang.shared.lecture.event.LecturePurchaseChangedEvent;
import com.hapbang.shared.lecture.event.LectureReviewChangedEvent;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

/**
 * AI 추천용 동기화 이벤트를 발행한다 (#712, D82). 업무 트랜잭션 안에서, 대상을 바꾼 뒤에 부른다.
 * <ul>
 *     <li>먼저 flush한다. {@code @Version}은 flush 때 오르므로, 그 전에 DTO를 만들면 오르기 전 버전이 실려 받는 쪽이 오래된 이벤트로 버린다.
 *     그래서 이벤트 버전이 커밋된 행의 버전과 같으려면 대상을 바꾸는 일이 모두 끝난 뒤에 불러야 한다.</li>
 *     <li>DTO는 엔티티의 {@code toSyncDto}가 만든다. 동기화 내부 API도 같은 메서드를 써서 같은 버전이면 같은 값이 된다.</li>
 *     <li>발행 기록은 Modulith 발행 기록에 남고 커밋 뒤 Kafka로 간다. 롤백되면 보내지 않는다.</li>
 * </ul>
 */
@Component
public class LectureSyncEvents {

    private final EntityManager entityManager;
    private final ApplicationEventPublisher eventPublisher;
    private final LectureOrderRepository lectureOrderRepository;
    private final LectureReviewRepository lectureReviewRepository;

    public LectureSyncEvents(EntityManager entityManager, ApplicationEventPublisher eventPublisher,
                             LectureOrderRepository lectureOrderRepository,
                             LectureReviewRepository lectureReviewRepository) {
        this.entityManager = entityManager;
        this.eventPublisher = eventPublisher;
        this.lectureOrderRepository = lectureOrderRepository;
        this.lectureReviewRepository = lectureReviewRepository;
    }

    /** 강의를 등록하거나 바꿨다(정보, 콘텐츠, 검수, 삭제) */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lectureChanged(Lecture lecture) {
        entityManager.flush();
        eventPublisher.publishEvent(new LectureChangedEvent(lecture.toSyncDto()));
    }

    /** 구매 결과나 실린 값이 바뀌었다(주문, 결제 성공·실패, 구매 취소, 반환). 결과가 그대로인 전이(확정·지급)에서는 부르지 않는다 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void purchasesChanged(Collection<OrderItem> items) {
        entityManager.flush();
        items.forEach(item -> eventPublisher.publishEvent(new LecturePurchaseChangedEvent(item.toSyncDto())));
    }

    /** {@link #purchasesChanged}의 항목 하나 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void purchaseChanged(OrderItem item) {
        purchasesChanged(List.of(item));
    }

    /** 장바구니에 담았거나 뺐다 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void cartItemChanged(CartItem item) {
        entityManager.flush();
        eventPublisher.publishEvent(new CartItemChangedEvent(item.toSyncDto()));
    }

    /** 리뷰를 쓰거나 고치거나 지웠다. 평점 반영 여부는 리뷰를 쓴 주문 항목의 지금 상태로 정한다 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reviewChanged(LectureReview review) {
        entityManager.flush();
        OrderItemStatus itemStatus = lectureOrderRepository.findStatusById(review.getOrderItemId())
                .orElseThrow(() -> new IllegalStateException("리뷰의 주문 항목이 없습니다. reviewId=" + review.getId()
                        + ", orderItemId=" + review.getOrderItemId()));
        eventPublisher.publishEvent(new LectureReviewChangedEvent(review.toSyncDto(itemStatus)));
    }

    /**
     * 주문 항목 상태가 바뀌어 그 항목 리뷰의 평점 반영 여부가 바뀌었다(이의 제기 인정으로 반환 처리 중). 살아 있는 리뷰가 있을 때만 다시 보낸다.
     * 평점 반영 여부는 컬럼이 아니라 버전이 저절로 오르지 않으므로, 리뷰 행을 잠그며 버전을 바로 올린다
     * ({@code PESSIMISTIC_FORCE_INCREMENT}). 커밋 직전에 올리는 낙관적 강제 증가는 이벤트에 오르기 전 버전이 실려 쓰지 않는다.
     * 수정 시각은 화면에 보이므로 바꾸지 않는다
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reviewCountChanged(OrderItem item) {
        entityManager.flush();
        lectureReviewRepository.findAliveIdByOrderItemId(item.getId()).ifPresent(reviewId -> {
            LectureReview review = entityManager.find(LectureReview.class, reviewId,
                    LockModeType.PESSIMISTIC_FORCE_INCREMENT);
            eventPublisher.publishEvent(new LectureReviewChangedEvent(review.toSyncDto(item.getStatus())));
        });
    }
}
