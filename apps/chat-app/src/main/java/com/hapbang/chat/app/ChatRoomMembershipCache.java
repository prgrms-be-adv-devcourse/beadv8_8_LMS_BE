package com.hapbang.chat.app;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Predicate;

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
 *     <li>캐시가 충분하면(버전·주기 조건) 잠금 없이 돌려준다. 아니면 Caffeine 항목 계산({@code asMap().compute})으로 그 방의
 *     읽기 자리(완료 전 future)를 하나만 세우고, 자리를 세운 요청이 계산 밖에서 DB를 읽는다. 같은 방의 다른 요청은 그 읽기가
 *     끝나기를 기다린 뒤 조건을 다시 본다. DB 조회는 해시 칸 잠금 밖이고 같은 방만 한 번에 하나다.</li>
 *     <li>더 낮은 버전으로 읽은 결과로 덮지 않는다(읽은 시각도 그대로 두어 주기가 지났으면 다음 요청이 다시 읽는다).</li>
 *     <li>방 수에 상한을 두고 자주 쓰지 않는 방부터(W-TinyLFU) 버린다.</li>
 * </ul>
 * 조회 실패는 예외로 알린다. 실패하면 캐시를 읽기 전으로 되돌리고, 기다리던 같은 방 요청은 직접 다시 읽는다. 호출하는 쪽은
 * 실패를 자격 있음으로 보지 않는다.
 * <p>
 * 값은 future지만 {@code AsyncCache}가 아니라 일반 {@code Cache}에 담는다. {@code AsyncCache}는 실패한 future마다 경고
 * 로그를 남기고 항목을 스스로 지우므로, 실패 때 이전 값으로 되돌리는 규칙과 기존 로그를 지키기 어렵다.
 * <p>
 * 지표는 Micrometer Caffeine 바인더가 {@code cache.*} 이름에 {@code cache=}{@value #CACHE_NAME} 태그로 낸다.
 * {@code cache.gets}의 hit·miss는 빠른 경로의 조회로 센다(주기가 지났거나 버전이 낮거나 읽는 중인 항목도 hit다).
 * {@code cache.puts}는 항목 계산(느린 경로) 수이고, 기다린 뒤 다시 보는 계산과 조회 실패를 포함한다. {@code cache.size}는 읽는 중인
 * 자리를 포함한다. {@code cache.load}는 {@code LoadingCache}가 아니어서 없다.
 */
@Slf4j
@Component
public class ChatRoomMembershipCache {

    /** 지표의 {@code cache} 태그 값 */
    public static final String CACHE_NAME = "chat.realtime.room_membership";

    private final ChatRecipientStateReader chatRecipientStateReader;
    private final long refreshNanos;
    /** 방 ID → 방 참여 상태. 완료 전 future는 그 방을 지금 읽는 중이라는 자리다. 실패한 future는 담지 않는다 */
    private final Cache<Long, CompletableFuture<Entry>> entries;

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
        Entry present = completedValue(entries.getIfPresent(roomId));
        Entry entry = isUsable(present, minSetVersion)
                ? present
                : load(roomId, cached -> isUsable(cached, minSetVersion));
        if (entry.membership().setVersion() < minSetVersion) {
            // 메시지가 커밋된 뒤에 읽었으므로 일어나지 않는다. 일어나면 확인하지 못한 것으로 본다(읽은 결과는 캐시에 남긴다)
            throw new IllegalStateException("방 참여 상태가 메시지의 수신자 집합 버전보다 오래됐습니다. roomId=" + roomId
                    + ", expected>=" + minSetVersion + ", actual=" + entry.membership().setVersion());
        }
        return entry.membership();
    }

    /**
     * 캐시와 관계없이 지금 DB에서 다시 읽는다(더 낮은 버전으로 덮지는 않는다). 캐시가 구독보다 먼저 읽힌 탓에 판단이 어긋날 수 있을
     * 때(재입장 뒤 새 세대로 등록한 구독을 재입장 전에 읽은 상태로 판단하는 경우) 회수하기 전에 부른다. 같은 방을 이미 읽는 중이면
     * 그 읽기는 이 호출 전에 시작했으므로 끝나기를 기다린 뒤 직접 다시 읽는다.
     */
    public RoomMembership refresh(Long roomId) {
        return load(roomId, cached -> false).membership();
    }

    /**
     * {@code usable}을 만족하는 캐시가 있으면 그 값을, 없으면 이 요청이 세운 읽기 자리로 DB에서 읽은 값을 돌려준다. 같은 방을 다른
     * 요청이 읽는 중이면 끝나기를 기다린 뒤(실패해도) 처음부터 다시 본다.
     */
    private Entry load(Long roomId, Predicate<@Nullable Entry> usable) {
        while (true) {
            CompletableFuture<Entry> created = new CompletableFuture<>();
            Outcome outcome = new Outcome();
            CompletableFuture<Entry> current = entries.asMap().compute(roomId, (ignored, cached) -> {
                if (cached != null && !cached.isDone()) {
                    return cached;
                }
                Entry entry = completedValue(cached);
                if (usable.test(entry)) {
                    outcome.hit = entry;
                    return cached;
                }
                outcome.previous = entry;
                return created;
            });
            if (current == created) {
                return read(roomId, created, outcome.previous);
            }
            if (outcome.hit != null) {
                return outcome.hit;
            }
            // 같은 방을 읽는 중이다. 끝나면(실패해도) 다시 본다
            current.handle((entry, error) -> null).join();
        }
    }

    /** 계산 밖에서 DB를 읽어 이 요청이 세운 자리를 채운다. 실패하면 자리를 읽기 전 값으로 되돌린 뒤 기다리던 요청을 깨운다 */
    private Entry read(Long roomId, CompletableFuture<Entry> created, @Nullable Entry previous) {
        try {
            RoomMembership loaded = chatRecipientStateReader.findRoomMembership(roomId);
            Entry entry = previous != null && loaded.setVersion() < previous.membership().setVersion()
                    ? previous
                    : new Entry(loaded, System.nanoTime());
            created.complete(entry);
            return entry;
        } catch (Throwable failure) {
            if (previous != null) {
                entries.asMap().replace(roomId, created, CompletableFuture.completedFuture(previous));
            } else {
                entries.asMap().remove(roomId, created);
            }
            created.completeExceptionally(failure);
            throw failure;
        }
    }

    /** 다시 읽지 않고 쓸 수 있는지: 적어도 {@code minSetVersion}을 반영했고 자격 재조회 주기 안에 읽었다 */
    private boolean isUsable(@Nullable Entry entry, int minSetVersion) {
        return entry != null && entry.membership().setVersion() >= minSetVersion
                && System.nanoTime() - entry.loadedAt() < refreshNanos;
    }

    /** 정상 완료한 future의 값. 없거나 읽는 중이거나 실패했으면 null */
    private static @Nullable Entry completedValue(@Nullable CompletableFuture<Entry> future) {
        return future != null && future.isDone() && !future.isCompletedExceptionally() ? future.join() : null;
    }

    /** 방 참여 상태와 그 상태를 읽은 시각({@link System#nanoTime()}) */
    private record Entry(RoomMembership membership, long loadedAt) {
    }

    /** 항목 계산이 고른 결과. 계산 람다 안에서만 쓰고 같은 스레드가 읽는다 */
    private static final class Outcome {
        private @Nullable Entry hit;
        private @Nullable Entry previous;
    }
}
