package com.hapbang.chat.app;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 모집용 단체방 설정 ({@code hapbang.chat.group.*}, 설계 3.1·Q34). 수치는 모두 부하 실측(Q12) 전의 코드 기본값이며 확정값이 아니다.
 * 기본값은 이 코드에 둔다(application.yaml에 두지 않는다). 같은 키의 yaml·환경 변수가 덮는다.
 *
 * @param maxGuestMembers        새 방의 게스트 정원(호스트 제외). 방을 만들 때 방에 저장하므로 바꿔도 기존 방에는 작용하지 않는다.
 *                               기본 500(시험 후보 상한)
 * @param inviteMaxUsers         초대 요청 한 번의 최대 회원 수(중복 제거 뒤). 넘으면 400. 기본 50
 * @param inviteRequestRetention 같은 초대 요청 ID의 결과를 보존하는 기간. 기본 24시간. 지난 결과 기록은 정리 배치가 지운다
 * @param historyExpiryBatchSize 조회 만료 배치가 한 번에 읽는 방 수(방 ID 키셋). 방마다 따로 잠그고 커밋한다. 기본 100
 * @param inviteRequestCleanupBatchSize 초대 결과 기록 정리 배치가 한 트랜잭션에서 지우는 행 수. 기본 500
 */
@ConfigurationProperties("hapbang.chat.group")
public record ChatGroupProperties(Integer maxGuestMembers, Integer inviteMaxUsers, Duration inviteRequestRetention,
        Integer historyExpiryBatchSize, Integer inviteRequestCleanupBatchSize) {

    public static final int DEFAULT_MAX_GUEST_MEMBERS = 500;
    public static final int DEFAULT_INVITE_MAX_USERS = 50;
    public static final Duration DEFAULT_INVITE_REQUEST_RETENTION = Duration.ofHours(24);
    public static final int DEFAULT_HISTORY_EXPIRY_BATCH_SIZE = 100;
    public static final int DEFAULT_INVITE_REQUEST_CLEANUP_BATCH_SIZE = 500;

    public ChatGroupProperties {
        maxGuestMembers = maxGuestMembers == null ? DEFAULT_MAX_GUEST_MEMBERS : maxGuestMembers;
        inviteMaxUsers = inviteMaxUsers == null ? DEFAULT_INVITE_MAX_USERS : inviteMaxUsers;
        inviteRequestRetention = inviteRequestRetention == null ? DEFAULT_INVITE_REQUEST_RETENTION
                : inviteRequestRetention;
        historyExpiryBatchSize = historyExpiryBatchSize == null ? DEFAULT_HISTORY_EXPIRY_BATCH_SIZE
                : historyExpiryBatchSize;
        inviteRequestCleanupBatchSize = inviteRequestCleanupBatchSize == null
                ? DEFAULT_INVITE_REQUEST_CLEANUP_BATCH_SIZE : inviteRequestCleanupBatchSize;
        if (maxGuestMembers < 1) {
            throw new IllegalArgumentException("hapbang.chat.group.max-guest-members는 1 이상이어야 합니다.");
        }
        if (inviteMaxUsers < 1) {
            throw new IllegalArgumentException("hapbang.chat.group.invite-max-users는 1 이상이어야 합니다.");
        }
        if (!inviteRequestRetention.isPositive()) {
            throw new IllegalArgumentException("hapbang.chat.group.invite-request-retention은 0보다 커야 합니다.");
        }
        if (historyExpiryBatchSize < 1) {
            throw new IllegalArgumentException("hapbang.chat.group.history-expiry-batch-size는 1 이상이어야 합니다.");
        }
        if (inviteRequestCleanupBatchSize < 1) {
            throw new IllegalArgumentException(
                    "hapbang.chat.group.invite-request-cleanup-batch-size는 1 이상이어야 합니다.");
        }
    }
}
