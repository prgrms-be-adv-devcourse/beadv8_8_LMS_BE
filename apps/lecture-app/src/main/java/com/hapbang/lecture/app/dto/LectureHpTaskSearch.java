package com.hapbang.lecture.app.dto;

import com.hapbang.lecture.domain.LectureHpTaskStatus;
import com.hapbang.lecture.domain.LectureHpTaskType;
import com.hapbang.shared.billing.domain.BillingReferenceType;

/**
 * 관리자 HP 작업 목록 필터 (reference.md 8.2.1). 모두 선택이다.
 *
 * @param referenceType 지갑 참조 유형. 강의 작업은 항상 {@code LECTURE_ORDER_ITEM}이라 다른 값이면 결과가 없다
 * @param errorCode     마지막 실패 코드
 */
public record LectureHpTaskSearch(LectureHpTaskStatus status, LectureHpTaskType taskType,
                                  BillingReferenceType referenceType, Long orderId, Long orderItemId,
                                  String errorCode) {
}
