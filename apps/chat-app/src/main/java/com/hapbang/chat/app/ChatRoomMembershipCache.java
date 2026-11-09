package com.hapbang.chat.app;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hapbang.chat.app.ChatRecipientStateReader.RoomMembership;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import lombok.extern.slf4j.Slf4j;

/**
 * 방 활성 참여자의 상태 캐시. 방 topic 전달·새 메시지 알림이 버전 확인 규칙(설계 4.2)으로 쓴다.
 * <ul>
 *     <li>메시지는 저장 때 가리킨 수신자 집합 버전 V를 싣는다. 캐시 버전이 V보다 작으면 DB에서 다시 읽는다(버전을 먼저, 참여를
 *     나중에). 퇴장·재입장은 방 잠금 아래 버전을 올리므로, 퇴장 커밋 뒤 저장된 메시지를 처음 내보낼 때 반드시 그 퇴장을 본다.</li>
 *     <li>회원 자격(정지·강등)은 버전을 바꾸지 않는다. 주기({@code membership-refresh})가 지난 캐시도 다시 읽는다. 자격 상실은
 *     회원 상태 재검사가 연결을 닫는다(설계 Q17).</li>
 *     <li>캐시가 충분하면(버전·주기 조건) 잠금 없이 돌려준다. 아니면 같은 방의 다시 읽기를 Caffeine 항목 계산
 *     ({@code asMap().compute})으로 직렬화하고, 계산 안에서 조건을 다시 확인한 뒤 읽으며, 더 낮은 버전으로 읽은 결과로 덮지
 *     않는다.</li>
 *     <li>계산은 해시 칸 잠금을 쥔 채 DB를 읽으므로, 다시 읽는 동안 같은 해시 칸에 든 다른 방도 기다린다. 실측으로 문제가 되면
 *     {@code AsyncCache}로 옮긴다.</li>
 *     <li>방 수에 상한을 두고 자주 쓰지 않는 방부터(W-TinyLFU) 버린다.</li>
 * </ul>
 * 조회 실패는 예외로 알린다. 계산 안의 예외는 항목을 바꾸지 않는다. 호출하는 쪽은 실패를 자격 있음으로 보지 않는다.
 * <p>
 * 지표는 Micrometer Caffeine 바인더가 {@code cache.*} 이름에 {@code cache=}{@value #CACHE_NAME} 태그로 낸다.
 * {@code cache.gets}의 hit·miss는 빠른 경로의 조회로 센다(주기가 지났거나 버전이 낮은 항목도 hit다). {@code cache.puts}는
 * 항목 계산(느린 경로) 수이고 조회 실패를 포함한다. {@code cache.load}는 {@code LoadingCache}가 아니어서 없다.
 */
@Slf4j
@Component
public class ChatRoomMembershipCache {

    /** 지표의 {@code cache} 태그 값 */
    public static final String CACHE_NAME = "chat.realtime.room_membership";

    private final ChatRecipientStateReader chatRecipientStateReader;
    private final long refreshNanos;
    private final Cache<Long, Entry> entries;

    /**
     * 캐시하는 방 수(1000)·자격 재조회 주기(60s)는 미확정 초기값(Q12)이다. 기본값은 코드가 원본이고 같은 키의 yaml·환경 변수가
     * 덮는다.
     */
    @Autowired
    public ChatRoomMembershipCache(ChatRecipientStateReader chatRecipientStateReader, MeterRegistry meterRegistry,
            @Value("${hapbang.chat.realtime.room.membership-cache-rooms:1000}") int maxRooms,
            @Value("${hapbang.chat.realtime.room.membership-refresh:60s}") Duration refresh) {
        this(chatRecipientStateReader, meterRegistry, maxRooms, refresh, ForkJoinPool.commonPool());
    }

    /** 퇴출 등 캐시 정리를 돌릴 실행기를 직접 넘긴다(테스트). 운영은 Caffeine 기본값인 공용 풀이다 */
    ChatRoomMembershipCache(ChatRecipientStateReader chatRecipientStateReader, MeterRegistry meterRegistry,
            int maxRooms, Duration refresh, Executor maintenanceExecutor) {
        Assert.isTrue(maxRooms > 0, "hapbang.chat.realtime.room.membership-cache-rooms는 1 이상이어야 합니다.");
        Assert.isTrue(refresh.isPositive(), "hapbang.chat.realtime.room.membership-refresh는 0보다 커야 합니다.");
        this.chatRecipientStateReader = chatRecipientStateReader;
        this.refreshNanos = refresh.toNanos();
        this.entries = Caffeine.newBuilder()
                .maximumSize(maxRooms)
                .executor(maintenanceExecutor)
                .recordStats()
                .build();
        CaffeineCacheMetrics.monitor(meterRegistry, entries, CACHE_NAME);
    }

    /**
     * 적어도 {@code minSetVersion}까지의 참여 변경을 반영한 방 참여 상태.
     *
     * @throws RuntimeException 조회 실패
     */
    public RoomMembership get(Long roomId, int minSetVersion) {
        Entry present = entries.getIfPresent(roomId);
        if (isUsable(present, minSetVersion)) {
            return present.membership();
        }
        Entry entry = entries.asMap().compute(roomId, (ignored, cached) ->
                isUsable(cached, minSetVersion) ? cached : reload(roomId, cached));
        if (entry.membership().setVersion() < minSetVersion) {
            // 메시지가 커밋된 뒤에 읽었으므로 일어나지 않는다. 일어나면 확인하지 못한 것으로 본다(읽은 결과는 캐시에 남긴다)
            throw new IllegalStateException("방 참여 상태가 메시지의 수신자 집합 버전보다 오래됐습니다. roomId=" + roomId
                    + ", expected>=" + minSetVersion + ", actual=" + entry.membership().setVersion());
        }
        return entry.membership();
    }

    /**
     * 캐시와 관계없이 지금 DB에서 다시 읽는다(더 낮은 버전으로 덮지는 않는다). 캐시가 구독보다 먼저 읽힌 탓에 판단이 어긋날 수 있을
     * 때(재입장 뒤 새 세대로 등록한 구독을 재입장 전에 읽은 상태로 판단하는 경우) 회수하기 전에 부른다.
     */
    public RoomMembership refresh(Long roomId) {
        return entries.asMap().compute(roomId, (ignored, cached) -> reload(roomId, cached)).membership();
    }

    /** 다시 읽지 않고 쓸 수 있는지: 적어도 {@code minSetVersion}을 반영했고 자격 재조회 주기 안에 읽었다 */
    private boolean isUsable(@Nullable Entry entry, int minSetVersion) {
        return entry != null && entry.membership().setVersion() >= minSetVersion
                && System.nanoTime() - entry.loadedAt() < refreshNanos;
    }

    /** DB에서 읽는다. 더 낮은 버전을 읽으면 기존 항목을 읽은 시각까지 그대로 둔다(주기가 지났으면 다음 요청이 다시 읽는다) */
    private Entry reload(Long roomId, @Nullable Entry cached) {
        RoomMembership loaded = chatRecipientStateReader.findRoomMembership(roomId);
        if (cached != null && loaded.setVersion() < cached.membership().setVersion()) {
            return cached;
        }
        return new Entry(loaded, System.nanoTime());
    }

    /** 방 참여 상태와 그 상태를 읽은 시각({@link System#nanoTime()}) */
    private record Entry(RoomMembership membership, long loadedAt) {
    }
}
