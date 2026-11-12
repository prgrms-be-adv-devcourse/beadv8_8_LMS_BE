package com.hapbang.auxiliary.search.lecture.app;

import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.hapbang.auxiliary.search.common.app.SearchJob;
import com.hapbang.auxiliary.search.common.app.SearchJobRunner;
import com.hapbang.auxiliary.search.common.out.DocumentRejectedException;
import com.hapbang.auxiliary.search.common.out.IndexNotReadyException;
import com.hapbang.auxiliary.search.common.out.WriteOutcome;
import com.hapbang.auxiliary.search.lecture.app.LectureIndexer.LectureIndexResult;
import com.hapbang.auxiliary.search.lecture.out.IndexedLecture;
import com.hapbang.auxiliary.search.lecture.out.LectureDocumentStore;
import com.hapbang.auxiliary.search.lecture.out.LectureIndexSetup;
import com.hapbang.auxiliary.search.lecture.out.LectureSyncSource;
import com.hapbang.shared.lecture.dto.LectureSyncDto;
import com.hapbang.shared.lecture.dto.LectureSyncPageDto;

import lombok.extern.slf4j.Slf4j;

/**
 * 강의 보정(#884). 강의 이벤트를 놓쳐도(DLT, 소비 버그) 강의 문서가 원본과 맞춰지게, 10분마다 강의 동기화 API를 키셋으로 끝까지 읽어 문서와
 * 비교한다. 한 pod에서만 돈다({@link SearchJob#LECTURE_RECONCILE}).
 * <ul>
 *     <li><b>비교</b>: 페이지마다 그 강의들의 문서를 한 번에 읽어(mget) 다른 것만 쓴다({@link #needsWrite}). 평소에는 쓰기 요청이 없다.</li>
 *     <li><b>같은 반영 코드</b>: 다른 강의는 이벤트와 같은 {@link LectureIndexer#apply(LectureSyncDto)}로 쓴다. 강의 version 조건부 쓰기라
 *     그사이 이벤트가 먼저 반영했으면 noop이다. 문서가 없던 강의는 판매자·평점 칸도 사본에서 채운다.</li>
 *     <li><b>원본에 없는 문서</b>: 끝까지 읽은 뒤 보이는 문서 중 이번에 읽지 못한 강의를 숨긴다. 읽는 동안 생긴 강의(마지막으로 읽은 ID보다 큰 ID)는
 *     건너뛴다. 원본이 비어 있으면(읽은 강의 0개) 숨기지 않는다(강의 앱 응답이 잘못돼 모든 문서를 숨기지 않게).</li>
 * </ul>
 * 재색인 뒤 따라잡기도 이 보정을 같은 락으로 한 번 돈다.
 * <p>
 * 한계: 보정 건수는 페이지 앞에서 한 번 읽은 문서와의 비교로 센다(쓰기 결과로 세지 않는다). 그 페이지를 반영하는 사이 같은 강의의 이벤트가 먼저 반영되면
 * 놓친 이벤트가 아닌데도 보정으로 셀 수 있다(인플루언서 증분 보정과 같은 한계). 쓰기는 강의 version 조건이라 결과는 같다.
 */
@Slf4j
@Service
public class LectureReconciler {

    /** 강의 동기화 한 페이지 크기(강의 API 상한) */
    static final int PAGE_SIZE = LectureSyncPageDto.MAX_SIZE;

    private final LectureIndexSetup indexSetup;
    private final LectureDocumentStore documents;
    private final LectureIndexer indexer;
    private final LectureSyncSource source;
    private final SearchJobRunner jobRunner;
    private final LectureSyncMetrics metrics;
    private final Clock clock;

    @Autowired
    public LectureReconciler(LectureIndexSetup indexSetup, LectureDocumentStore documents, LectureIndexer indexer,
            LectureSyncSource source, SearchJobRunner jobRunner, LectureSyncMetrics metrics) {
        this(indexSetup, documents, indexer, source, jobRunner, metrics, Clock.system(SellerIndexer.ZONE));
    }

