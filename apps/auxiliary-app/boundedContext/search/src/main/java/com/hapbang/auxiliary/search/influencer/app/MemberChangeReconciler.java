package com.hapbang.auxiliary.search.influencer.app;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.hapbang.auxiliary.search.common.app.IndexCatchUp;
import com.hapbang.auxiliary.search.common.app.SearchJob;
import com.hapbang.auxiliary.search.common.app.SearchJobRunner;
import com.hapbang.auxiliary.search.influencer.app.InfluencerMemberIndexer.MemberIndexResult;
import com.hapbang.auxiliary.search.common.out.DocumentRejectedException;
import com.hapbang.auxiliary.search.common.out.IndexNotReadyException;
import com.hapbang.auxiliary.search.influencer.out.IndexedMember;
import com.hapbang.auxiliary.search.influencer.out.InfluencerDocumentStore;
import com.hapbang.auxiliary.search.influencer.out.InfluencerIndexSetup;
import com.hapbang.auxiliary.search.influencer.out.MemberChangeSource;
import com.hapbang.auxiliary.search.influencer.out.SyncCursor;
import com.hapbang.auxiliary.search.influencer.out.SyncCursorStore;
import com.hapbang.auxiliary.search.common.out.WriteOutcome;
import com.hapbang.shared.member.dto.MemberChangePageDto;
import com.hapbang.shared.member.dto.UserDto;

import lombok.extern.slf4j.Slf4j;

/**
 * 증분 보정(#772). 회원 이벤트를 놓쳐도 검색 문서가 원본과 저절로 맞춰지게, 10분마다 회원 변경분(#769)을 저장한 커서부터 끝까지 읽어 반영한다.
 * 한 pod에서만 돈다({@link SearchJob#MEMBER_RECONCILE}).
 * <ul>
 *     <li><b>같은 반영 코드</b>: 받은 {@link UserDto}마다 이벤트와 같은 {@link InfluencerMemberIndexer#apply}를 부른다. 회원 version 조건부
 *     쓰기라 이벤트로 이미 반영한 회원은 noop이고, 놓친 회원만 바뀐다.</li>
 *     <li><b>커서</b>: 페이지마다 회원 앱이 준 다음 커서를 저장한다({@link SyncCursorStore}). 중간에 멈추면 다음 회차는 저장한 커서부터 이어 간다.</li>
 *     <li><b>1분 겹쳐 읽기</b>: 저장한 커서 시각에서 1분 뺀 시각(회원 ID 0)부터 읽는다. 늦게 커밋된 변경이 지나간 커서 앞에 나타나기 때문이다.
 *     겹쳐 읽은 회원은 version으로 걸러진다.</li>
 *     <li><b>처음</b>: 커서가 없으면 지금 시각부터 읽는다. 이전 회원은 #773 처음 적재가 맡는다.</li>
 *     <li><b>보정 건수</b>(#772 Q1): 반영 전에 그 페이지 문서를 한 번에 읽어 보이는 내용이 실제로 다른지로 가른다({@link ReconcileKind}).
 *     보정한 회원은 INFO 한 줄을 남긴다.</li>
 *     <li><b>바깥에서 시작 시각 정하기</b>: {@link #runFrom}(#773 재색인 뒤 따라잡기). 공통 재색인의 따라잡기 부품({@link IndexCatchUp})이다(#868).</li>
 * </ul>
 * 한계: 보정 건수는 페이지 앞에서 한 번 읽은 문서와 비교한다. 그 페이지를 반영하는 몇 초 사이에 같은 회원의 이벤트가 먼저 반영되면(이벤트 도착 직전에
 * 보정이 읽었거나, 같은 회원이 몇 초 안에 두 번 바뀜) 놓친 이벤트가 아닌데도 보정으로 셀 수 있다.
 */
@Slf4j
@Service
public class MemberChangeReconciler implements IndexCatchUp {

    /** 변경분 한 페이지 크기(회원 API 상한) */
    static final int PAGE_SIZE = MemberChangePageDto.MAX_SIZE;

    /** 겹쳐 읽는 시간. 저장(flush)부터 커밋까지 이보다 오래 걸린 변경은 놓칠 수 있다(그때는 회원 이벤트가 반영한다, #769) */
    static final Duration OVERLAP = Duration.ofMinutes(1);

