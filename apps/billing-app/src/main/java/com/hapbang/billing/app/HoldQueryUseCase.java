package com.hapbang.billing.app;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.billing.domain.HoldDetailActionType;
import com.hapbang.billing.domain.HoldVoid;
import com.hapbang.billing.domain.PointHold;
import com.hapbang.billing.domain.Settlement;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.out.HoldDetailSum;
import com.hapbang.billing.out.HoldVoidRepository;
import com.hapbang.billing.out.PointHoldDetailRepository;
import com.hapbang.billing.out.PointHoldRepository;
import com.hapbang.billing.out.SettlementRepository;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.shared.billing.domain.BillingReferenceType;

/**
 * 서비스 간 HP 대사용 보류·지급 조회 (#577, BD-137). 업무 상태와 맞는지는 호출한 모듈(강의·출연)이 판단하고, 지갑은 기록만 준다.
 * <ul>
 *     <li>보류 금액: 최초·남은 보류는 보류 행, 반환·지급·확정 차감은 보류 상세 합이다. 상세는 반환·지급마다 배분 누적값과 대조(재생)한 값이다.</li>
 *     <li>조회 수는 요청 크기와 관계없이 일정하다: 보류, 상세 합, 지급 건, 지갑, 무효화 기록을 각각 한 번씩 읽는다.</li>
 *     <li>읽기 전용 트랜잭션 하나에서 읽어, 한 응답 안의 보류·상세·지급 건이 같은 커밋 기준이다(같은 스냅숏은 아니다:
 *     READ COMMITTED라 조회 사이에 커밋된 처리가 섞일 수 있다. 대사는 다음 실행에서 다시 보므로 잠그지 않는다).</li>
 *     <li>목록 조회는 최근 확정 대기 시간({@code settleWindow}, 기본 10초) 안에 바뀐 보류를 주지 않는다. {@code updated_at}은 커밋이 아니라
 *     플러시 시점의 앱 시각이라, 늦게 커밋된 보류가 이미 지나간 커서 뒤에 끼어 영영 빠질 수 있기 때문이다. 시각 기록 뒤 가장 길게
 *     기다리는 곳은 같은 참조 대상 보류의 UNIQUE 대기이고 락 대기 상한({@code lock_timeout}) 3초에서 끝나므로, 그보다 넉넉히 둔다.</li>
 * </ul>
 */
@Service
public class HoldQueryUseCase {

    private final PointHoldRepository pointHoldRepository;
    private final PointHoldDetailRepository pointHoldDetailRepository;
    private final SettlementRepository settlementRepository;
    private final HoldVoidRepository holdVoidRepository;
    private final WalletRepository walletRepository;
    private final Duration settleWindow;

    public HoldQueryUseCase(PointHoldRepository pointHoldRepository,
                            PointHoldDetailRepository pointHoldDetailRepository,
                            SettlementRepository settlementRepository, HoldVoidRepository holdVoidRepository,
                            WalletRepository walletRepository,
                            @Value("${hapbang.billing.hold-query.settle-window:10s}") Duration settleWindow) {
        this.pointHoldRepository = pointHoldRepository;
        this.pointHoldDetailRepository = pointHoldDetailRepository;
        this.settlementRepository = settlementRepository;
        this.holdVoidRepository = holdVoidRepository;
        this.walletRepository = walletRepository;
        if (settleWindow == null || settleWindow.isNegative()) {
            throw new IllegalArgumentException("보류 목록 확정 대기 시간은 0 이상이어야 합니다: " + settleWindow);
        }
        this.settleWindow = settleWindow;
    }

    /** 참조 ID마다 하나씩, 요청 순서대로(같은 ID는 한 번) 돌려준다. 지갑에 기록이 없는 ID도 빈 기록으로 준다 */
    @Transactional(readOnly = true)
    public List<HoldState> query(BillingReferenceType referenceType, List<Long> referenceIds) {
        List<Long> distinctIds = List.copyOf(new LinkedHashSet<>(referenceIds));
        List<String> keys = distinctIds.stream().map(id -> BillingKeys.hold(referenceType, id)).toList();
        Map<Long, PointHold> holds = pointHoldRepository.findAllByIdempotencyKeyIn(keys).stream()
                .collect(Collectors.toMap(PointHold::getReferenceId, Function.identity()));
        return assemble(referenceType, distinctIds, holds);
    }

