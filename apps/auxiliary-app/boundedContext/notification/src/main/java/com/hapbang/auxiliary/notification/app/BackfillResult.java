package com.hapbang.auxiliary.notification.app;

import java.util.List;

/**
 * 회원 복제본 Backfill 한 번의 결과(ND-077·ND-078). 일시 장애로 끝까지 읽지 못하면 결과 대신 예외로 끝나고 완료로 보지 않는다.
 *
 * @param scanned        읽은 회원 수
 * @param applied        새로 만들었거나 더 큰 버전으로 바꾼 수
 * @param unchanged      복제본이 같거나 더 큰 버전이거나 파기돼 그대로 둔 수
 * @param skipped        복제할 수 없는 회원 정보라 건너뛴 수
 * @param skippedUserIds 건너뛴 회원 ID
 */
public record BackfillResult(int scanned, int applied, int unchanged, int skipped, List<Long> skippedUserIds) {

    public BackfillResult {
        skippedUserIds = List.copyOf(skippedUserIds);
    }
}