    /** 커서 시각의 시간대. 회원 앱이 updated_at을 찍는 시간대와 같다 */
    static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private static final String CURSOR = SyncCursor.MEMBER_CHANGES;

    private final InfluencerIndexSetup indexSetup;
    private final InfluencerDocumentStore documentStore;
    private final InfluencerMemberIndexer indexer;
    private final MemberChangeSource source;
    private final SyncCursorStore cursorStore;
    private final SearchJobRunner jobRunner;
    private final SearchSyncMetrics metrics;
    private final Clock clock;

    @Autowired
    public MemberChangeReconciler(InfluencerIndexSetup indexSetup, InfluencerDocumentStore documentStore,
            InfluencerMemberIndexer indexer, MemberChangeSource source, SyncCursorStore cursorStore,
            SearchJobRunner jobRunner, SearchSyncMetrics metrics) {
        this(indexSetup, documentStore, indexer, source, cursorStore, jobRunner, metrics, Clock.system(ZONE));
    }

    MemberChangeReconciler(InfluencerIndexSetup indexSetup, InfluencerDocumentStore documentStore,
            InfluencerMemberIndexer indexer, MemberChangeSource source, SyncCursorStore cursorStore,
            SearchJobRunner jobRunner, SearchSyncMetrics metrics, Clock clock) {
        this.indexSetup = indexSetup;
        this.documentStore = documentStore;
        this.indexer = indexer;
        this.source = source;
        this.cursorStore = cursorStore;
        this.jobRunner = jobRunner;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** 한 pod에서만 실행한다. 실패는 다음 회차에 저장한 커서부터 다시 한다 */
    public void run() {
        jobRunner.run(SearchJob.MEMBER_RECONCILE, () -> reconcile(null));
    }

    /**
     * 커서를 이 시각으로 옮기고 한 번 돈다(#773: 새 index로 alias를 바꾼 뒤 시각 T 이후 따라잡기). 겹쳐 읽기를 빼지 않고 이 시각부터 읽으므로,
     * 여유는 부르는 쪽이 넣는다(T − 1분). 정기 회차와 같은 락을 쓴다.
     *
     * @param from 이 시각 뒤에 바뀐 회원부터 읽는다(회원 앱 시간대)
     * @return 끝까지 따라잡았으면 {@link CatchUp#CAUGHT_UP}. 다른 회차가 락을 쥐고 있어 돌지 못했으면 {@link CatchUp#BUSY}(커서 그대로라 부르는
     * 쪽이 다시 부른다). 중간에 실패했으면 {@link CatchUp#FAILED}(WARN, 커서는 그 시각 또는 마지막으로 끝낸 페이지에 있어 다음 정기 회차가 이어 간다)
     */
    /** 재색인 따라잡기도 정기 보정과 같은 겹쳐 읽기({@link #OVERLAP})를 쓴다 */
    @Override
    public Duration overlap() {
        return OVERLAP;
    }

    @Override
    public CatchUp runFrom(LocalDateTime from) {
        Objects.requireNonNull(from, "from은 필수입니다.");
        AtomicBoolean caughtUp = new AtomicBoolean();
        boolean ran = jobRunner.run(SearchJob.MEMBER_RECONCILE, () -> {
            reconcile(from);
            caughtUp.set(true);
        });
        if (!ran) {
            return CatchUp.BUSY;
        }
        return caughtUp.get() ? CatchUp.CAUGHT_UP : CatchUp.FAILED;
    }

    /**
     * 보정한다. 락 없이 돌므로 바깥에서는 {@link #run}·{@link #runFrom}만 부른다(커서를 함께 쓰는 회차가 겹치지 않게). 테스트는 같은 패키지에서
     * 바로 부른다.
     *
     * @param from 바깥에서 정한 시작 시각. null이면 저장한 커서 − 1분(없으면 지금 시각)부터
     * @throws com.hapbang.auxiliary.search.common.out.IndexNotReadyException alias가 없는 경우(처음 적재 전, #773). 커서를 만들지 않는다
     * @throws IllegalStateException                                 ES에 읽거나 쓰지 못한 경우, 회원 앱 응답의 다음 커서가 빠진 경우
     *                                                               (커서는 마지막으로 끝낸 페이지에 있다)
     * @throws com.hapbang.shared.member.out.MemberApiCallException 회원 앱을 부르지 못한 경우
     */
    ReconcileResult reconcile(LocalDateTime from) {
        long started = System.nanoTime();
        try {
            ReconcileResult result = reconcileOnce(from);
            metrics.reconciled(result, Duration.ofNanos(System.nanoTime() - started));
            return result;
        } catch (IndexNotReadyException e) {
            // 처음 적재 전은 실패가 아니라 할 일이 없는 것이다. 실패 지표에 넣지 않는다(#773 Q1). 준비 확인이 먼저라 커서도 만들지 않았다
            throw e;
        } catch (RuntimeException e) {
            metrics.reconcileFailed(Duration.ofNanos(System.nanoTime() - started));
            throw e;
        }
    }

    private ReconcileResult reconcileOnce(LocalDateTime from) {
        indexSetup.ensureReady();
        LocalDateTime afterUpdatedAt = startFrom(from);
        long afterId = SyncCursor.READ_FROM_ID;

        Map<ReconcileKind, Long> counts = new EnumMap<>(ReconcileKind.class);
        int pages = 0;
        long read = 0;
        MemberChangePageDto page;
        do {
            page = source.changes(afterUpdatedAt, afterId, PAGE_SIZE);
            requireNextCursor(page);
            applyPage(page, counts);
            pages++;
            read += page.items().size();
            if (page.nextAfterUpdatedAt() != null) {
                afterUpdatedAt = page.nextAfterUpdatedAt();
                afterId = page.nextAfterId();
                cursorStore.moveTo(CURSOR, afterUpdatedAt, afterId);
            }
        } while (page.hasNext());
        cursorStore.caughtUp(CURSOR, LocalDateTime.now(clock));

        ReconcileResult result = new ReconcileResult(pages, read, counts);
        log.info("검색 증분 보정을 했다 pages={}, read={}, inserted={}, hidden={}, overwritten={}, versionOnly={}, "
                        + "alreadyApplied={}, skipped={}, cursor={}/{}", result.pages(), result.read(),
                result.count(ReconcileKind.INSERTED), result.count(ReconcileKind.HIDDEN),
                result.count(ReconcileKind.OVERWRITTEN), result.count(ReconcileKind.VERSION_ONLY),
                result.count(ReconcileKind.ALREADY_APPLIED), result.count(ReconcileKind.SKIPPED), afterUpdatedAt,
                afterId);
        return result;
    }

    /** 이번 회차에 읽기 시작할 시각. 커서가 없으면 지금 시각으로 만들고, 바깥에서 정했으면 그 시각으로 옮긴다 */
    private LocalDateTime startFrom(LocalDateTime from) {
        if (from != null) {
            cursorStore.moveTo(CURSOR, from, SyncCursor.READ_FROM_ID);
            return from;
        }
        SyncCursor cursor = cursorStore.find(CURSOR).orElseGet(() -> {
            // 처음: 지금 시각을 먼저 저장해야, 이번 회차에 변경이 없어도 다음 회차가 그사이 변경을 놓치지 않는다
            LocalDateTime now = LocalDateTime.now(clock);
            cursorStore.moveTo(CURSOR, now, SyncCursor.READ_FROM_ID);
            log.info("검색 증분 보정 커서가 없어 지금 시각부터 읽는다 from={}", now);
            return new SyncCursor(CURSOR, now, SyncCursor.READ_FROM_ID, null);
        });
        return cursor.readFrom(OVERLAP);
    }

    /**
     * 다음 커서가 빠진 응답을 거절한다(#769 계약: 항목이 있으면 커서를 주고, 빈 페이지는 hasNext=false). 받아들이면 같은 커서로 끝없이 다시 부르거나
     * 커서를 저장하지 못한다.
     */
    private static void requireNextCursor(MemberChangePageDto page) {
        boolean noCursor = page.nextAfterUpdatedAt() == null || page.nextAfterId() == null;
        boolean partialCursor = (page.nextAfterUpdatedAt() == null) != (page.nextAfterId() == null);
        if (partialCursor || (noCursor && (page.hasNext() || !page.items().isEmpty()))) {
            throw new IllegalStateException("회원 변경분 응답에 다음 커서가 없습니다 items=" + page.items().size()
                    + ", hasNext=" + page.hasNext() + ", nextAfterUpdatedAt=" + page.nextAfterUpdatedAt()
                    + ", nextAfterId=" + page.nextAfterId());
        }
    }

    private void applyPage(MemberChangePageDto page, Map<ReconcileKind, Long> counts) {
        Map<Long, IndexedMember> before = documentStore.findMembers(page.items().stream()
                .map(UserDto::id).filter(Objects::nonNull).distinct().toList());
        for (UserDto user : page.items()) {
            ReconcileKind kind = apply(user, user.id() == null ? null : before.get(user.id()));
            counts.merge(kind, 1L, Long::sum);
        }
    }

    private ReconcileKind apply(UserDto user, IndexedMember before) {
        MemberIndexResult result;
        try {
            result = indexer.apply(user);
        } catch (IllegalArgumentException e) {
            // 회원 정보가 빠졌다. 다시 읽어도 같으므로 건너뛰고 다음 회원을 계속한다(멈추면 커서가 영영 넘어가지 못한다)
            log.warn("회원 정보가 빠져 검색 보정에서 건너뛴다 userId={}", user.id(), e);
            return ReconcileKind.SKIPPED;
        } catch (DocumentRejectedException e) {
            // ES가 이 회원의 값 때문에 거절했다. 다시 써도 같으므로 건너뛴다. 배포 실수(script 오류 등)·ES 장애는 여기에 들지 않아 회차를 멈춘다
            log.warn("ES가 거절해 검색 보정에서 건너뛴다 userId={}", user.id(), e);
            return ReconcileKind.SKIPPED;
        }
        ReconcileKind kind = result.outcome() == WriteOutcome.NOOP
                ? ReconcileKind.ALREADY_APPLIED
                : classify(user, result.visible(), before);
        if (kind.isFix()) {
            log.info("회원 이벤트를 놓친 검색 문서를 보정했다 userId={}, kind={}, memberVersion={}", user.id(), kind.tag(),
                    user.version());
        }
        return kind;
    }

    /**
     * 쓴 회원이 검색에 보이는 내용으로 무엇이 바뀌었는지 가른다(쓰기가 noop이 아니었을 때).
     *
     * @param visible 이번에 보이게 썼는지
     * @param before  쓰기 전 문서. 없으면 null
     */
    static ReconcileKind classify(UserDto user, boolean visible, IndexedMember before) {
        boolean wasVisible = before != null && !before.hidden();
        if (visible) {
            if (!wasVisible) {
                return ReconcileKind.INSERTED;
            }
            return sameProfile(user, before) ? ReconcileKind.VERSION_ONLY : ReconcileKind.OVERWRITTEN;
        }
        // 숨김: 보이던 회원만 보정이다. 없던 문서·이미 숨긴 문서는 검색에 보이는 것이 그대로다
        return wasVisible ? ReconcileKind.HIDDEN : ReconcileKind.VERSION_ONLY;
    }

    private static boolean sameProfile(UserDto user, IndexedMember before) {
        return Objects.equals(user.nickname(), before.nickname())
                && Objects.equals(user.bio(), before.bio())
                && Objects.equals(user.role().name(), before.role())
                && user.contentCategories().equals(before.categoryCodes());
    }

    /**
     * @param pages  읽은 페이지 수
     * @param read   읽은 회원 수(겹쳐 읽은 회원 포함)
     * @param counts 결과별 회원 수
     */
    public record ReconcileResult(int pages, long read, Map<ReconcileKind, Long> counts) {

        public ReconcileResult {
            counts = Map.copyOf(counts);
        }

        public long count(ReconcileKind kind) {
            return counts.getOrDefault(kind, 0L);
        }

        /** 이벤트가 빠져 보정한 회원 수(넣음·숨김·덮음) */
        public long fixed() {
            return counts.entrySet().stream().filter(e -> e.getKey().isFix()).mapToLong(Map.Entry::getValue).sum();
        }
    }
}
