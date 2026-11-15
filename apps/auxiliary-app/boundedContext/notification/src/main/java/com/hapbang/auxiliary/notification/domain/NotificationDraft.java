package com.hapbang.auxiliary.notification.domain;

import java.time.Instant;
import java.util.Map;

/**
 * 알림 저장 서비스에 넘기는 알림 초안. 이벤트를 소비하는 작업(N5 이후)이 만든다.
 *
 * @param receiverUserId  받는 사람
 * @param kind            종류 코드. 같은 종류의 문구 템플릿이 있어야 한다(ND-050)
 * @param sourceEventType 원본 이벤트 종류(중복 판정, ND-019)
 * @param sourceTargetId  원본 대상 ID(ND-033·ND-045)
 * @param targetType      눌렀을 때 이동할 대상 종류
 * @param targetId        이동할 대상 ID
 * @param payload         값. 형식 버전 {@code v} 필수, 다른 회원 닉네임은 {@code nicknames}(ND-051)
 * @param occurredAt      발생 시각(정렬 기준, ND-021)
 */
public record NotificationDraft(
        Long receiverUserId,
        String kind,
        String sourceEventType,
        String sourceTargetId,
        String targetType,
        String targetId,
        Map<String, Object> payload,
        Instant occurredAt
) {
}
