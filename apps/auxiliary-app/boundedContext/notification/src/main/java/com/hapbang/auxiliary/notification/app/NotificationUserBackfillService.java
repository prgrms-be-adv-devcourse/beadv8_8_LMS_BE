package com.hapbang.auxiliary.notification.app;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import com.hapbang.auxiliary.global.scheduling.JobLock;
import com.hapbang.auxiliary.notification.app.exception.NotificationErrorCode;
import com.hapbang.auxiliary.notification.app.exception.NotificationException;
import com.hapbang.auxiliary.notification.domain.NotificationUser;
import com.hapbang.auxiliary.notification.out.NotificationUserRepository;
import com.hapbang.shared.member.dto.MemberPageDto;
import com.hapbang.shared.member.dto.UserDto;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * 회원 복제본 Backfill(ND-077·ND-078). Kafka 보존(72시간)보다 먼저 가입하고 그 뒤 변경이 없는 회원의 복제본을 회원 목록 내부 API로 채운다.
 * <ul>
 *     <li>키셋 페이지를 마지막 페이지(다음 afterId 없음)까지 읽는다. 회원마다 한 문장 upsert라 같은 회원의 Kafka 변경과 겹쳐도 더 큰
 *     버전이 남고, 파기된 복제본은 바꾸지 않는다({@link NotificationUserRepository#upsertFromBackfill}).</li>
 *     <li>복제할 수 없는 회원 정보(ID·닉네임·역할·상태 없음, ND-049 기준)는 건너뛰고 회원 ID를 남긴다. 완료 판정을 막지 않는다.</li>
 *     <li>회원 API·DB 장애는 예외로 끝난다(완료 아님). 다시 실행하면 처음부터 다시 읽어도 결과가 같다(멱등).</li>
 *     <li>기동 실행과 관리자 재실행은 같은 advisory lock({@link NotificationJob#USER_BACKFILL})으로 하나만 돈다. 잡지 못하면
 *     {@code BACKFILL_RUNNING}이다.</li>
 * </ul>
 * 결과는 로그와 지표 {@value #RUNS_METRIC}{outcome}·{@value #MEMBERS_METRIC}{result}로 본다.
 */
@Slf4j
@Service
public class NotificationUserBackfillService {

    public static final String PAGE_SIZE = "hapbang.notification.user-backfill.page-size";
    static final String RUNS_METRIC = "hapbang.notification.user.backfill.runs";
    static final String MEMBERS_METRIC = "hapbang.notification.user.backfill.members";
    /** 완료 로그에 남기는 건너뛴 회원 ID 수. 전체는 관리자 실행 조회(COMPLETED 결과)로 본다 */
    static final int LOGGED_SKIPPED_IDS = 20;

    private final ObjectProvider<NotificationMemberSource> sources;
    private final JobLock jobLock;
    private final NotificationUserRepository users;
    private final ObjectProvider<MeterRegistry> meterRegistry;
    private final int pageSize;

    public NotificationUserBackfillService(ObjectProvider<NotificationMemberSource> sources, JobLock jobLock,
            NotificationUserRepository users, ObjectProvider<MeterRegistry> meterRegistry,
            @Value("${" + PAGE_SIZE + ":" + MemberPageDto.MAX_SIZE + "}") int pageSize) {
        Assert.isTrue(pageSize >= 1 && pageSize <= MemberPageDto.MAX_SIZE,
                "Backfill 페이지 크기는 1~" + MemberPageDto.MAX_SIZE + "이어야 합니다.");
        this.sources = sources;
        this.jobLock = jobLock;
        this.users = users;
        this.meterRegistry = meterRegistry;
        this.pageSize = pageSize;
    }

    /**
     * @throws NotificationException 다른 실행이 잠금을 쥐고 있으면 {@code BACKFILL_RUNNING}
     * @throws RuntimeException      회원 API·DB 장애. 완료가 아니다
     */
    public BackfillResult run() {
        try (JobLock.Lease ignored = acquire()) {
            return runLocked(() -> {
            });
        }
    }

    /**
     * 잠금을 잡는다. 관리자 비동기 실행은 요청 스레드에서 잡고, 실행 스레드가 끝나며 푼다(ND-092).
     *
     * @throws NotificationException 다른 실행이 쥐고 있으면 {@code BACKFILL_RUNNING}
     */
    public JobLock.Lease acquire() {
        Optional<JobLock.Lease> lease;
        try {
            lease = jobLock.tryAcquire(NotificationJob.USER_BACKFILL);
        } catch (RuntimeException e) {
            // 잠금을 시도하지 못했다(DB 장애). 실행하지 못했으므로 실패로 센다
            count(RUNS_METRIC, "outcome", "failed", 1);
            throw e;
        }
        return lease.orElseThrow(() -> {
            count(RUNS_METRIC, "outcome", "running", 1);
            return new NotificationException(NotificationErrorCode.BACKFILL_RUNNING);
        });
    }

    /**
     * 잠금을 잡은 쪽이 부른다. 페이지마다 {@code onPage}를 부른다(실행 상태 갱신 시각, ND-092).
     *
     * @throws RuntimeException 회원 API·DB 장애. 완료가 아니다
     */
    public BackfillResult runLocked(Runnable onPage) {
        NotificationMemberSource source = sources.getIfAvailable();
        try {
            if (source == null) {
                throw new IllegalStateException("회원 목록 소스가 없어 Backfill을 할 수 없습니다(hapbang.clients.member.base-url).");
            }
            BackfillResult result = readAll(source, onPage);
            count(RUNS_METRIC, "outcome", "completed", 1);
            log.info("알림 회원 복제본 Backfill을 마쳤다 scanned={}, applied={}, unchanged={}, skipped={}, skippedUserIds={}",
                    result.scanned(), result.applied(), result.unchanged(), result.skipped(),
                    result.skippedUserIds().stream().limit(LOGGED_SKIPPED_IDS).toList());
            return result;
        } catch (RuntimeException e) {
            count(RUNS_METRIC, "outcome", "failed", 1);
            throw e;
        }
    }

    private BackfillResult readAll(NotificationMemberSource source, Runnable onPage) {
        int scanned = 0;
        int applied = 0;
        int unchanged = 0;
        int skippedCount = 0;
        List<Long> skipped = new ArrayList<>();
        Long afterId = null;
        do {
            MemberPageDto page = source.members(afterId, pageSize);
            for (UserDto user : page.items()) {
                scanned++;
                if (!NotificationUser.isReplicable(user)) {
                    skippedCount++;
                    if (user.id() != null) {
                        skipped.add(user.id());
                    }
                    continue;
                }
                if (users.upsertFromBackfill(user.id(), user.nickname(), user.role().name(), user.status().name(),
                        user.version(), LocalDateTime.now()) == 1) {
                    applied++;
                } else {
                    unchanged++;
                }
            }
            afterId = page.nextAfterId();
            onPage.run();
        } while (afterId != null);
        count(MEMBERS_METRIC, "result", "applied", applied);
        count(MEMBERS_METRIC, "result", "unchanged", unchanged);
        count(MEMBERS_METRIC, "result", "skipped", skippedCount);
        return new BackfillResult(scanned, applied, unchanged, skippedCount, skipped);
    }

    private void count(String metric, String tag, String value, int amount) {
        MeterRegistry registry = meterRegistry.getIfAvailable();
        if (registry != null && amount > 0) {
            Counter.builder(metric).tag(tag, value).register(registry).increment(amount);
        }
    }
}
