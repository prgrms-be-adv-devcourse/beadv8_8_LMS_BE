package com.hapbang.chat.app.dto;

/**
 * 필수 consumer group의 위치. 큐 정리의 안전 경계를 계산할 때 쓴다.
 *
 * @param lastDeliveredId 그룹에 마지막으로 배분한 항목 위치. 이후 항목은 아직 배분되지 않았다
 * @param pendingCount    확인(ACK)되지 않은 항목 수
 * @param minPendingId    확인되지 않은 항목 중 가장 앞선 위치. 없으면 null
 */
public record QueueGroupState(String group, String lastDeliveredId, long pendingCount, String minPendingId) {
}
