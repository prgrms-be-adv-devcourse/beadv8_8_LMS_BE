package com.hapbang.billing.app;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.shared.member.dto.MemberPageDto;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.shared.member.out.MemberApiCallException;
import com.hapbang.shared.member.out.MemberApiClient;

import lombok.extern.slf4j.Slf4j;

// 기존 회원 BillingUser Backfill (#324). 회원 내부 API(shared MemberApiClient, #322)를 ID 오름차순 키셋으로 읽어
// 회원마다 BillingUserSyncUseCase.apply를 그대로 부른다. 저장·버전 비교·잠금·재시도는 회원 이벤트 반영과 같은 로직이라
// Backfill이 이벤트로 반영한 최신 정보를 옛 정보로 덮지 않고, 다시 실행해도 결과가 같다.
// - 트랜잭션을 열지 않는다: 회원 내부 API를 부르는 동안 Billing DB 트랜잭션이 없고, 회원마다 apply가 짧은 트랜잭션을 연다.
// - 한 번에 최대 MAX_LIMIT명. 끝나지 않으면 결과의 nextAfterId를 다음 실행의 afterId로 넘긴다(처음부터 다시 실행해도 안전하다).
// - 회원 하나의 반영 실패는 failed로 세고 다음 회원을 계속한다. 회원 내부 API 장애·계약 위반은 실행 전체를 멈춘다.
// - 같은 앱에서 동시에 두 번 실행하지 않는다(BACKFILL_ALREADY_RUNNING). 단일 인스턴스 전제이며, 회원 이벤트 반영은 막지 않는다.
// 지갑·원장·환전 데이터는 건드리지 않는다.
@Slf4j
@Service
public class BillingUserBackfillUseCase {

    /** 한 번 실행에서 처리할 최대 회원 수 */
    public static final int MAX_LIMIT = 5_000;
    /** 회원 내부 API 한 번에 받을 최대 회원 수 */
    static final int PAGE_SIZE = MemberPageDto.MAX_SIZE;
    /** 응답에 담을 실패 회원 ID 최대 개수 */
    static final int MAX_FAILED_IDS = 100;

    private final Supplier<MemberApiClient> memberApiClient;
    private final BillingUserSyncUseCase billingUserSyncUseCase;
    // 빈(싱글턴)이 가진 실행 중 표시라 요청 사이에서 공유된다
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Autowired
    BillingUserBackfillUseCase(ObjectProvider<MemberApiClient> memberApiClient,
            BillingUserSyncUseCase billingUserSyncUseCase) {
        this((Supplier<MemberApiClient>) memberApiClient::getIfAvailable, billingUserSyncUseCase);
    }

    BillingUserBackfillUseCase(Supplier<MemberApiClient> memberApiClient,
            BillingUserSyncUseCase billingUserSyncUseCase) {
        this.memberApiClient = memberApiClient;
        this.billingUserSyncUseCase = billingUserSyncUseCase;
    }

    // afterId보다 큰 회원부터 최대 limit명을 반영한다. afterId는 0 이상, limit은 1~MAX_LIMIT(검사는 API 명세 제약이 한다).
    public BillingUserBackfillResult run(long afterId, int limit) {
        if (afterId < 0 || limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("afterId는 0 이상, limit은 1~" + MAX_LIMIT + "이어야 합니다.");
        }
        if (!running.compareAndSet(false, true)) {
            throw new BillingException(BillingErrorCode.BACKFILL_ALREADY_RUNNING);
        }
        try {
            return backfill(afterId, limit);
        } finally {
            running.set(false);
        }
    }

    private BillingUserBackfillResult backfill(long afterId, int limit) {
        Tally tally = new Tally();
        MemberApiClient client = memberApiClient.get();
        if (client == null) {
            log.error("회원 내부 API Client가 없어 BillingUser Backfill을 실행할 수 없습니다(hapbang.clients.member.base-url 미설정).");
            throw abort(BillingErrorCode.MEMBER_SOURCE_UNAVAILABLE, tally, afterId, null);
        }
        log.info("BillingUser Backfill 시작 afterId={}, limit={}", afterId, limit);
        long cursor = afterId;
        int remaining = limit;
        boolean completed = false;
        while (remaining > 0) {
            int size = Math.min(PAGE_SIZE, remaining);
            MemberPageDto page = fetch(client, cursor, size, tally);
            verify(page, cursor, size, tally);
            for (UserDto user : page.items()) {
                tally.apply(user, billingUserSyncUseCase);
            }
            remaining -= page.items().size();
            if (page.nextAfterId() == null) {
                completed = true;
                break;
            }
            cursor = page.nextAfterId();
        }
        BillingUserBackfillResult result = tally.result(completed ? null : cursor, completed);
        log.info("BillingUser Backfill 종료 scanned={}, created={}, updated={}, ignoredStale={}, ignoredVersionless={}, "
                        + "failed={}, lastProcessedId={}, nextAfterId={}, completed={}", result.scanned(), result.created(),
                result.updated(), result.ignoredStale(), result.ignoredVersionless(), result.failed(),
                result.lastProcessedId(), result.nextAfterId(), result.completed());
        return result;
    }

