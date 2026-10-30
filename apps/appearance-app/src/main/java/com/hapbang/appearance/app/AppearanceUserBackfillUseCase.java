package com.hapbang.appearance.app;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.appearance.app.dto.AppearanceUserBackfillResDto;
import com.hapbang.appearance.domain.AppearanceErrorCode;
import com.hapbang.appearance.domain.AppearanceException;
import com.hapbang.shared.member.dto.MemberPageDto;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.shared.member.out.MemberApiCallException;
import com.hapbang.shared.member.out.MemberApiClient;

import lombok.extern.slf4j.Slf4j;

/**
 * 기존 회원 Backfill (#394). 회원 앱이 Kafka로 가입 이벤트를 보내기 전에 가입한 회원은 출연 회원이 없으므로,
 * 회원 내부 API(shared {@link MemberApiClient})를 회원 ID 오름차순 키셋으로 읽어 출연 회원을 채운다. 지갑 Backfill(#324)과 같은 구조다.
 * <ul>
 *     <li>회원마다 {@link AppearanceUserSyncUseCase#applySnapshot}을 짧은 트랜잭션으로 부른다. 같은 클래스 안 처리라
 *     {@link TransactionTemplate}으로 연다. 회원 내부 API를 부르는 동안에는 출연 DB 트랜잭션이 없다.</li>
 *     <li>회원 버전으로 비교하므로 이벤트로 반영한 최신 정보를 덮지 않고, 다시 실행해도 결과가 같다.</li>
 *     <li>한 번에 최대 {@value #MAX_LIMIT}명. 끝나지 않으면 결과의 nextAfterId를 다음 실행의 afterId로 넘긴다.</li>
 *     <li>회원 하나의 반영 실패는 failed로 세고 다음 회원을 계속한다. 회원 내부 API 장애·키셋 계약 위반은 실행 전체를 멈춘다.</li>
 *     <li>같은 앱에서 동시에 두 번 실행하지 않는다({@code BACKFILL_ALREADY_RUNNING}, 출연 앱 1대 전제). 회원 이벤트 소비는 막지 않는다.</li>
 * </ul>
 */
@Slf4j
@Service
public class AppearanceUserBackfillUseCase {

    /**
     * 한 번 실행에서 처리할 최대 회원 수. Gateway 응답 제한(30초) 안에 끝나도록 지갑(5,000)보다 낮게 둔다.
     * 넘기면 관리자는 504를 받지만 서버는 끝까지 처리하고 결과는 로그에만 남는다
     */
    public static final int MAX_LIMIT = 1_000;
    /** 회원 내부 API 한 번에 받을 최대 회원 수 */
    static final int PAGE_SIZE = MemberPageDto.MAX_SIZE;
    /** 응답에 담을 실패 회원 ID 최대 개수 */
    static final int MAX_FAILED_IDS = 100;

    private final Supplier<MemberApiClient> memberApiClient;
    private final AppearanceUserSyncUseCase appearanceUserSyncUseCase;
    private final TransactionTemplate transactionTemplate;
    // 빈(싱글턴)이 가진 실행 중 표시라 요청 사이에서 공유된다
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 회원 내부 API 주소가 없으면 Client 빈이 없다. 그때는 실행하면 MEMBER_SOURCE_UNAVAILABLE이다 */
    @Autowired
    AppearanceUserBackfillUseCase(ObjectProvider<MemberApiClient> memberApiClient,
            AppearanceUserSyncUseCase appearanceUserSyncUseCase, TransactionTemplate transactionTemplate) {
        this((Supplier<MemberApiClient>) memberApiClient::getIfAvailable, appearanceUserSyncUseCase, transactionTemplate);
    }

    AppearanceUserBackfillUseCase(Supplier<MemberApiClient> memberApiClient,
            AppearanceUserSyncUseCase appearanceUserSyncUseCase, TransactionTemplate transactionTemplate) {
        this.memberApiClient = memberApiClient;
        this.appearanceUserSyncUseCase = appearanceUserSyncUseCase;
        this.transactionTemplate = transactionTemplate;
    }

    /** afterId보다 큰 회원부터 최대 limit명을 반영한다. afterId는 0 이상, limit은 1~{@value #MAX_LIMIT}(검사는 API 명세 제약이 한다) */
    public AppearanceUserBackfillResDto run(long afterId, int limit) {
        if (afterId < 0 || limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("afterId는 0 이상, limit은 1~" + MAX_LIMIT + "이어야 합니다.");
        }
        if (!running.compareAndSet(false, true)) {
            throw new AppearanceException(AppearanceErrorCode.BACKFILL_ALREADY_RUNNING);
        }
        try {
            return backfill(afterId, limit);
        } finally {
            running.set(false);
        }
    }

