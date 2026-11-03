package com.hapbang.chat.app;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.chat.domain.ChatMessage;
import com.hapbang.chat.domain.ChatMessageType;
import com.hapbang.chat.out.ChatMessageRepository;
import com.hapbang.chat.out.ChatReadCountRows.MessageUnreadRow;
import com.hapbang.chat.out.ChatReadCountRows.RecentUnreadRow;

import lombok.RequiredArgsConstructor;

/**
 * 메시지별 안 읽은 사람 수를 센다(설계 3.5, Q36). 메시지 × 참여자 읽음 행을 두지 않고 현재 참여자의 누적 읽음 순번으로 센다.
 * <ul>
 *     <li>센 값과 버전(센 문장의 DB 시각, epoch 마이크로초)을 한 문장으로 읽는다. 페이지·묶음마다 집계 문장 하나다(메시지마다 참여자를
 *     읽지 않는다).</li>
 *     <li>값은 줄기만 한다: 읽음 순번은 오르기만 하고, 나가기·강퇴는 사람을 빼고, 늦은 초대·재입장은 가시 범위가 이전 메시지 뒤다.</li>
 * </ul>
 * 계약: {@code apps/chat-app/docs/contracts/unread-count.md}
 */
@Component
@RequiredArgsConstructor
public class ChatUnreadMemberCounter {

    private final ChatMessageRepository chatMessageRepository;

    /**
     * 주어진 메시지 중 일반 메시지의 안 읽은 사람 수. 일반 메시지가 없으면 세지 않고 지금 DB 시각만 버전으로 읽는다.
     * 부르는 쪽의 트랜잭션에 참여한다.
     */
    @Transactional(readOnly = true)
    public MessageCounts countMessages(Collection<ChatMessage> messages) {
        List<Long> textIds = messages.stream()
                .filter(message -> message.getMessageType() == ChatMessageType.TEXT)
                .map(ChatMessage::getId)
                .toList();
        if (textIds.isEmpty()) {
            return new MessageCounts(Map.of(), chatMessageRepository.currentReadCountVersion());
        }
        List<MessageUnreadRow> rows = chatMessageRepository.countUnreadMembers(textIds);
        if (rows.isEmpty()) {
            return new MessageCounts(Map.of(), chatMessageRepository.currentReadCountVersion());
        }
        Map<Long, Integer> counts = new HashMap<>();
        for (MessageUnreadRow row : rows) {
            counts.put(row.getMessageId(), Math.toIntExact(row.getUnreadMemberCount()));
        }
        return new MessageCounts(counts, rows.getFirst().getReadCountVersion());
    }

    /**
     * 방의 마지막 {@code window}개 순번 안의 일반 메시지별 안 읽은 사람 수와, 같은 스냅샷의 방 현재 수신자 집합 버전. 방이 없거나
     * 참여자가 볼 수 없는 방(종료·조회 만료, {@code now} 기준)이면 빈 값.
     */
    @Transactional(readOnly = true)
    public Optional<RecentCounts> countRecent(Long roomId, int window, LocalDateTime now) {
        List<RecentUnreadRow> rows = chatMessageRepository.countRecentUnreadMembers(roomId, window, now);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        RecentUnreadRow first = rows.getFirst();
        Map<Long, Integer> counts = new HashMap<>();
        for (RecentUnreadRow row : rows) {
            if (row.getRoomSequence() != null) {
                counts.put(row.getRoomSequence(), Math.toIntExact(row.getUnreadMemberCount()));
            }
        }
        return Optional.of(new RecentCounts(first.getFromSequence(), first.getToSequence(),
                first.getRecipientSetVersion(), counts, first.getReadCountVersion()));
    }

    /**
     * @param byMessageId 일반 메시지 ID → 안 읽은 사람 수
     * @param version     센 시점(DB 시각, epoch 마이크로초)
     */
    public record MessageCounts(Map<Long, Integer> byMessageId, long version) {

        /** 그 메시지의 값. 일반 메시지가 아니거나 세지 않았으면 null */
        public Integer of(Long messageId) {
            return byMessageId.get(messageId);
        }
    }

    /**
     * @param fromSequence 센 구간의 첫 순번
     * @param toSequence          센 구간의 끝 순번(같은 스냅샷의 방 마지막 순번). 구간이 비면 {@code fromSequence}보다 작다
     * @param recipientSetVersion 같은 스냅샷의 방 현재 수신자 집합 버전. 받는 구독은 이 버전 이상을 반영한 참여 상태로 고른다
     * @param bySequence          구간 안의 일반 메시지 순번 → 안 읽은 사람 수(0 포함)
     * @param version             센 시점(DB 시각, epoch 마이크로초)
     */
    public record RecentCounts(long fromSequence, long toSequence, int recipientSetVersion,
            Map<Long, Integer> bySequence, long version) {
    }
}
