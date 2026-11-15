package com.hapbang.shared.lecture.event;

/**
 * 강의 이벤트 Kafka 토픽 (#278, ADR 0001). 강의 앱이 내보내는 이벤트 타입을 이 토픽 하나에 섞는다.
 * key는 대상 종류 접두어와 ID다: {@code order-item:<주문 항목 ID>}(이의 제기·구매), {@code lecture:<강의 ID>},
 * {@code cart-item:<장바구니 항목 ID>}, {@code lecture-review:<리뷰 ID>}(#712), {@code order:<주문 ID>}(결제 완료·실패, #872).
 * 토픽·key·헤더 규칙은
 * docs/architecture/infrastructure/kafka.md를 따른다.
 */
public final class LectureEventTopic {

    public static final String NAME = "lecture.events.v1";

    private LectureEventTopic() {
    }
}
