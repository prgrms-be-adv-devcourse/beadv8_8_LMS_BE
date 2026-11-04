package com.hapbang.chat.app;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * 로컬 연결 회원의 채팅 자격 재검사(설계 5.3). 제어 명령(RECHECK_USER)·주기 재검사·Redis 구독 복구 직후 재검사가 쓴다.
 * <p>
 * 명령에 실린 사유·버전이 아니라 chat DB의 최신 회원 상태로 판단하므로, 자격이 복구된 회원은 옛 명령이 늦게 와도 닫지 않는다.
 * 연결 닫기는 반복해도 안전하다.
 * <p>
 * <b>조회 실패</b>: 실패한 묶음의 연결은 닫지 않고, 실패를 자격 확인 성공으로 기록하지도 않는다. 그 묶음의 회원만
 * 짧은 지수 backoff({@code retry.initial-delay}부터 두 배씩, 최대 {@code retry.max-delay})로 최대 {@code retry.max-retries}번
 * 다시 확인한다. 다시 확인할 때 이미 연결이 없는 회원은 뺀다. 상한을 넘으면 {@value #RETRY_EXHAUSTED_METRIC}을 세고
 * 다음 주기 재검사에 맡긴다. 조회 실패는 모두 {@value #LOOKUP_FAILURE_METRIC}으로 센다.
 */
@Slf4j
@Service
public class DefaultChatConnectionRecheckService implements ChatConnectionRecheckService, DisposableBean {

    /** 재검사 자격 조회 실패 수 */
    public static final String LOOKUP_FAILURE_METRIC = "chat.realtime.recheck.lookup.failures";
    /** 재시도 상한을 넘어 주기 재검사에 맡긴 묶음 수 */
    public static final String RETRY_EXHAUSTED_METRIC = "chat.realtime.recheck.retry.exhausted";
    /** 이미 재시도를 기다리는 회원이라 그 재시도에 합친 수 */
    public static final String RETRY_COALESCED_METRIC = "chat.realtime.recheck.retry.coalesced";
    /** 재시도를 기다리는 회원 수 상한을 넘어 예약하지 않고 주기 재검사에 맡긴 수 */
    public static final String RETRY_DROPPED_METRIC = "chat.realtime.recheck.retry.dropped";

    private final LocalChatConnections localChatConnections;
    private final ChatRecipientStateReader chatRecipientStateReader;
    private final TaskScheduler taskScheduler;
    private final int batchSize;
    private final int maxRetries;
    private final Duration initialRetryDelay;
    private final Duration maxRetryDelay;
    private final Counter lookupFailures;
    private final Counter retryExhausted;
    private final Counter retryCoalesced;
    private final Counter retryDropped;
    /** 재시도가 예약돼 기다리는 회원. 회원마다 하나만 둔다(같은 회원의 재검사가 몰려도 예약이 늘지 않는다) */
    private final Set<Long> pendingRetryUsers = ConcurrentHashMap.newKeySet();
    private int maxPendingRetryUsers = 10_000;
    /** 이 서비스가 만들어 직접 닫아야 하는 재시도 스케줄러인지 */
    private final boolean ownsScheduler;

    /**
     * 재시도는 이 서비스 전용 스레드 하나로 예약한다. 배치가 쓰는 공용 {@code taskScheduler}와 나눠, 배치·전송 폭주가 재시도를
     * 굶기거나 재시도가 배치를 막지 않게 한다(설계 4.6).
     * <p>
     * 재검사 묶음 크기(500)·재시도 횟수(3)·간격(1s~10s)은 미확정 초기값(Q12)이다. 기본값은 이 코드가 원본이고 application.yaml에
     * 두지 않는다. 같은 키의 yaml·환경 변수가 덮는다.
     */
    @Autowired
    public DefaultChatConnectionRecheckService(LocalChatConnections localChatConnections,
            ChatRecipientStateReader chatRecipientStateReader,
            MeterRegistry meterRegistry,
            @Value("${hapbang.chat.realtime.recheck.batch-size:500}") int batchSize,
            @Value("${hapbang.chat.realtime.recheck.retry.max-retries:3}") int maxRetries,
            @Value("${hapbang.chat.realtime.recheck.retry.initial-delay:1s}") Duration initialRetryDelay,
            @Value("${hapbang.chat.realtime.recheck.retry.max-delay:10s}") Duration maxRetryDelay) {
        this(localChatConnections, chatRecipientStateReader, retryScheduler(), true, meterRegistry, batchSize,
                maxRetries, initialRetryDelay, maxRetryDelay);
    }

    /** 재시도 스케줄러를 직접 넘긴다(테스트). 넘긴 스케줄러는 닫지 않는다 */
    DefaultChatConnectionRecheckService(LocalChatConnections localChatConnections,
            ChatRecipientStateReader chatRecipientStateReader, TaskScheduler taskScheduler,
            MeterRegistry meterRegistry, int batchSize, int maxRetries, Duration initialRetryDelay,
            Duration maxRetryDelay) {
        this(localChatConnections, chatRecipientStateReader, taskScheduler, false, meterRegistry, batchSize,
                maxRetries, initialRetryDelay, maxRetryDelay);
    }

    private DefaultChatConnectionRecheckService(LocalChatConnections localChatConnections,
            ChatRecipientStateReader chatRecipientStateReader, TaskScheduler taskScheduler, boolean ownsScheduler,
            MeterRegistry meterRegistry, int batchSize, int maxRetries, Duration initialRetryDelay,
            Duration maxRetryDelay) {
        Assert.isTrue(batchSize > 0, "hapbang.chat.realtime.recheck.batch-size는 1 이상이어야 합니다.");
        Assert.isTrue(maxRetries >= 0, "hapbang.chat.realtime.recheck.retry.max-retries는 0 이상이어야 합니다.");
        Assert.isTrue(initialRetryDelay.isPositive() && !maxRetryDelay.minus(initialRetryDelay).isNegative(),
                "hapbang.chat.realtime.recheck.retry 지연은 0보다 크고 initial-delay <= max-delay여야 합니다.");
        this.localChatConnections = localChatConnections;
        this.chatRecipientStateReader = chatRecipientStateReader;
        this.taskScheduler = taskScheduler;
        this.ownsScheduler = ownsScheduler;
        this.batchSize = batchSize;
        this.maxRetries = maxRetries;
        this.initialRetryDelay = initialRetryDelay;
        this.maxRetryDelay = maxRetryDelay;
        this.lookupFailures = meterRegistry.counter(LOOKUP_FAILURE_METRIC);
        this.retryExhausted = meterRegistry.counter(RETRY_EXHAUSTED_METRIC);
        this.retryCoalesced = meterRegistry.counter(RETRY_COALESCED_METRIC);
        this.retryDropped = meterRegistry.counter(RETRY_DROPPED_METRIC);
    }

    private static ThreadPoolTaskScheduler retryScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("chat-recheck-retry-");
        scheduler.initialize();
        return scheduler;
    }

    @Override
    public void destroy() {
        if (ownsScheduler && taskScheduler instanceof ThreadPoolTaskScheduler scheduler) {
            scheduler.shutdown();
        }
    }

    @Override
    public void recheckUser(Long userId) {
        if (userId == null || localChatConnections.connectedAmong(List.of(userId)).isEmpty()) {
            return;
        }
        recheck(List.of(userId), 0);
    }

    @Override
    public void recheckAllConnected() {
        List<Long> userIds = new ArrayList<>(localChatConnections.connectedUserIds());
        int closedUsers = 0;
        for (int from = 0; from < userIds.size(); from += batchSize) {
            closedUsers += recheck(userIds.subList(from, Math.min(from + batchSize, userIds.size())), 0);
        }
        log.debug("로컬 연결 회원 재검사 users={}, closedUsers={}", userIds.size(), closedUsers);
    }

    /**
     * @param retry 지금까지 한 재시도 횟수. 첫 확인은 0
     * @return 연결을 닫은 회원 수
     */
    private int recheck(List<Long> userIds, int retry) {
        Set<Long> allowed;
        try {
            allowed = chatRecipientStateReader.findChatAllowedUserIds(userIds);
        } catch (RuntimeException exception) {
            lookupFailures.increment();
            log.error("채팅 자격 재검사 조회 실패. 연결을 유지하고 다시 확인한다 users={}, retry={}", userIds.size(), retry,
                    exception);
            scheduleRetry(List.copyOf(userIds), retry);
            return 0;
        }
        int closedUsers = 0;
        for (Long userId : userIds) {
            if (allowed.contains(userId)) {
                continue;
            }
            int closed = localChatConnections.closeNotAllowed(userId);
            closedUsers++;
            log.info("채팅할 수 없는 회원의 로컬 연결 닫음 userId={}, closed={}", userId, closed);
        }
        return closedUsers;
    }

    /** 재시도를 기다릴 수 있는 회원 수 상한. 미확정 초기값(Q12) */
    @Value("${hapbang.chat.realtime.recheck.retry.max-pending-users:10000}")
    public void setMaxPendingRetryUsers(int maxPendingRetryUsers) {
        Assert.isTrue(maxPendingRetryUsers > 0,
                "hapbang.chat.realtime.recheck.retry.max-pending-users는 1 이상이어야 합니다.");
        this.maxPendingRetryUsers = maxPendingRetryUsers;
    }

    /** 재시도를 기다리는 회원 수(관측·테스트용) */
    public int pendingRetryUsers() {
        return pendingRetryUsers.size();
    }

    /**
     * 실패한 회원의 재시도를 예약한다. 이미 재시도를 기다리는 회원은 그 재시도에 합치고, 기다리는 회원 수가 상한이면 예약하지 않고
     * 세어 주기 재검사에 맡긴다(설계 4.6: 대기열·재시도에 상한을 둔다).
     */
    private void scheduleRetry(List<Long> userIds, int retry) {
        if (retry >= maxRetries) {
            retryExhausted.increment();
            log.error("채팅 자격 재검사 재시도 상한 도달. 주기 재검사에 맡긴다 users={}, retries={}", userIds.size(), retry);
            return;
        }
        List<Long> scheduled = new ArrayList<>();
        for (Long userId : userIds) {
            if (pendingRetryUsers.contains(userId)) {
                retryCoalesced.increment();
            } else if (pendingRetryUsers.size() >= maxPendingRetryUsers) {
                retryDropped.increment();
            } else if (pendingRetryUsers.add(userId)) {
                scheduled.add(userId);
            } else {
                retryCoalesced.increment();
            }
        }
        if (scheduled.isEmpty()) {
            return;
        }
        if (scheduled.size() < userIds.size()) {
            log.warn("채팅 자격 재검사 재시도 일부를 합치거나 상한으로 주기 재검사에 맡김 requested={}, scheduled={}",
                    userIds.size(), scheduled.size());
        }
        Duration delay = retryDelay(retry);
        try {
            taskScheduler.schedule(() -> retry(scheduled, retry + 1), Instant.now().plus(delay));
        } catch (TaskRejectedException exception) {
            // 앱 종료 중 등. 주기 재검사가 회복한다
            pendingRetryUsers.removeAll(scheduled);
            log.warn("채팅 자격 재검사 재시도를 예약하지 못함 users={}", scheduled.size(), exception);
        }
    }

    private void retry(List<Long> userIds, int retry) {
        // 이제 실행한다. 이 실행이 다시 실패하면 새로 예약한다
        pendingRetryUsers.removeAll(userIds);
        try {
            Set<Long> stillConnected = localChatConnections.connectedAmong(userIds);
            List<Long> targets = userIds.stream().filter(stillConnected::contains).toList();
            if (!targets.isEmpty()) {
                recheck(targets, retry);
            }
        } catch (RuntimeException exception) {
            log.error("채팅 자격 재검사 재시도 실패 users={}", userIds.size(), exception);
        }
    }

    /** {@code initial-delay × 2^retry}, 최대 {@code max-delay} */
    private Duration retryDelay(int retry) {
        Duration delay = initialRetryDelay;
        for (int i = 0; i < retry && delay.compareTo(maxRetryDelay) < 0; i++) {
            delay = delay.multipliedBy(2);
        }
        return delay.compareTo(maxRetryDelay) > 0 ? maxRetryDelay : delay;
    }
}
