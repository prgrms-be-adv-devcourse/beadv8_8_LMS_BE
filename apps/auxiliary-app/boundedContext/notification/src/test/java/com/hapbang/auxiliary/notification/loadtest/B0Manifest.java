package com.hapbang.auxiliary.notification.loadtest;

import java.util.List;
import java.util.Map;

/**
 * B0 기대값(manifest). 같은 seed면 같다. 결과 검증(V0~V8)이 이 값과 실측을 비교하고, 실측에 맞춰 고치지 않는다.
 *
 * @param recordsByTopic        토픽별 투입 레코드 수(회원 포함)
 * @param partitionSequences    토픽 → 파티션 → 파일 순서대로의 레코드 digest(V0-2)
 * @param outcomes              토픽 → 저장 결과(created·updated·duplicate·skipped_receiver) → 건수(V2)
 * @param deadLetters           DLT 이유(예외 짧은 이름) → 건수(V3)
 * @param failures              처리 실패 시도 예외 → 건수(V8, 재시도 포함)
 * @param legacySkipped         LEGACY 건너뜀 수(V4)
 * @param notAlertSkipped       알림 대상이 아닌 이벤트 수(알림·DLT 없음)
 * @param schedules             예약 종류 → 상태(SCHEDULED·CANCELED) → 행 수(V7)
 * @param notificationRows      알림 행 수(Soft Delete 포함, V1)
 * @param rowsByReceiver        받을 수 있는 받는 사람 → 저장된 행 수(V6)
 * @param inactiveReceivers     받을 수 없는 회원(정지·탈퇴) ID. 행 0이어야 한다(V6)
 * @param replicas              회원 복제본 수(단계 장벽)
 * @param orderedPairs          같은 토픽·같은 key의 순서 의존 쌍(V0-3)
 * @param orderIndependentPairs 다른 key라 처리 순서가 정해지지 않지만 결과가 같아야 하는 쌍
 * @param coverage              리스너 분기·예외 범위표 → 건수(G3)
 * @param phaseRecordsByTopic   투입 단계(normal·failure) → 토픽 → 알림 레코드 수(ND-166). 회원은 넣지 않는다
 */
public record B0Manifest(
        long seed,
        Map<String, Integer> recordsByTopic,
        Map<String, Map<Integer, List<String>>> partitionSequences,
        Map<String, Map<String, Integer>> outcomes,
        Map<String, Integer> deadLetters,
        Map<String, Integer> failures,
        int legacySkipped,
        int notAlertSkipped,
        Map<String, Map<String, Integer>> schedules,
        int notificationRows,
        Map<Long, Integer> rowsByReceiver,
        List<Long> inactiveReceivers,
        int replicas,
        List<OrderedPair> orderedPairs,
        List<OrderIndependentPair> orderIndependentPairs,
        Map<B0Branch, Integer> coverage,
        Map<String, Map<String, Integer>> phaseRecordsByTopic) {

    /** 같은 토픽·같은 key, 파일에서 앞(first) → 뒤(second). index는 알림 레코드 목록의 위치 */
    public record OrderedPair(String relation, String firstTopic, String firstKey, int firstIndex,
                              String secondTopic, String secondKey, int secondIndex) {
    }

    public record OrderIndependentPair(String relation, String firstTopic, String firstKey, String secondTopic,
                                       String secondKey) {
    }
}
