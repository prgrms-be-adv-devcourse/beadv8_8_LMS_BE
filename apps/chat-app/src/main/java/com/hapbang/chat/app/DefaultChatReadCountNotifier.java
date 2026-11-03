package com.hapbang.chat.app;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import com.hapbang.chat.app.ChatRecipientStateReader.RoomMembership;
import com.hapbang.chat.app.ChatUnreadMemberCounter.RecentCounts;
import com.hapbang.chat.app.LocalChatSender.RoomTarget;
import com.hapbang.chat.app.LocalRoomSubscriptions.Subscription;
import com.hapbang.chat.app.dto.ChatReadCounts;
import com.hapbang.chat.app.dto.ChatRecipientState;
import com.hapbang.chat.app.dto.ReadChangedFanout;

import lombok.extern.slf4j.Slf4j;

/**
 * 안 읽은 사람 수 갱신을 방별로 묶어 방 topic에 보낸다(설계 Q36).
 * <ul>
 *     <li>변경 신호는 그 방을 연 로컬 구독이 있을 때만 표시한다. 같은 방의 신호는 묶음 주기(초기값 1초) 동안 하나로 합친다. 개별 읽음을
 *     방 전체에 다시 보내지 않는다.</li>
 *     <li>묶음마다 표시된 방 하나에 집계 문장 하나로 마지막 {@code window}개 순번(초기값 100)의 일반 메시지를 센다. 같은 문장이 방의
 *     현재 수신자 집합 버전도 읽는다. 참여자가 볼 수 없는 방(종료·조회 만료)은 세지 않고 보내지 않는다.</li>
 *     <li>받는 구독은 센 뒤에 메시지 전달과 같은 규칙으로 고른다: 방 참여 캐시(센 스냅샷의 수신자 집합 버전과 신호의 가장 높은 버전
 *     이상)에서 구독 세대 == 현재 세대·활성 참여·채팅 가능. 그래서 센 값에 들어간 참여 변경(강퇴 뒤 새 메시지 등)을 받는 쪽도 반드시
 *     본다. 구독 회원의 가시 범위 앞 순번은 싣지 않는다. 맞지 않는 구독의 회수는 메시지 전달이 한다.</li>
 *     <li>읽기 전용 방도 조회 만료 전까지 같다(Q33).</li>
 *     <li>최선 노력이다. 조회에 실패하면 그 방의 묶음을 버리고 FE가 REST 이력으로 보정한다.</li>
 * </ul>
 */
@Slf4j
@Component
public class DefaultChatReadCountNotifier implements ChatReadCountNotifier {

    private final LocalRoomSubscriptions localRoomSubscriptions;
    private final ChatRoomMembershipCache chatRoomMembershipCache;
    private final ChatUnreadMemberCounter chatUnreadMemberCounter;
    private final LocalChatSender localChatSender;
    private final int window;
    /** 다시 셀 방 → 묶음 안 신호의 가장 높은 수신자 집합 버전 */
    private final Map<Long, Integer> dirty = new ConcurrentHashMap<>();

    /** 다시 세는 순번 구간 크기(100)는 미확정 운영 수치(Q12)다. 기본값은 코드가 원본이고 같은 키의 yaml·환경 변수가 덮는다 */
    public DefaultChatReadCountNotifier(LocalRoomSubscriptions localRoomSubscriptions,
            ChatRoomMembershipCache chatRoomMembershipCache, ChatUnreadMemberCounter chatUnreadMemberCounter,
            LocalChatSender localChatSender,
            @Value("${hapbang.chat.realtime.read-counts.window:100}") int window) {
        Assert.isTrue(window > 0, "hapbang.chat.realtime.read-counts.window는 1 이상이어야 합니다.");
        this.localRoomSubscriptions = localRoomSubscriptions;
        this.chatRoomMembershipCache = chatRoomMembershipCache;
        this.chatUnreadMemberCounter = chatUnreadMemberCounter;
        this.localChatSender = localChatSender;
        this.window = window;
    }

    @Override
    public void record(ReadChangedFanout readChanged) {
        if (readChanged.roomId() == null || localRoomSubscriptions.subscriptions(readChanged.roomId()).isEmpty()) {
            return;
        }
        dirty.merge(readChanged.roomId(), readChanged.recipientSetVersion(), Math::max);
    }

    @Override
    public void flush() {
        for (Long roomId : new ArrayList<>(dirty.keySet())) {
            Integer minSetVersion = dirty.remove(roomId);
            if (minSetVersion == null) {
                continue;
            }
            try {
                send(roomId, minSetVersion);
            } catch (RuntimeException exception) {
                log.warn("안 읽은 사람 수 갱신을 보내지 못해 버림 roomId={}", roomId, exception);
            }
        }
    }

    private void send(Long roomId, int minSetVersion) {
        if (localRoomSubscriptions.subscriptions(roomId).isEmpty()) {
            return;
        }
        Optional<RecentCounts> found = chatUnreadMemberCounter.countRecent(roomId, window, LocalDateTime.now());
        if (found.isEmpty()) {
            // 없거나 참여자가 볼 수 없게 된 방이다(종료·조회 만료)
            return;
        }
        RecentCounts counts = found.get();
        // 참여 상태는 센 스냅샷 이상으로 맞춘 뒤 읽고, 구독도 그 뒤에 고른다(센 값에 들어간 참여 변경을 받는 쪽도 본다)
        RoomMembership membership = chatRoomMembershipCache.get(roomId,
                Math.max(minSetVersion, counts.recipientSetVersion()));
        List<Subscription> subscriptions = localRoomSubscriptions.subscriptions(roomId);
        // 같은 가시 범위의 구독끼리 묶어 payload를 한 번만 만든다
        Map<Long, List<RoomTarget>> targetsByFrom = new LinkedHashMap<>();
        for (Subscription subscription : subscriptions) {
            ChatRecipientState state = membership.members().get(subscription.userId());
            if (state == null || !state.activeParticipant() || !state.canChat()
                    || state.participationVersion() != subscription.participationVersion()) {
                continue;
            }
            long from = Math.max(counts.fromSequence(), state.visibleFromSequence());
            if (from > counts.toSequence()) {
                continue;
            }
            targetsByFrom.computeIfAbsent(from, ignored -> new ArrayList<>())
                    .add(new RoomTarget(subscription.sessionId(), subscription.subscriptionId(),
                            subscription.participationVersion()));
        }
        targetsByFrom.forEach((from, targets) -> localChatSender.sendReadCounts(roomId,
                new ChatReadCounts(roomId, counts.version(), from, counts.toSequence(), nonZeroFrom(counts, from)),
                targets));
    }

    private static List<ChatReadCounts.Count> nonZeroFrom(RecentCounts counts, long from) {
        return counts.bySequence().entrySet().stream()
                .filter(entry -> entry.getKey() >= from && entry.getValue() > 0)
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new ChatReadCounts.Count(entry.getKey(), entry.getValue()))
                .toList();
    }
}