    private AppearanceUserBackfillResDto backfill(long afterId, int limit) {
        Tally tally = new Tally();
        MemberApiClient client = memberApiClient.get();
        if (client == null) {
            log.error("회원 내부 API Client가 없어 출연 회원 Backfill을 실행할 수 없다(hapbang.clients.member.base-url 미설정)");
            throw abort(AppearanceErrorCode.MEMBER_SOURCE_UNAVAILABLE, tally, afterId, null);
        }
        log.info("출연 회원 Backfill 시작 afterId={}, limit={}", afterId, limit);
        long cursor = afterId;
        int remaining = limit;
        boolean completed = false;
        while (remaining > 0) {
            int size = Math.min(PAGE_SIZE, remaining);
            MemberPageDto page = fetch(client, cursor, size, tally);
            verify(page, cursor, size, tally);
            for (UserDto user : page.items()) {
                tally.apply(user);
            }
            remaining -= page.items().size();
            if (page.nextAfterId() == null) {
                completed = true;
                break;
            }
            cursor = page.nextAfterId();
        }
        AppearanceUserBackfillResDto result = tally.result(completed ? null : cursor, completed);
        log.info("출연 회원 Backfill 종료 scanned={}, created={}, updated={}, ignored={}, failed={}, lastProcessedId={}, "
                        + "nextAfterId={}, completed={}", result.scanned(), result.created(), result.updated(),
                result.ignored(), result.failed(), result.lastProcessedId(), result.nextAfterId(), result.completed());
        return result;
    }

    /** 4xx(REJECTED)는 내부 API 키·계약 불일치라 다시 해도 같고, 나머지(연결·시간 초과·5xx·읽을 수 없는 응답)는 일시 장애다 */
    private MemberPageDto fetch(MemberApiClient client, long cursor, int size, Tally tally) {
        try {
            return client.getMembers(cursor, size);
        } catch (MemberApiCallException e) {
            boolean rejected = e.getReason() == MemberApiCallException.Reason.REJECTED;
            log.warn("회원 내부 API를 읽지 못해 출연 회원 Backfill을 멈춘다 afterId={}, reason={}, status={}, code={}",
                    cursor, e.getReason(), e.getStatus(), e.getCode());
            throw abort(rejected ? AppearanceErrorCode.MEMBER_SOURCE_REJECTED : AppearanceErrorCode.MEMBER_SOURCE_UNAVAILABLE,
                    tally, cursor, e);
        }
    }

    /**
     * 키셋 계약: 요청 크기 이하, ID는 null이 아니고 afterId보다 크며 엄격히 오름차순,
     * nextAfterId가 있으면 비어 있지 않은 페이지의 마지막 ID와 같다(커서가 반드시 전진한다).
     * 어기면 그 페이지의 어떤 회원도 반영하지 않고 멈춘다(잘못된 응답으로 같은 페이지를 반복하지 않는다).
     */
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
            log.error("회원 내부 API 응답이 키셋 계약과 맞지 않아 출연 회원 Backfill을 멈춘다 afterId={}, size={}, 사유={}",
                    cursor, size, violation);
            throw abort(AppearanceErrorCode.MEMBER_SOURCE_UNAVAILABLE, tally, cursor, new IllegalStateException(violation));
        }
    }

    private AppearanceUserBackfillException abort(AppearanceErrorCode code, Tally tally, long resumeAfterId,
            Throwable cause) {
        AppearanceUserBackfillResDto progress = tally.result(resumeAfterId, false);
        log.warn("출연 회원 Backfill 중단 code={}, scanned={}, created={}, updated={}, failed={}, lastProcessedId={}, "
                        + "resumeAfterId={}", code, progress.scanned(), progress.created(), progress.updated(),
                progress.failed(), progress.lastProcessedId(), resumeAfterId);
        return new AppearanceUserBackfillException(code, progress, resumeAfterId, cause);
    }

    /** 한 번 실행의 집계 (실행마다 새로 만든다) */
    private final class Tally {

        private long scanned;
        private long created;
        private long updated;
        private long ignored;
        private long failed;
        private final List<Long> failedIds = new ArrayList<>();
        private Long lastProcessedId;

        /** 회원 하나를 반영한다. 실패하면 failed로 세고 다음 회원을 계속한다(예외 메시지에 회원 값이 담길 수 있어 로그에 남기지 않는다) */
        void apply(UserDto user) {
            scanned++;
            lastProcessedId = user.id();
            try {
                switch (transactionTemplate.execute(status -> appearanceUserSyncUseCase.applySnapshot(user))) {
                    case CREATED -> created++;
                    case UPDATED -> updated++;
                    case IGNORED -> ignored++;
                    case null -> throw new IllegalStateException("반영 결과가 없다");
                }
            } catch (RuntimeException e) {
                failed++;
                if (failedIds.size() < MAX_FAILED_IDS) {
                    failedIds.add(user.id());
                }
                log.warn("출연 회원 Backfill 회원 반영 실패, 다음 회원을 계속한다 userId={}, error={}", user.id(),
                        e.getClass().getSimpleName());
            }
        }

        AppearanceUserBackfillResDto result(Long nextAfterId, boolean completed) {
            return new AppearanceUserBackfillResDto(scanned, created, updated, ignored, failed, failedIds,
                    lastProcessedId, nextAfterId, completed);
        }
    }
}
