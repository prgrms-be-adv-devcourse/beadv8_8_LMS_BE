package com.hapbang.chat.app;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.chat.domain.ChatRoom;
import com.hapbang.chat.domain.ChatRoomEventType;
import com.hapbang.chat.domain.ChatRoomLog;
import com.hapbang.chat.out.ChatRoomLogRepository;
import com.hapbang.chat.out.ChatRoomRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 조회 만료 시각이 지난 읽기 전용 단체방을 {@code EXPIRED}로 바꾸고 방 기록(조회 만료)을 남긴다(설계 3.2, 정책 8장 "조회 기간 만료").
 * <ul>
 *     <li>참여자 노출 차단은 조회 때 만료 시각으로 이미 하고 있다. 이 처리는 저장한 상태와 기록을 맞출 뿐이라, 늦거나 실패해도
 *     참여자에게 기록이 보이지 않는다.</li>
 *     <li>메시지·참여 행·기록은 지우지 않는다(관리자 조회용으로 보존). 참여자에게 보이지 않는 방이라 안내 메시지·전달 작업도 만들지 않는다.</li>
 *     <li>대상 방 ID를 방 ID 순서의 키셋으로 한 묶음씩 읽고(잠그지 않음), 방마다 짧은 트랜잭션에서 방을 잠근 뒤 읽기 전용이고 만료
 *     시각이 지났는지 다시 확인하고 바꾼다. 그래서 회원 동작·다른 실행과 겹쳐도 한 번만 바뀌고, 다시 실행해도 결과가 같다.</li>
 *     <li>한 방의 실패는 그 방만 롤백하고 다음 방을 계속한다. 남은 방은 다음 실행이 처리한다.</li>
 * </ul>
 */
@Slf4j
@Service
public class ChatGroupHistoryExpiryService {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatRoomLogRepository chatRoomLogRepository;
    private final TransactionTemplate roomTransaction;
    private final int batchSize;

    public ChatGroupHistoryExpiryService(ChatRoomRepository chatRoomRepository,
            ChatRoomLogRepository chatRoomLogRepository, PlatformTransactionManager transactionManager,
            ChatGroupProperties properties) {
        this.chatRoomRepository = chatRoomRepository;
        this.chatRoomLogRepository = chatRoomLogRepository;
        this.roomTransaction = new TransactionTemplate(transactionManager);
        this.batchSize = properties.historyExpiryBatchSize();
    }

    /**
     * @param now 기준 시각. 만료 시각이 이 시각 이하인 방이 대상이다(그 시각 포함)
     * @return 이번 실행에서 {@code EXPIRED}로 바꾼 방 수
     */
    public int expireDue(LocalDateTime now) {
        int expired = 0;
        int failed = 0;
        long afterId = 0L;
        while (true) {
            List<Long> roomIds = chatRoomRepository.findHistoryExpiryDueIds(now, afterId, Limit.of(batchSize));
            for (Long roomId : roomIds) {
                try {
                    if (Boolean.TRUE.equals(roomTransaction.execute(status -> expire(roomId, now)))) {
                        expired++;
                    }
                } catch (RuntimeException e) {
                    failed++;
                    log.warn("단체방 조회 만료 처리 실패 chatRoomId={}", roomId, e);
                }
            }
            if (roomIds.size() < batchSize) {
                break;
            }
            afterId = roomIds.getLast();
        }
        if (expired > 0 || failed > 0) {
            log.info("단체방 조회 만료 expired={}, failed={}", expired, failed);
        }
        return expired;
    }

    /** @return 이번에 바꿨으면 true. 그 사이 다른 실행이 바꿨거나 대상이 아니면 false */
    private boolean expire(Long roomId, LocalDateTime now) {
        ChatRoom room = chatRoomRepository.findByIdForUpdate(roomId).orElse(null);
        if (room == null || !room.isHistoryExpiryDue(now)) {
            return false;
        }
        room.expireHistory(now);
        // 일어난 시각은 참여자가 볼 수 없게 된 조회 만료 시각이다(배치가 늦어도 관리자 기록의 시각이 밀리지 않는다)
        chatRoomLogRepository.save(ChatRoomLog.record(room, room.getOwnerUserId(), ChatRoomEventType.HISTORY_EXPIRED,
                room.getHistoryExpiresAt()));
        return true;
    }
}
