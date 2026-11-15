package com.hapbang.auxiliary.chatbot.in;

import java.util.Objects;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.chatbot.app.CopyApplier;
import com.hapbang.auxiliary.chatbot.in.kafka.ChatbotKafkaConfig;
import com.hapbang.auxiliary.chatbot.in.kafka.ChatbotKafkaSettings;
import com.hapbang.auxiliary.chatbot.out.CopyWrite;
import com.hapbang.shared.lecture.dto.CartItemSyncDto;
import com.hapbang.shared.lecture.dto.LecturePurchaseSyncDto;
import com.hapbang.shared.lecture.dto.LectureReviewSyncDto;
import com.hapbang.shared.lecture.dto.LectureSyncDto;
import com.hapbang.shared.lecture.event.CartItemChangedEvent;
import com.hapbang.shared.lecture.event.LectureChangedEvent;
import com.hapbang.shared.lecture.event.LecturePurchaseChangedEvent;
import com.hapbang.shared.lecture.event.LectureReviewChangedEvent;

import lombok.extern.slf4j.Slf4j;

/**
 * 강의 이벤트({@code lecture.events.v1})를 강의 사본 4종에 반영한다 (#717, 그룹 {@code auxiliary-app.chatbot-lecture-copy}).
 * <ul>
 *     <li>강의·구매·장바구니·리뷰 변경 4종만 쓴다. 이의 제기 이벤트 등 다른 타입은 기본 처리기가 건너뛴다.</li>
 *     <li>최소 한 번 전달·순서 보장 없음이라 적용기가 version으로 멱등 처리한다. 마스킹한 회원의 구매·장바구니·리뷰는 버린다.</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnBooleanProperty(name = ChatbotKafkaConfig.ENABLED, matchIfMissing = true)
@KafkaListener(id = "chatbot-lecture-copy", idIsGroup = false, topics = "#{__listener.topic}",
        groupId = "#{__listener.groupId}", containerFactory = ChatbotKafkaConfig.LECTURE_EVENT_CONTAINER_FACTORY)
public class LectureCopyEventListener {

    private final CopyApplier copyApplier;
    private final ChatbotKafkaSettings settings;

    public LectureCopyEventListener(CopyApplier copyApplier,
            @Qualifier(ChatbotKafkaConfig.LECTURE_EVENT_SETTINGS) ChatbotKafkaSettings settings) {
        this.copyApplier = copyApplier;
        this.settings = settings;
    }

    @KafkaHandler
    public void on(LectureChangedEvent event) {
        LectureSyncDto lecture = Objects.requireNonNull(event.lecture(), "lecture는 필수입니다.");
        CopyWrite write = copyApplier.apply(lecture, null);
        log.info("강의 변경 이벤트를 강의 사본에 반영했다 lectureId={}, version={}, ourVersion={}, outcome={}", lecture.id(),
                lecture.version(), write.ourVersion(), write.outcome());
    }

    @KafkaHandler
    public void on(LecturePurchaseChangedEvent event) {
        LecturePurchaseSyncDto purchase = Objects.requireNonNull(event.purchase(), "purchase는 필수입니다.");
        CopyWrite write = copyApplier.apply(purchase, null);
        log.info("구매 변경 이벤트를 구매 사본에 반영했다 orderItemId={}, version={}, ourVersion={}, outcome={}",
                purchase.orderItemId(), purchase.version(), write.ourVersion(), write.outcome());
    }

    @KafkaHandler
    public void on(CartItemChangedEvent event) {
        CartItemSyncDto cartItem = Objects.requireNonNull(event.cartItem(), "cartItem은 필수입니다.");
        CopyWrite write = copyApplier.apply(cartItem, null);
        log.info("장바구니 변경 이벤트를 장바구니 사본에 반영했다 cartItemId={}, version={}, ourVersion={}, outcome={}",
                cartItem.id(), cartItem.version(), write.ourVersion(), write.outcome());
    }

    @KafkaHandler
    public void on(LectureReviewChangedEvent event) {
        LectureReviewSyncDto review = Objects.requireNonNull(event.review(), "review는 필수입니다.");
        CopyWrite write = copyApplier.apply(review, null);
        log.info("강의 리뷰 변경 이벤트를 리뷰 사본에 반영했다 lectureReviewId={}, version={}, ourVersion={}, outcome={}",
                review.id(), review.version(), write.ourVersion(), write.outcome());
    }

    @KafkaHandler(isDefault = true)
    public void skip(Object event) {
        log.debug("챗봇이 쓰지 않는 강의 이벤트를 건너뛴다 type={}", event.getClass().getSimpleName());
    }

    public String getTopic() {
        return settings.topic();
    }

    public String getGroupId() {
        return settings.groupId();
    }
}
