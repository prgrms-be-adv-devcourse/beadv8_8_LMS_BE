package com.hapbang.auxiliary.search.lecture.app;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.hapbang.auxiliary.search.common.app.SearchJob;
import com.hapbang.auxiliary.search.common.app.SearchJobRunner;
import com.hapbang.auxiliary.search.common.out.IndexNotReadyException;
import com.hapbang.auxiliary.search.lecture.app.SellerIndexer.SellerIndexResult;
import com.hapbang.auxiliary.search.lecture.out.SearchMemberCopyStore;
import com.hapbang.auxiliary.search.lecture.out.Seller;
import com.hapbang.auxiliary.search.out.MemberChangeSource;
import com.hapbang.auxiliary.search.out.SyncCursor;
import com.hapbang.auxiliary.search.out.SyncCursorStore;
import com.hapbang.shared.member.dto.MemberChangePageDto;
import com.hapbang.shared.member.dto.UserDto;

import lombok.extern.slf4j.Slf4j;

/**
 * 판매자 사본 증분 보정(#884 Q3 a). 회원 이벤트를 놓쳐도(DLT) 회원 사본과 그 판매자의 강의 문서가 원본과 맞춰지게, 10분마다 회원 변경분(#769)을 저장한
 * 커서부터 끝까지 읽어 반영한다. 한 pod에서만 돈다({@link SearchJob#SELLER_RECONCILE}).
 * <ul>
 *     <li><b>인플루언서 증분 보정과 따로 돈다</b>: 커서({@link SyncCursor#SELLER_CHANGES})·락이 따로라 인플루언서 index가 없거나 그쪽 쓰기가
 *     실패해도 판매자 보정은 돈다. 같은 회원 변경분 API를 한 번 더 읽는다(변경이 없으면 요청 1번).</li>
 *     <li><b>같은 반영 코드</b>: 사본보다 새 version인 회원만 이벤트와 같은 {@link SellerIndexer#apply}로 반영한다. 페이지마다 사본 version을
 *     한 번에 읽어 이벤트가 이미 반영한 회원(겹쳐 읽은 회원 포함)은 부르지 않는다. 같은 version을 다시 반영하면 사본이 재시도로 보고 판매자의 강의
 *     문서를 다시 맞추기({@code update_by_query}) 때문이다. 그 문서 칸은 리뷰 보정의 칸 맞추기가 본다. 강의 alias가 없으면(처음 적재 전) 사본만
 *     고치고 다음 회원을 계속한다.</li>
 *     <li><b>커서·1분 겹쳐 읽기·처음</b>: 인플루언서 증분 보정(#772)과 같다. 커서가 없으면 지금 시각부터 읽고, 그 전 회원은 처음 적재의 회원 사본
 *     맞추기(#884 Q2 a)가 맡는다.</li>
 * </ul>
 * 보정 건수: 강의 문서가 실제로 바뀐 회원은 {@link LectureReconcileKind#SELLER_FIXED}(INFO 한 줄), 사본만 바뀐 회원(판매자가 아니거나
 * 처음 적재 전)은 {@link LectureReconcileKind#COPIED}, 사본이 이미 같거나 더 새 version이면 {@link LectureReconcileKind#ALREADY_APPLIED}다.
 */
@Slf4j
@Service
public class SellerChangeReconciler {

    /** 변경분 한 페이지 크기(회원 API 상한) */
    static final int PAGE_SIZE = MemberChangePageDto.MAX_SIZE;

    /** 겹쳐 읽는 시간. 인플루언서 증분 보정과 같다(저장부터 커밋까지 이보다 오래 걸린 변경은 회원 이벤트가 반영한다, #769) */
    static final Duration OVERLAP = Duration.ofMinutes(1);

    private static final String CURSOR = SyncCursor.SELLER_CHANGES;

    private final SellerIndexer sellerIndexer;
    private final SearchMemberCopyStore members;
    private final MemberChangeSource source;
    private final SyncCursorStore cursorStore;
    private final SearchJobRunner jobRunner;
    private final LectureSyncMetrics metrics;
    private final Clock clock;

    @Autowired
    public SellerChangeReconciler(SellerIndexer sellerIndexer, SearchMemberCopyStore members,
            MemberChangeSource source, SyncCursorStore cursorStore, SearchJobRunner jobRunner,
            LectureSyncMetrics metrics) {
        this(sellerIndexer, members, source, cursorStore, jobRunner, metrics, Clock.system(SellerIndexer.ZONE));
    }

