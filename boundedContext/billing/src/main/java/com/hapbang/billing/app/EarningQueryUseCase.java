package com.hapbang.billing.app;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.EarningStatus;

// 강의·출연 지급 예정 수익의 통합 요약·목록 (#295). 두 출처의 내부 HTTP API를 부르므로 DB 트랜잭션을 열지 않는다.
// 한쪽이라도 실패하면 전체를 실패시킨다(부분 합계·부분 목록을 정상 응답으로 주지 않는다). 다른 도메인 데이터는 저장하지 않는다.
@Service
public class EarningQueryUseCase {

    /** 통합 목록에서 조회할 수 있는 전역 깊이: (page + 1) × size */
    static final int MAX_DEPTH = 1_000;
    /** 출처 내부 API 한 번에 받을 최대 건수(내부 API 페이지 최대 크기) */
    static final int SOURCE_PAGE_SIZE = 100;

    private final List<EarningSource> sources;
    private final Clock clock;

    @Autowired
    EarningQueryUseCase(LectureEarningSource lectureEarningSource, AppearanceEarningSource appearanceEarningSource) {
        this(List.of(lectureEarningSource, appearanceEarningSource), Clock.systemUTC());
    }

    EarningQueryUseCase(List<EarningSource> sources, Clock clock) {
        this.sources = List.copyOf(sources);
        this.clock = clock;
    }

    // 두 출처의 미지급 수익을 상태별로 더한다. asOf는 조회를 시작한 시각이다(두 출처의 같은 시점 스냅샷이 아니다).
    public EarningSummaryResult summary(long userId) {
        Instant asOf = clock.instant();
        EarningSummaryResult.Buckets buckets = EarningSummaryResult.Buckets.ZERO;
        for (EarningSource source : sources) {
            buckets = buckets.plus(source.summary(userId));
        }
        return EarningSummaryResult.of(buckets, asOf);
    }

    // 두 출처의 수익을 전역 순서(EarningItemResult.ORDER)로 합친 목록의 page 번째 페이지.
    // N = (page + 1) × size건까지만 볼 수 있고(넘으면 EARNING_PAGE_TOO_DEEP), 출처마다 앞쪽 최대 N건을 0페이지부터 받아 합친 뒤
    // [page × size, N) 구간을 자른다. 각 출처가 같은 순서로 주므로 전역 앞쪽 N건은 반드시 어느 한 출처의 앞쪽 N건 안에 있다.
    // 전체 건수는 두 출처 전체 건수의 합이다. 출처를 여러 번 부르는 사이 데이터가 바뀌면 중복·누락이 있을 수 있다.
    public Page<EarningItemResult> list(long userId, Collection<EarningStatus> statuses, int page, int size) {
        long depth = ((long) page + 1) * size;
        if (depth > MAX_DEPTH) {
            throw new BillingException(BillingErrorCode.EARNING_PAGE_TOO_DEEP);
        }
        int limit = (int) depth;
        Set<EarningStatus> filter = filterOf(statuses);
        List<EarningItemResult> merged = new ArrayList<>();
        long totalElements = 0;
        for (EarningSource source : sources) {
            SourceHead head = head(source, userId, filter, limit);
            merged.addAll(head.items());
            totalElements = Math.addExact(totalElements, head.totalElements());
        }
        merged.sort(EarningItemResult.ORDER);
        int from = Math.min(page * size, merged.size());
        int to = Math.min(limit, merged.size());
        return new PageImpl<>(List.copyOf(merged.subList(from, to)), PageRequest.of(page, size), totalElements);
    }

    // 출처의 앞쪽 최대 limit건을 0페이지부터 차례로 받는다. 마지막 페이지(덜 찬 페이지·전체 건수 도달)에서 멈춘다.
    private SourceHead head(EarningSource source, long userId, Set<EarningStatus> statuses, int limit) {
        int pageSize = Math.min(limit, SOURCE_PAGE_SIZE);
        List<EarningItemResult> items = new ArrayList<>();
        long totalElements = 0;
        for (int sourcePage = 0; items.size() < limit; sourcePage++) {
            EarningSource.SourcePage response = source.earnings(userId, statuses, sourcePage, pageSize);
            if (sourcePage == 0) {
                totalElements = response.totalElements();
            }
            items.addAll(response.content());
            if (response.content().size() < pageSize || items.size() >= totalElements) {
                break;
            }
        }
        return new SourceHead(items.size() > limit ? items.subList(0, limit) : items, totalElements);
    }

    // 상태를 주지 않았거나 빈 값(?status=)만 주면 미지급 다섯 상태다.
    private static Set<EarningStatus> filterOf(Collection<EarningStatus> statuses) {
        if (statuses == null) {
            return EarningStatus.UNPAID;
        }
        List<EarningStatus> given = statuses.stream().filter(Objects::nonNull).toList();
        return given.isEmpty() ? EarningStatus.UNPAID : EnumSet.copyOf(given);
    }

    private record SourceHead(List<EarningItemResult> items, long totalElements) {
    }
}
