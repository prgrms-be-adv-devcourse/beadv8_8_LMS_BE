package com.hapbang.appearance.app;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.hapbang.appearance.app.dto.AppearanceEarningBucketResDto;
import com.hapbang.appearance.app.dto.AppearanceEarningItemResDto;
import com.hapbang.appearance.app.dto.AppearanceEarningSummaryResDto;
import com.hapbang.appearance.domain.Appearance;
import com.hapbang.appearance.domain.AppearanceEarningRule;
import com.hapbang.appearance.domain.AppearanceEarningRule.Classification;
import com.hapbang.appearance.domain.AppearanceEarningRule.Condition;
import com.hapbang.appearance.domain.AppearanceEarningStatus;
import com.hapbang.appearance.domain.AppearanceEarningType;
import com.hapbang.appearance.domain.AppearanceErrorCode;
import com.hapbang.appearance.domain.AppearanceException;
import com.hapbang.appearance.domain.AppearanceStatus;
import com.hapbang.appearance.out.AppearanceEarningGroup;
import com.hapbang.appearance.out.AppearanceEarningRow;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 게스트의 출연 수익 조회 (#301, ISSUE.md APR-39): Billing "지급 예정 요약"에 출연료·위약금과 지급 상태를 준다.
 * 분류는 {@link AppearanceEarningRule} 한 곳에서 하고, 요약과 목록이 같은 규칙을 쓴다. 트랜잭션은 호출한 Facade가 연다 (읽기 전용).
 * <ul>
 *     <li>기준 회원은 게스트(수익을 받는 쪽)다. 출연 1건 = 수익 1건이고, 일방 취소된 출연은 위약금 한 건으로만 센다.</li>
 *     <li>출연 쪽에 데이터가 없는 회원이면 0원 요약·빈 목록이다 (회원 복제를 확인하지 않는다: 동기화가 늦어도 Billing 화면이 깨지지 않게).</li>
 *     <li>있어야 할 지급·위약금 작업이 없는 출연은 FAILED로 보여 주고 로그를 남긴다 (APR-39 N2). 목록은 출연 ID와 함께 ERROR,
 *     요약은 묶음이라 출연 ID 없이 WARN (태그 {@code [EARNING_TASK_MISSING]}으로 같이 찾는다).</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppearanceEarningQueryUseCase {

    private final AppearanceEarningSupport appearanceEarningSupport;
    private final AppearanceTitleSupport appearanceTitleSupport;

    /**
     * @param type 종류, null이면 출연료·위약금 모두
     * @throws AppearanceException 회원 ID가 없거나 1 미만 ({@code INVALID_REQUEST})
     */
    public AppearanceEarningSummaryResDto summarize(Long memberId, AppearanceEarningType type) {
        validateMemberId(memberId);
        Map<AppearanceEarningStatus, AppearanceEarningBucketResDto> buckets = new EnumMap<>(AppearanceEarningStatus.class);
        for (AppearanceEarningGroup group : appearanceEarningSupport.summarize(memberId)) {
            Classification classification = AppearanceEarningRule.classify(group.status(), group.taskStatus());
            if (type != null && classification.type() != type) {
                continue;
            }
            if (classification.missingTask()) {
                // 요약은 묶음이라 출연 ID를 모른다. 출연 ID는 목록 조회의 ERROR 로그로 남는다
                log.warn("[EARNING_TASK_MISSING] 지급·위약금 작업이 없는 출연 guestId={} status={} count={}", memberId,
                        group.status(), group.count());
            }
            long amount = group.status() == AppearanceStatus.CANCELED_BY_HOST ? group.penaltySum() : group.feeSum();
            buckets.merge(classification.status(), AppearanceEarningBucketResDto.EMPTY.plus(amount, group.count()),
                    (sum, added) -> sum.plus(added.amountHp(), added.count()));
        }
        return new AppearanceEarningSummaryResDto(
                bucket(buckets, AppearanceEarningStatus.CONFIRMATION_PENDING),
                AppearanceEarningBucketResDto.EMPTY,
                bucket(buckets, AppearanceEarningStatus.ON_HOLD),
                bucket(buckets, AppearanceEarningStatus.PROCESSING),
                bucket(buckets, AppearanceEarningStatus.FAILED));
    }

    /**
     * @param statuses 지급 상태. null이면 아직 받지 못한 수익(요약과 같은 범위), 빈 목록이면 빈 결과
     *                 (공통 상태 중 출연에 없는 값만 골랐을 때, 예: SCHEDULED — APR-39 N4)
     * @param type     종류, null이면 출연료·위약금 모두
     * @throws AppearanceException 회원 ID가 없거나 1 미만 ({@code INVALID_REQUEST})
     */
    public Page<AppearanceEarningItemResDto> find(Long memberId, Collection<AppearanceEarningStatus> statuses,
                                                  AppearanceEarningType type, Pageable pageable) {
        validateMemberId(memberId);
        Collection<AppearanceEarningStatus> targets = statuses == null ? AppearanceEarningStatus.UNPAID : statuses;
        Condition condition = AppearanceEarningRule.conditionOf(targets, type);
        if (condition.isEmpty()) {
            return Page.empty(pageable);
        }
        Page<AppearanceEarningRow> rows = appearanceEarningSupport.find(memberId, condition, pageable);
        List<Appearance> appearances = new ArrayList<>();
        for (AppearanceEarningRow row : rows.getContent()) {
            appearances.add(row.appearance());
        }
        Map<Long, String> titles = appearanceTitleSupport.titlesOf(appearances);
        List<AppearanceEarningItemResDto> items = new ArrayList<>();
        for (AppearanceEarningRow row : rows.getContent()) {
            items.add(toItem(row, titles.get(row.appearance().getId())));
        }
        return new PageImpl<>(items, pageable, rows.getTotalElements());
    }

    private AppearanceEarningItemResDto toItem(AppearanceEarningRow row, String title) {
        Appearance appearance = row.appearance();
        Classification classification = AppearanceEarningRule.classify(appearance.getStatus(), row.taskStatus());
        if (classification.missingTask()) {
            log.error("[EARNING_TASK_MISSING] 지급·위약금 작업이 없는 출연 guestId={} appearanceId={} status={}",
                    appearance.getGuestId(), appearance.getId(), appearance.getStatus());
        }
        return new AppearanceEarningItemResDto(AppearanceEarningRule.sourceTypeOf(appearance), appearance.getId(),
                classification.type(), title, AppearanceEarningRule.amountOf(appearance), classification.status(),
                null, row.statusUpdatedAt());
    }

    private static AppearanceEarningBucketResDto bucket(
            Map<AppearanceEarningStatus, AppearanceEarningBucketResDto> buckets, AppearanceEarningStatus status) {
        return buckets.getOrDefault(status, AppearanceEarningBucketResDto.EMPTY);
    }

    private static void validateMemberId(Long memberId) {
        if (memberId == null || memberId < 1) {
            throw new AppearanceException(AppearanceErrorCode.INVALID_REQUEST, "회원 ID는 1 이상이어야 합니다.");
        }
    }
}
