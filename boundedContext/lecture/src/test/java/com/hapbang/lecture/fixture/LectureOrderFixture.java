package com.hapbang.lecture.fixture;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureOrder;

/**
 * 주문 테스트 공용 데이터. HP 보류 없이 주문을 만들어 저장한다. 구매 시각을 정할 수 있어 기한이 지난 주문을 만들 때 쓴다.
 * HP 보류·반환까지 확인하려면 주문 생성 API(Facade)로 만든다.
 */
public final class LectureOrderFixture {

    private LectureOrderFixture() {
    }

    /** {@code purchasedAt}에 산 주문. 지난 시각을 넣으면 기한이 지난 주문을 만들 수 있다 */
    public static LectureOrder order(Long buyerUserId, List<Lecture> lectures, LocalDateTime purchasedAt) {
        return LectureOrder.place(buyerUserId, lectures, Set.of(), purchasedAt);
    }
}
