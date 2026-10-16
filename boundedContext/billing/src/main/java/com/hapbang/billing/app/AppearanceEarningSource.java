package com.hapbang.billing.app;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.EarningSourceType;
import com.hapbang.billing.domain.EarningStatus;
import com.hapbang.billing.domain.EarningType;
import com.hapbang.shared.appearance.domain.AppearanceEarningStatus;
import com.hapbang.shared.appearance.dto.AppearanceEarningAmountDto;
import com.hapbang.shared.appearance.dto.AppearanceEarningItemDto;
import com.hapbang.shared.appearance.dto.AppearanceEarningPageDto;
import com.hapbang.shared.appearance.dto.AppearanceEarningSummaryDto;
import com.hapbang.shared.appearance.out.AppearanceApiCallException;
import com.hapbang.shared.appearance.out.AppearanceApiClient;

import lombok.extern.slf4j.Slf4j;

// 출연 수익을 shared AppearanceApiClient(내부 HTTP API, #301)로 읽어 Billing 값으로 바꾼다 (#295).
// 출연 시각은 offset 없는 KST LocalDateTime이라 JVM 기본 시간대가 아니라 고정 Asia/Seoul로 해석해 Instant로 바꾼다.
// 내부 API 키·주소·타임아웃은 Client 설정을 그대로 쓴다. Client가 없으면(주소 미설정) 조회할 수 없는 것으로 본다.
@Slf4j
@Component
class AppearanceEarningSource implements EarningSource {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final Supplier<AppearanceApiClient> client;

    @Autowired
    AppearanceEarningSource(ObjectProvider<AppearanceApiClient> client) {
        this((Supplier<AppearanceApiClient>) client::getIfAvailable);
    }

    AppearanceEarningSource(Supplier<AppearanceApiClient> client) {
        this.client = client;
    }

    @Override
    public EarningSourceType type() {
        return EarningSourceType.APPEARANCE;
    }

    @Override
    public EarningSummaryResult.Buckets summary(long userId) {
        AppearanceEarningSummaryDto dto = call(() -> client().getEarningSummary(userId));
        return new EarningSummaryResult.Buckets(amount(dto.confirmationPending()), amount(dto.scheduled()),
                amount(dto.onHold()), amount(dto.processing()), amount(dto.failed()));
    }

    @Override
    public SourcePage earnings(long userId, Set<EarningStatus> statuses, int page, int size) {
        List<AppearanceEarningStatus> requested = statuses.stream()
                .map(status -> AppearanceEarningStatus.valueOf(status.name())).toList();
        AppearanceEarningPageDto dto = call(() -> client().getEarnings(userId, requested, page, size));
        EarningSourceResponses.require(type(), dto.content().size() <= size && dto.page().totalElements() >= 0,
                "페이지 크기·전체 건수가 요청과 맞지 않습니다.");
        List<EarningItemResult> items = dto.content().stream().map(item -> item(item, statuses)).toList();
        return new SourcePage(items, dto.page().totalElements());
    }

    private EarningItemResult item(AppearanceEarningItemDto dto, Set<EarningStatus> statuses) {
        EarningStatus status = EarningStatus.valueOf(dto.status().name());
        EarningSourceResponses.require(type(), AppearanceEarningItemDto.SOURCE_TYPE.equals(dto.sourceType())
                        && dto.amountHp() >= 0 && statuses.contains(status),
                "수익 항목이 요청 조건·계약과 맞지 않습니다.");
        return new EarningItemResult(EarningSourceType.APPEARANCE, dto.sourceId(),
                EarningType.valueOf(dto.earningType().name()), dto.title(), dto.amountHp(), status,
                instant(dto.payoutScheduledAt()), instant(dto.statusUpdatedAt()));
    }

    private EarningSummaryResult.Amount amount(AppearanceEarningAmountDto dto) {
        EarningSourceResponses.require(type(), dto.amountHp() >= 0 && dto.count() >= 0, "요약 금액·건수가 음수입니다.");
        return new EarningSummaryResult.Amount(dto.amountHp(), dto.count());
    }

    static Instant instant(LocalDateTime kst) {
        return kst == null ? null : kst.atZone(KST).toInstant();
    }

    private AppearanceApiClient client() {
        AppearanceApiClient appearanceApiClient = client.get();
        if (appearanceApiClient == null) {
            log.error("출연 내부 API Client가 없어 수익을 조회할 수 없습니다(hapbang.clients.appearance.base-url 미설정).");
            throw new EarningSourceException(BillingErrorCode.EARNING_SOURCE_UNAVAILABLE, type(), null);
        }
        return appearanceApiClient;
    }

    // 4xx(REJECTED)는 내부 API 키·계약 불일치라 다시 시도해도 같고, 나머지(연결·시간 초과·5xx·읽을 수 없는 응답)는 일시 장애다.
    private <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (AppearanceApiCallException e) {
            boolean rejected = e.getReason() == AppearanceApiCallException.Reason.REJECTED;
            if (rejected) {
                log.error("출연 내부 수익 API가 요청을 거절했습니다: status={}, code={}", e.getStatus(), e.getCode());
            } else {
                log.warn("출연 내부 수익 API를 조회하지 못했습니다: status={}, message={}", e.getStatus(), e.getMessage());
            }
            throw new EarningSourceException(rejected ? BillingErrorCode.EARNING_SOURCE_REJECTED
                    : BillingErrorCode.EARNING_SOURCE_UNAVAILABLE, type(), e);
        }
    }
}