    // 4xx(REJECTED)는 내부 API 키·계약 불일치라 다시 시도해도 같고, 나머지(연결·시간 초과·5xx·읽을 수 없는 응답)는 일시 장애다.
    private MemberPageDto fetch(MemberApiClient client, long cursor, int size, Tally tally) {
        try {
            return client.getMembers(cursor, size);
        } catch (MemberApiCallException e) {
            boolean rejected = e.getReason() == MemberApiCallException.Reason.REJECTED;
            if (rejected) {
                log.error("회원 내부 API가 Backfill 조회를 거절했습니다: afterId={}, status={}, code={}", cursor, e.getStatus(),
                        e.getCode());
            } else {
                log.warn("회원 내부 API를 조회하지 못해 Backfill을 멈춥니다: afterId={}, status={}", cursor, e.getStatus());
            }
            throw abort(rejected ? BillingErrorCode.MEMBER_SOURCE_REJECTED : BillingErrorCode.MEMBER_SOURCE_UNAVAILABLE,
                    tally, cursor, e);
        }
    }

    // 키셋 계약: 요청 크기 이하, ID는 null이 아니고 afterId보다 크며 엄격히 오름차순(중복 없음),
    // nextAfterId가 있으면 비어 있지 않은 페이지의 마지막 ID와 같다(커서가 반드시 전진한다).
    // 어기면 페이지의 어떤 회원도 반영하지 않고 실행을 멈춘다(잘못된 응답으로 반복하지 않는다).
    private void verify(MemberPageDto page, long cursor, int size, Tally tally) {
        List<UserDto> items = page.items();
        String violation = null;
        if (items.size() > size) {
            violation = "요청보다 많은 회원";
        } else if (items.isEmpty() && page.nextAfterId() != null) {
            violation = "빈 페이지에 nextAfterId";
        } else {
            long previous = cursor;
            for (UserDto user : items) {
                if (user.id() == null || user.id() <= previous) {
                    violation = "회원 ID가 afterId보다 크지 않거나 오름차순이 아님";
                    break;
                }
                previous = user.id();
            }
            if (violation == null && page.nextAfterId() != null && page.nextAfterId() != previous) {
                violation = "nextAfterId가 마지막 회원 ID와 다름";
            }
        }
        if (violation != null) {
            log.error("회원 내부 API 응답이 키셋 계약과 맞지 않아 Backfill을 멈춥니다: afterId={}, size={}, 사유={}", cursor, size,
                    violation);
            throw abort(BillingErrorCode.MEMBER_SOURCE_UNAVAILABLE, tally, cursor,
                    new IllegalStateException(violation));
        }
    }

    private BillingUserBackfillException abort(BillingErrorCode code, Tally tally, long resumeAfterId,
            Throwable cause) {
        BillingUserBackfillResult progress = tally.result(resumeAfterId, false);
        log.warn("BillingUser Backfill 중단 code={}, scanned={}, created={}, updated={}, failed={}, lastProcessedId={}, "
                        + "resumeAfterId={}", code, progress.scanned(), progress.created(), progress.updated(),
                progress.failed(), progress.lastProcessedId(), resumeAfterId);
        return new BillingUserBackfillException(code, progress, resumeAfterId, cause);
    }

    // 한 번 실행의 집계(실행마다 새로 만든다)
    private static final class Tally {

        private long scanned;
        private long created;
        private long updated;
        private long ignoredStale;
        private long ignoredVersionless;
        private long failed;
        private final List<Long> failedIds = new ArrayList<>();
        private Long lastProcessedId;

        // 회원 하나를 반영한다. 실패하면 failed로 세고 다음 회원을 계속한다(예외 메시지에는 회원 값이 담길 수 있어 로그에 남기지 않는다).
        void apply(UserDto user, BillingUserSyncUseCase syncUseCase) {
            scanned++;
            lastProcessedId = user.id();
            try {
                switch (syncUseCase.apply(user)) {
                    case CREATED -> created++;
                    case UPDATED -> updated++;
                    case IGNORED_STALE -> ignoredStale++;
                    case IGNORED_VERSIONLESS -> ignoredVersionless++;
                }
            } catch (RuntimeException e) {
                failed++;
                if (failedIds.size() < MAX_FAILED_IDS) {
                    failedIds.add(user.id());
                }
                log.warn("BillingUser Backfill 회원 반영 실패, 다음 회원을 계속한다 userId={}, error={}", user.id(),
                        e.getClass().getSimpleName());
            }
        }

        BillingUserBackfillResult result(Long nextAfterId, boolean completed) {
            return new BillingUserBackfillResult(scanned, created, updated, ignoredStale, ignoredVersionless, failed,
                    failedIds, lastProcessedId, nextAfterId, completed);
        }
    }
}
