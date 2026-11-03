package com.hapbang.chat.app;

import java.time.Duration;
import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import com.hapbang.chat.out.ChatGroupInviteRequestRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 보존 기간({@code hapbang.chat.group.invite-request-retention})이 지난 단체방 초대 결과 기록({@code chat_group_invite_request})을
 * 물리 삭제한다(설계 3.6 "1단계 구현 범위와 세부").
 * <ul>
 *     <li>이 행은 같은 요청 ID의 재시도를 가리는 기술 데이터다. 초대된 회원은 방 기록({@code MEMBER_INVITED})과 초대 안내 메시지에
 *     계속 남으므로, 지워도 업무 사실은 사라지지 않는다. 정책의 Soft Delete 규칙은 업무 데이터에 대한 것이다.</li>
 *     <li>지우는 기준은 {@code recordedAt <= 지금 − 보존 기간}이다. 초대가 보존 기간 안으로 보는 행({@code isRetained})은 지우지
 *     않는다.</li>
 *     <li>한 묶음씩 짧은 트랜잭션으로 지운다. 초대 트랜잭션이 잠근 행은 건너뛰고 다음 실행에서 지운다. 다시 실행해도 결과가 같다.</li>
 * </ul>
 */
@Slf4j
@Service
public class ChatGroupInviteRequestCleaner {

    private final ChatGroupInviteRequestRepository chatGroupInviteRequestRepository;
    private final Duration retention;
    private final int batchSize;

    public ChatGroupInviteRequestCleaner(ChatGroupInviteRequestRepository chatGroupInviteRequestRepository,
            ChatGroupProperties properties) {
        this.chatGroupInviteRequestRepository = chatGroupInviteRequestRepository;
        this.retention = properties.inviteRequestRetention();
        this.batchSize = properties.inviteRequestCleanupBatchSize();
    }

    /**
     * @param now 기준 시각
     * @return 지운 행 수
     */
    public int deleteExpired(LocalDateTime now) {
        LocalDateTime cutoff = now.minus(retention);
        int total = 0;
        while (true) {
            int deleted = chatGroupInviteRequestRepository.deleteExpired(cutoff, batchSize);
            total += deleted;
            if (deleted < batchSize) {
                break;
            }
        }
        if (total > 0) {
            log.info("단체방 초대 결과 기록 정리 cutoff={}, deleted={}", cutoff, total);
        }
        return total;
    }
}