    /** 참조 유형별로 (updatedAfter, afterId) 뒤에, 확정 대기 시간보다 전에 바뀐 보류. size + 1개를 읽어 다음 페이지가 있는지 안다 */
    @Transactional(readOnly = true)
    public HoldStatePage changes(BillingReferenceType referenceType, Instant updatedAfter, long afterId, int size) {
        List<PointHold> found = pointHoldRepository.findChangedAfter(referenceType, updatedAfter, afterId,
                Instant.now().minus(settleWindow), Limit.of(size + 1));
        boolean hasNext = found.size() > size;
        List<PointHold> page = hasNext ? found.subList(0, size) : found;
        Map<Long, PointHold> holds = page.stream()
                .collect(Collectors.toMap(PointHold::getReferenceId, Function.identity()));
        List<HoldState> items = assemble(referenceType, page.stream().map(PointHold::getReferenceId).toList(), holds);
        if (!hasNext) {
            return new HoldStatePage(items, null, null);
        }
        PointHold last = page.getLast();
        return new HoldStatePage(items, last.getUpdatedAt(), last.getId());
    }

    private List<HoldState> assemble(BillingReferenceType referenceType, List<Long> referenceIds,
                                     Map<Long, PointHold> holds) {
        List<Long> holdIds = holds.values().stream().map(PointHold::getId).toList();
        Map<Long, Map<HoldDetailActionType, HoldState.Amount>> sums = holdIds.isEmpty() ? Map.of() : detailSums(holdIds);
        Map<Long, List<Settlement>> settlements = holdIds.isEmpty() ? Map.of()
                : settlementRepository.findAllByPointHoldIdInOrderByIdAsc(holdIds).stream()
                        .collect(Collectors.groupingBy(Settlement::getPointHoldId));
        Map<Long, Long> walletOwners = holds.isEmpty() ? Map.of()
                : walletRepository.findAllByIdIn(holds.values().stream().map(PointHold::getWalletId).toList())
                        .stream().collect(Collectors.toMap(Wallet::getId, Wallet::getUserId));
        Map<Long, HoldVoid> voids = holdVoidRepository.findAllByReferenceTypeAndReferenceIdIn(referenceType, referenceIds)
                .stream().collect(Collectors.toMap(HoldVoid::getReferenceId, Function.identity()));

        List<HoldState> states = new ArrayList<>(referenceIds.size());
        for (Long referenceId : referenceIds) {
            PointHold hold = holds.get(referenceId);
            HoldVoid voided = voids.get(referenceId);
            states.add(new HoldState(referenceType, referenceId,
                    hold == null ? null : hold(hold, walletOwners.get(hold.getWalletId()),
                            sums.getOrDefault(hold.getId(), Map.of())),
                    voided == null ? null : new HoldState.Voided(voided.getOutcome(), voided.getReleasedHp()),
                    hold == null ? List.of() : settlements.getOrDefault(hold.getId(), List.of()).stream()
                            .map(HoldQueryUseCase::settlement).toList()));
        }
        return states;
    }

    private Map<Long, Map<HoldDetailActionType, HoldState.Amount>> detailSums(List<Long> holdIds) {
        Map<Long, Map<HoldDetailActionType, HoldState.Amount>> sums = new HashMap<>();
        for (HoldDetailSum sum : pointHoldDetailRepository.sumByPointHoldIds(holdIds)) {
            sums.computeIfAbsent(sum.pointHoldId(), id -> new EnumMap<>(HoldDetailActionType.class))
                    .put(sum.actionType(), new HoldState.Amount(sum.chargeHp(), sum.revenueHp()));
        }
        return sums;
    }

    private static HoldState.Hold hold(PointHold hold, Long payerUserId,
                                       Map<HoldDetailActionType, HoldState.Amount> sums) {
        return new HoldState.Hold(hold.getStatus(), payerUserId,
                new HoldState.Amount(hold.getOriginalChargeHp(), hold.getOriginalRevenueHp()),
                new HoldState.Amount(hold.getHeldChargeHp(), hold.getHeldRevenueHp()),
                sums.getOrDefault(HoldDetailActionType.RELEASE, HoldState.Amount.ZERO),
                sums.getOrDefault(HoldDetailActionType.PAYOUT, HoldState.Amount.ZERO),
                sums.getOrDefault(HoldDetailActionType.CAPTURE, HoldState.Amount.ZERO));
    }

    private static HoldState.Settlement settlement(Settlement settlement) {
        return new HoldState.Settlement(settlement.getSourceType(), settlement.getSourceId(),
                settlement.getPayoutType(), settlement.getReceiverUserId(), settlement.getGrossAmountHp(),
                settlement.getPaidAmountHp(), settlement.getStatus());
    }
}