    SellerChangeReconciler(SellerIndexer sellerIndexer, SearchMemberCopyStore members, MemberChangeSource source,
            SyncCursorStore cursorStore, SearchJobRunner jobRunner, LectureSyncMetrics metrics, Clock clock) {
        this.sellerIndexer = sellerIndexer;
        this.members = members;
        this.source = source;
        this.cursorStore = cursorStore;
        this.jobRunner = jobRunner;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** 한 pod에서만 실행한다. 실패는 다음 회차에 저장한 커서부터 다시 한다 */
    public void run() {
        jobRunner.run(SearchJob.SELLER_RECONCILE, this::reconcile);
    }

    /**
     * 보정한다. 락 없이 돌므로 바깥에서는 {@link #run}만 부른다(커서를 함께 쓰는 회차가 겹치지 않게). 테스트는 같은 패키지에서 부른다.
     *
     * @throws IllegalStateException 강의 문서를 고치지 못한 경우, 회원 앱 응답의 다음 커서가 빠진 경우(커서는 마지막으로 끝낸 페이지에 있다)
     * @throws com.hapbang.shared.member.out.MemberApiCallException 회원 앱을 부르지 못한 경우
     */
    LectureReconcileResult reconcile() {
        long started = System.nanoTime();
        try {
            LectureReconcileResult result = reconcileOnce();
            metrics.reconciled(LectureSyncMetrics.JOB_SELLER, result, Duration.ofNanos(System.nanoTime() - started));
            return result;
        } catch (RuntimeException e) {
            metrics.reconcileFailed(LectureSyncMetrics.JOB_SELLER, Duration.ofNanos(System.nanoTime() - started));
            throw e;
        }
    }

    private LectureReconcileResult reconcileOnce() {
        LocalDateTime afterUpdatedAt = startFrom();
        long afterId = SyncCursor.READ_FROM_ID;
        Map<LectureReconcileKind, Long> counts = new EnumMap<>(LectureReconcileKind.class);
        long read = 0;
        MemberChangePageDto page;
        do {
            page = source.changes(afterUpdatedAt, afterId, PAGE_SIZE);
            MemberChangeSource.requireNextCursor(page);
            Map<Long, Seller> copies = members.findSellers(
                    page.items().stream().map(UserDto::id).filter(Objects::nonNull).distinct().toList());
            for (UserDto user : page.items()) {
                Seller copy = user.id() == null ? null : copies.get(user.id());
                counts.merge(alreadyCopied(user, copy) ? LectureReconcileKind.ALREADY_APPLIED : apply(user), 1L,
                        Long::sum);
            }
            read += page.items().size();
            if (page.nextAfterUpdatedAt() != null) {
                afterUpdatedAt = page.nextAfterUpdatedAt();
                afterId = page.nextAfterId();
                cursorStore.moveTo(CURSOR, afterUpdatedAt, afterId);
            }
        } while (page.hasNext());
        cursorStore.caughtUp(CURSOR, LocalDateTime.now(clock));

        LectureReconcileResult result = new LectureReconcileResult(read, counts);
        log.info("강의 판매자 사본 보정을 했다 read={}, sellerFixed={}, copied={}, alreadyApplied={}, skipped={}, cursor={}/{}",
                read, result.count(LectureReconcileKind.SELLER_FIXED), result.count(LectureReconcileKind.COPIED),
                result.count(LectureReconcileKind.ALREADY_APPLIED), result.count(LectureReconcileKind.SKIPPED),
                afterUpdatedAt, afterId);
        return result;
    }

    /** 이번 회차에 읽기 시작할 시각. 커서가 없으면 지금 시각으로 먼저 저장한다(이번 회차에 변경이 없어도 다음 회차가 그사이 변경을 놓치지 않게) */
    private LocalDateTime startFrom() {
        SyncCursor cursor = cursorStore.find(CURSOR).orElseGet(() -> {
            LocalDateTime now = LocalDateTime.now(clock);
            cursorStore.moveTo(CURSOR, now, SyncCursor.READ_FROM_ID);
            log.info("강의 판매자 사본 보정 커서가 없어 지금 시각부터 읽는다 from={}", now);
            return new SyncCursor(CURSOR, now, SyncCursor.READ_FROM_ID, null);
        });
        return cursor.readFrom(OVERLAP);
    }

    /** 사본이 이미 같거나 더 새 version이다(이벤트가 반영함). version 없는 정보는 가릴 수 없어 반영한다({@code UserDto} 규칙) */
    static boolean alreadyCopied(UserDto user, Seller copy) {
        return copy != null && user.version() != null && copy.version() != null && user.version() <= copy.version();
    }

    private LectureReconcileKind apply(UserDto user) {
        SellerIndexResult result;
        try {
            result = sellerIndexer.apply(user);
        } catch (IllegalArgumentException e) {
            // 회원 ID·상태가 빠졌다. 다시 읽어도 같으므로 건너뛰고 다음 회원을 계속한다(멈추면 커서가 영영 넘어가지 못한다)
            log.warn("회원 정보가 빠져 강의 판매자 사본 보정에서 건너뛴다 userId={}", user == null ? null : user.id(), e);
            return LectureReconcileKind.SKIPPED;
        } catch (IndexNotReadyException e) {
            // 처음 적재 전이다. 사본은 고쳤고 처음 적재가 사본으로 문서를 만든다
            return LectureReconcileKind.COPIED;
        }
        if (!result.sellerChanged()) {
            return LectureReconcileKind.ALREADY_APPLIED;
        }
        if (result.documents() == 0) {
            return LectureReconcileKind.COPIED;
        }
        log.info("회원 이벤트를 놓친 판매자 정보를 강의 검색 문서에 보정했다 userId={}, memberVersion={}, documents={}",
                result.userId(), result.memberVersion(), result.documents());
        return LectureReconcileKind.SELLER_FIXED;
    }
}
