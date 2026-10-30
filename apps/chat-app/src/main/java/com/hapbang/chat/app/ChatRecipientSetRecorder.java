package com.hapbang.chat.app;

import java.time.LocalDateTime;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.chat.domain.ChatRecipientSet;
import com.hapbang.chat.domain.ChatRoom;
import com.hapbang.chat.out.ChatRecipientSetMemberRepository;
import com.hapbang.chat.out.ChatRecipientSetRepository;
import com.hapbang.chat.out.ChatRoomMemberRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 참여 변경 뒤 불변 수신자 집합의 새 버전을 만들고 방의 현재 버전·활성 참여자 수를 바꾼다 (설계 4.6).
 * <ul>
 *     <li>방을 잠근 호출자 트랜잭션 안에서만 쓴다(MANDATORY). 참여 행·집계·새 집합·현재 포인터가 함께 커밋·롤백된다.</li>
 *     <li>항목은 같은 트랜잭션의 참여 행 변경을 반영한 활성 참여 행을 DB 안에서 복사한다. 기존 버전의 항목은 고치지 않는다.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatRecipientSetRecorder {

    private final ChatRecipientSetRepository chatRecipientSetRepository;
    private final ChatRecipientSetMemberRepository chatRecipientSetMemberRepository;
    private final ChatRoomMemberRepository chatRoomMemberRepository;

    /**
     * 지금의 활성 참여로 새 집합 버전을 만들고 방의 현재 버전으로 삼는다.
     *
     * @param chatRoom 이 트랜잭션에서 잠갔거나 새로 만든 방
     * @return 새 집합
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ChatRecipientSet snapshot(ChatRoom chatRoom, LocalDateTime now) {
        Long chatRoomId = chatRoom.getId();
        int memberCount = Math.toIntExact(chatRoomMemberRepository.countActiveMembers(chatRoomId));
        int version = chatRecipientSetRepository.findLastVersion(chatRoomId) + 1;
        ChatRecipientSet recipientSet = chatRecipientSetRepository.save(
                ChatRecipientSet.create(chatRoomId, version, memberCount, now));
        int copied = chatRecipientSetMemberRepository.copyActiveMembers(recipientSet.getId(), chatRoomId);
        if (copied != memberCount) {
            // 방 잠금 아래에서는 일어나지 않는다. 일어나면 집합이 참여 행과 어긋나므로 전체를 롤백한다
            throw new IllegalStateException("수신자 집합 항목 수가 활성 참여자 수와 다릅니다. chatRoomId=" + chatRoomId
                    + ", expected=" + memberCount + ", copied=" + copied);
        }
        chatRoom.applyRecipientSet(recipientSet.getId(), memberCount, now);
        log.debug("수신자 집합 갱신 chatRoomId={}, recipientSetId={}, version={}, memberCount={}", chatRoomId,
                recipientSet.getId(), version, memberCount);
        return recipientSet;
    }
}