    LectureReconciler(LectureIndexSetup indexSetup, LectureDocumentStore documents, LectureIndexer indexer,
            LectureSyncSource source, SearchJobRunner jobRunner, LectureSyncMetrics metrics, Clock clock) {
        this.indexSetup = indexSetup;
        this.documents = documents;
        this.indexer = indexer;
        this.source = source;
        this.jobRunner = jobRunner;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** 한 pod에서만 실행한다. 실패하면 다음 회차에 처음부터 다시 읽는다 */
    public void run() {
        jobRunner.run(SearchJob.LECTURE_RECONCILE, this::reconcile);
    }

    /**
     * 보정한다. 락 없이 돌므로 바깥에서는 {@link #run}만 부른다. 테스트와 재색인 따라잡기는 같은 패키지에서 부른다.
     *
     * @throws IndexNotReadyException alias가 없는 경우(처음 적재 전)
     * @throws IllegalStateException  ES를 읽거나 쓰지 못한 경우
     * @throws com.hapbang.shared.lecture.out.LectureApiCallException 강의 앱을 부르지 못한 경우
     */
    LectureReconcileResult reconcile() {
        long started = System.nanoTime();
        try {
            LectureReconcileResult result = reconcileOnce();
            metrics.reconciled(LectureSyncMetrics.JOB_LECTURE, result, Duration.ofNanos(System.nanoTime() - started));
            return result;
        } catch (IndexNotReadyException e) {
            // 처음 적재 전은 실패가 아니라 할 일이 없는 것이다(실패 지표에 넣지 않는다)
            throw e;
        } catch (RuntimeException e) {
            metrics.reconcileFailed(LectureSyncMetrics.JOB_LECTURE, Duration.ofNanos(System.nanoTime() - started));
            throw e;
        }
    }

    private LectureReconcileResult reconcileOnce() {
        indexSetup.ensureReady();
        Map<LectureReconcileKind, Long> counts = new EnumMap<>(LectureReconcileKind.class);
        Set<Long> seen = new HashSet<>();
        long read = 0;
        Long maxId = null;
        Long afterId = null;
        do {
            LectureSyncPageDto page = source.lectures(afterId, PAGE_SIZE);
            Map<Long, IndexedLecture> indexed = documents.findLectures(
                    page.items().stream().map(LectureSyncDto::id).toList());
            for (LectureSyncDto lecture : page.items()) {
                seen.add(lecture.id());
                counts.merge(apply(lecture, indexed.get(lecture.id())), 1L, Long::sum);
            }
            read += page.items().size();
            if (!page.items().isEmpty()) {
                maxId = page.items().getLast().id();
            }
            afterId = page.nextAfterId();
        } while (afterId != null);
        if (maxId != null) {
            hideMissing(seen, maxId, counts);
        }

        LectureReconcileResult result = new LectureReconcileResult(read, counts);
        log.info("강의 검색 보정을 했다 read={}, inserted={}, overwritten={}, hidden={}, alreadyApplied={}, skipped={}",
                read, result.count(LectureReconcileKind.INSERTED), result.count(LectureReconcileKind.OVERWRITTEN),
                result.count(LectureReconcileKind.HIDDEN), result.count(LectureReconcileKind.ALREADY_APPLIED),
                result.count(LectureReconcileKind.SKIPPED));
        return result;
    }

    /**
     * 원본과 문서가 다른지. 문서가 없거나, 문서의 강의 version이 원본보다 작거나 없으면(원본에 없다고 숨긴 문서) 다시 쓴다.
     * <p>
     * 비교는 여기 한 곳에만 둔다. 같은 version인데 원본 가격이 있고 문서 가격과 다르면 다시 쓰는 조건은 강의 문서에 가격 칸이 생긴 뒤(#909)에만 넣을
     * 수 있다(가격 칸 없이 넣으면 모든 강의가 다르게 보여 10분마다 전체를 다시 쓴다).
     */
    static boolean needsWrite(LectureSyncDto lecture, IndexedLecture indexed) {
        return indexed == null || indexed.lectureVersion() == null || indexed.lectureVersion() < lecture.version();
    }

    private LectureReconcileKind apply(LectureSyncDto lecture, IndexedLecture indexed) {
        if (!needsWrite(lecture, indexed)) {
            return LectureReconcileKind.ALREADY_APPLIED;
        }
        LectureIndexResult result;
        try {
            result = indexer.apply(lecture);
        } catch (DocumentRejectedException e) {
            // ES가 이 강의 값 때문에 거절했다. 다시 써도 같으므로 건너뛰고 다음 강의를 계속한다. 배포 실수·장애는 여기에 들지 않아 회차를 멈춘다
            log.warn("ES가 거절해 강의 검색 보정에서 건너뛴다 lectureId={}", lecture.id(), e);
            return LectureReconcileKind.SKIPPED;
        }
        // 보정 건수는 쓰기 결과가 아니라 쓰기 전 비교로 센다. 같은 version의 가격만 고치는 쓰기(#909)는 결과가 NOOP이어서 결과로 세면 보정이 빠진다
        LectureReconcileKind kind = indexed == null ? LectureReconcileKind.INSERTED : LectureReconcileKind.OVERWRITTEN;
        log.info("강의 이벤트를 놓친 강의 검색 문서를 보정했다 lectureId={}, kind={}, lectureVersion={}, visible={}",
                lecture.id(), kind.tag(), lecture.version(), result.visible());
        return kind;
    }

    /** 보이는 문서 중 이번에 원본에서 읽지 못한 강의(읽는 동안 생긴 강의는 빼고)를 숨긴다 */
    private void hideMissing(Set<Long> seen, long maxId, Map<LectureReconcileKind, Long> counts) {
        Long after = null;
        List<IndexedLecture> page;
        while (!(page = documents.page(after, PAGE_SIZE)).isEmpty()) {
            for (IndexedLecture doc : page) {
                if (doc.hidden() || doc.lectureId() > maxId || seen.contains(doc.lectureId())) {
                    continue;
                }
                if (documents.hideLecture(doc.lectureId(), clock.instant()) == WriteOutcome.UPDATED) {
                    counts.merge(LectureReconcileKind.HIDDEN, 1L, Long::sum);
                    log.info("원본에 없는 강의의 검색 문서를 숨겼다 lectureId={}, lectureVersion={}", doc.lectureId(),
                            doc.lectureVersion());
                }
            }
            after = page.getLast().lectureId();
        }
    }
}
