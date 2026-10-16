package com.hapbang.lecture.app;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.domain.OrderItem;
import com.hapbang.lecture.out.EarningRow;
import com.hapbang.lecture.out.EarningStatusTotal;
import com.hapbang.lecture.out.LectureOrderRepository;
import com.hapbang.shared.lecture.domain.LectureEarningStatus;
import com.hapbang.shared.lecture.domain.LectureEarningType;
import com.hapbang.shared.lecture.dto.LectureEarningAmountDto;
import com.hapbang.shared.lecture.dto.LectureEarningItemDto;
import com.hapbang.shared.lecture.dto.LectureEarningSummaryDto;

/** 판매 수익 조회 (#300). 분류는 {@link LectureOrderRepository#EARNING_STATUS}가 하고, 여기서는 계약 DTO로 바꾼다 */
@Component
public class LectureEarningSupport {

    /** 지급 예정 시각을 정할 수 없는 상태. 이 상태의 {@code payoutScheduledAt}은 {@code null}이다 */
    private static final Set<LectureEarningStatus> UNSCHEDULED = Set.of(LectureEarningStatus.ON_HOLD,
            LectureEarningStatus.FAILED, LectureEarningStatus.CANCELED);

    private final LectureOrderRepository lectureOrderRepository;
    private final Clock clock;

    public LectureEarningSupport(LectureOrderRepository lectureOrderRepository,
                                 @Qualifier(LectureConfig.CLOCK) Clock clock) {
        this.lectureOrderRepository = lectureOrderRepository;
        this.clock = clock;
    }

    /** 판매자의 아직 지급하지 않은 판매 수익을 상태별로 합한다. 수익이 없는 상태는 0이다 */
    public LectureEarningSummaryDto getSummary(Long sellerUserId) {
        LocalDateTime now = LocalDateTime.now(clock);
        Map<LectureEarningStatus, EarningStatusTotal> totals = lectureOrderRepository
                .summarizeEarnings(sellerUserId, now).stream()
                .collect(Collectors.toMap(EarningStatusTotal::status, Function.identity()));
        return new LectureEarningSummaryDto(
                amount(totals, LectureEarningStatus.CONFIRMATION_PENDING),
                amount(totals, LectureEarningStatus.SCHEDULED),
                amount(totals, LectureEarningStatus.ON_HOLD),
                amount(totals, LectureEarningStatus.PROCESSING),
                amount(totals, LectureEarningStatus.FAILED),
                toOffset(now));
    }

    /**
     * 판매자의 판매 수익 목록. 상태를 주지 않으면 아직 지급하지 않은 수익만 본다.
     * 상태 변경 최신순이고 정렬은 쿼리에 고정되어 요청의 정렬은 무시한다
     */
    public Page<LectureEarningItemDto> getEarnings(Long sellerUserId, Collection<LectureEarningStatus> statuses,
                                                   Pageable pageable) {
        Collection<LectureEarningStatus> target = statuses == null || statuses.isEmpty()
                ? LectureOrderRepository.UNPAID_EARNING_STATUSES
                : statuses;
        PageRequest unsorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
        return lectureOrderRepository.findEarnings(sellerUserId, target, LocalDateTime.now(clock), unsorted)
                .map(LectureEarningSupport::toItem);
    }

    private static LectureEarningItemDto toItem(EarningRow row) {
        OrderItem item = row.item();
        OffsetDateTime payoutScheduledAt = UNSCHEDULED.contains(row.status())
                ? null
                : toOffset(item.getPayoutScheduledAt());
        return new LectureEarningItemDto(LectureEarningItemDto.SOURCE_TYPE, item.getId(),
                LectureEarningType.LECTURE_SALE, item.getLectureTitleSnapshot(), item.getPriceHp(), row.status(),
                payoutScheduledAt, toOffset(row.statusUpdatedAt()));
    }

    private static LectureEarningAmountDto amount(Map<LectureEarningStatus, EarningStatusTotal> totals,
                                                  LectureEarningStatus status) {
        EarningStatusTotal total = totals.get(status);
        return total == null ? LectureEarningAmountDto.ZERO
                : new LectureEarningAmountDto(total.amountHp(), total.count());
    }

    /** 강의 시각은 KST 날짜·시각으로 저장한다 (D37) */
    private static OffsetDateTime toOffset(LocalDateTime kst) {
        return kst.atZone(LectureConfig.ZONE).toOffsetDateTime();
    }
}
