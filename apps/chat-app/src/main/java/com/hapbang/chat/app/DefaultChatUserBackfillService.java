package com.hapbang.chat.app;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import com.hapbang.chat.app.dto.ChatUserBackfillResult;
import com.hapbang.chat.app.dto.ChatUserSyncResult;
import com.hapbang.chat.app.exception.ChatErrorCode;
import com.hapbang.chat.app.exception.ChatException;
import com.hapbang.chat.app.exception.ChatUserBackfillException;
import com.hapbang.shared.member.dto.MemberPageDto;
import com.hapbang.shared.member.dto.UserDto;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 기존 회원 백필의 업무 구현(설계 문서 5.4). 회원 원본 스냅샷({@link ChatMemberSnapshotSource})을 ID 오름차순 키셋으로 읽어
 * 회원마다 {@link ChatUserSyncService#syncBackfill}로 반영한다. billing 백필과 같은 구조다.
 * <ul>
 *     <li>트랜잭션을 열지 않는다: 회원 원본을 읽는 동안 chat DB 트랜잭션이 없고, 회원마다 짧은 트랜잭션을 연다.</li>
 *     <li>한 번에 최대 {@code max-limit}명. 끝나지 않으면 결과의 nextAfterId를 다음 실행의 afterId로 넘긴다.</li>
 *     <li>회원 하나의 반영 실패는 failed·failedIds로 남기고 다음 회원을 계속한다. 실패가 있으면 완료로 표시하지 않는다.</li>
 *     <li>회원 원본 장애·키셋 계약 위반은 실행 전체를 멈추고 집계·재개 커서를 담은 예외를 던진다.</li>
 *     <li>같은 앱에서 동시에 두 번 실행하지 않는다(replica 1개 전제). 회원 이벤트 소비는 막지 않는다.</li>
 * </ul>
 * 실행 기록은 DB에 남기지 않고 응답과 로그로 남긴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(ChatUserBackfillProperties.class)
public class DefaultChatUserBackfillService implements ChatUserBackfillService {

    private final ChatMemberSnapshotSource chatMemberSnapshotSource;
    private final ChatUserSyncService chatUserSyncService;
    private final ChatUserBackfillProperties properties;
    // 빈(싱글턴)이 가진 실행 중 표시라 요청 사이에서 공유된다
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Override
    public ChatUserBackfillResult backfill(long afterId, int limit) {
        if (afterId < 0 || limit < 1 || limit > properties.maxLimit()) {
            throw new ChatException(ChatErrorCode.INVALID_REQUEST,
                    "afterId는 0 이상, limit은 1~" + properties.maxLimit() + "이어야 합니다.");
        }
        if (!running.compareAndSet(false, true)) {
            throw new ChatException(ChatErrorCode.CHAT_USER_BACKFILL_ALREADY_RUNNING);
        }
        try {
            return run(afterId, limit);
        } finally {
            running.set(false);
        }
    }

    private ChatUserBackfillResult run(long afterId, int limit) {
        Tally tally = new Tally();
        if (!chatMemberSnapshotSource.isConfigured()) {
            log.error("회원 내부 API Client가 없어 채팅 회원 백필을 실행할 수 없습니다(hapbang.clients.member.base-url 미설정).");
            throw abort(ChatErrorCode.MEMBER_SOURCE_UNAVAILABLE, tally, afterId, null);
        }
        log.info("채팅 회원 백필 시작 afterId={}, limit={}", afterId, limit);
        long cursor = afterId;
        int remaining = limit;
        boolean reachedEnd = false;
        while (remaining > 0) {
            int size = Math.min(properties.pageSize(), remaining);
            MemberPageDto page = fetch(cursor, size, tally);
            verify(page, cursor, size, tally);
            for (UserDto user : page.items()) {
                tally.apply(user);
            }
            remaining -= page.items().size();
            if (page.nextAfterId() == null) {
                reachedEnd = true;
                break;
            }
            cursor = page.nextAfterId();
        }
        ChatUserBackfillResult result = tally.result(reachedEnd ? null : cursor, reachedEnd);
        log.info("채팅 회원 백필 종료 scanned={}, created={}, updated={}, skippedStale={}, failed={}, failedIds={}, "
                        + "lastProcessedId={}, nextAfterId={}, reachedEnd={}, completed={}", result.scanned(),
                result.created(), result.updated(), result.skippedStale(), result.failed(), result.failedIds(),
                result.lastProcessedId(), result.nextAfterId(), result.reachedEnd(), result.completed());
        return result;
    }

    // 4xx(REJECTED)는 내부 API 키·계약 불일치라 다시 시도해도 같고, 나머지(연결·시간 초과·5xx·읽을 수 없는 응답)는 일시 장애다
    private MemberPageDto fetch(long cursor, int size, Tally tally) {
        try {
            return chatMemberSnapshotSource.readPage(cursor, size);
        } catch (ChatMemberSourceException e) {
            boolean rejected = e.getReason() == ChatMemberSourceException.Reason.REJECTED;
            log.warn("회원 내부 API 조회 실패로 채팅 회원 백필을 멈춥니다: afterId={}, reason={}, status={}, code={}", cursor,
                    e.getReason(), e.getStatus(), e.getCode(), e);
            throw abort(rejected ? ChatErrorCode.MEMBER_SOURCE_REJECTED : ChatErrorCode.MEMBER_SOURCE_UNAVAILABLE,
                    tally, cursor, e);
        }
    }

    // 키셋 계약: 요청 크기 이하, ID는 null이 아니고 afterId보다 크며 엄격히 오름차순,
    // nextAfterId가 있으면 비어 있지 않은 페이지의 마지막 ID와 같다(커서가 반드시 전진한다).
    // 어기면 페이지의 어떤 회원도 반영하지 않고 실행을 멈춘다
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
                if (user == null || user.id() == null || user.id() <= previous) {
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
            log.error("회원 내부 API 응답이 키셋 계약과 맞지 않아 채팅 회원 백필을 멈춥니다: afterId={}, size={}, 사유={}", cursor,
                    size, violation);
            throw abort(ChatErrorCode.MEMBER_SOURCE_UNAVAILABLE, tally, cursor, null);
        }
    }

    private ChatUserBackfillException abort(ChatErrorCode code, Tally tally, long resumeAfterId, Throwable cause) {
        ChatUserBackfillResult progress = tally.result(resumeAfterId, false);
        log.warn("채팅 회원 백필 중단 code={}, scanned={}, created={}, updated={}, failed={}, failedIds={}, "
                        + "lastProcessedId={}, resumeAfterId={}, cause={}", code, progress.scanned(), progress.created(),
                progress.updated(), progress.failed(), progress.failedIds(), progress.lastProcessedId(), resumeAfterId,
                cause == null ? null : cause.getClass().getSimpleName());
        return new ChatUserBackfillException(code, progress, resumeAfterId);
    }

    /** 한 번 실행의 집계(실행마다 새로 만든다) */
    private final class Tally {

        private long scanned;
        private long created;
        private long updated;
        private long skippedStale;
        private long failed;
        private final List<Long> failedIds = new ArrayList<>();
        private Long lastProcessedId;

        // 회원 하나를 반영한다. 실패하면 failed로 세고 다음 회원을 계속한다(예외 메시지에 회원 값이 담길 수 있어 로그에 남기지 않는다)
        void apply(UserDto user) {
            scanned++;
            lastProcessedId = user.id();
            try {
                ChatUserSyncResult result = chatUserSyncService.syncBackfill(user);
                switch (result.outcome()) {
                    case CREATED -> created++;
                    case UPDATED -> updated++;
                    case SKIPPED_STALE, SKIPPED_EXISTING -> skippedStale++;
                }
            } catch (RuntimeException e) {
                failed++;
                failedIds.add(user.id());
                log.warn("채팅 회원 백필 회원 반영 실패, 다음 회원을 계속한다 userId={}, error={}", user.id(),
                        e.getClass().getSimpleName());
            }
        }

        ChatUserBackfillResult result(Long nextAfterId, boolean reachedEnd) {
            return new ChatUserBackfillResult(scanned, created, updated, skippedStale, failed, failedIds,
                    lastProcessedId, nextAfterId, reachedEnd, reachedEnd && failed == 0);
        }
    }
}
