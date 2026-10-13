package com.hapbang.billing.app;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.hapbang.billing.app.ReleaseResult.ChargeRestoration;
import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.HoldDetailActionType;

// 보류 하나의 충전 원금 배분(POINT_CHARGE_USAGE)에 반환·지급 상세(POINT_HOLD_DETAIL)를 ID 순서대로 다시 적용해
// 처리 한 건이 어느 충전 건에서 얼마를 가져갔는지 재구성한다. 상세와 배분 사이에 연결 테이블이 없으므로(스키마 A안)
// 충전 원금 처리 순서 규칙을 고정해 두고, 그 규칙으로 재생한 결과가 DB의 누적값과 같은지 대조한다.
//
// 반환 규칙: 최초 FIFO 배분의 역순(배분 ID 내림차순)으로, 남은 보류가 있는 배분부터 채운다 (2026-10-13 확정).
// 이 규칙을 바꾸면 이미 기록된 상세를 재구성할 수 없으므로 바꾸지 않는다.
final class ChargeUsageHistory {

    // 충전 건 하나를 보류에 처음 배분한 내역이다. 배분 순서(ID 오름차순)로 넘긴다.
    record Allocation(Long usageId, Long chargeId, long amountHp) {
    }

    // 보류의 반환·지급 상세 한 건 중 충전 HP 부분이다. 처리 순서(ID 오름차순)로 넘긴다.
    record Processing(Long detailId, HoldDetailActionType actionType, long chargeAmountHp) {
    }

    // 배분 하나에서 반환한 원금이다.
    record UsageRelease(Long usageId, Long chargeId, long amountHp) {
    }

    private final List<Slot> slots = new ArrayList<>();
    private final Map<Long, Slot> slotsByUsageId = new HashMap<>();
    private final Map<Long, List<ChargeRestoration>> restorationsByDetailId = new LinkedHashMap<>();

    private ChargeUsageHistory(List<Allocation> allocations) {
        for (Allocation allocation : allocations) {
            Slot slot = new Slot(allocation);
            slots.add(slot);
            slotsByUsageId.put(allocation.usageId(), slot);
        }
    }

    // 최초 배분에서 시작해 상세를 처리 순서대로 적용한다. 규칙으로 배분할 수 없는 상세가 있으면 HOLD_STATE_INCONSISTENT로 거절한다.
    static ChargeUsageHistory replay(List<Allocation> allocations, List<Processing> processings) {
        ChargeUsageHistory history = new ChargeUsageHistory(allocations);
        for (Processing processing : processings) {
            if (processing.chargeAmountHp() == 0) {
                history.restorationsByDetailId.put(processing.detailId(), List.of());
                continue;
            }
            // TODO(payout): 지급이 충전 원금을 소진하는 순서는 아직 정하지 않았다. 지급 구현 때 순서를 정해 여기에 더한다.
            if (processing.actionType() != HoldDetailActionType.RELEASE) {
                throw new BillingException(BillingErrorCode.HOLD_STATE_INCONSISTENT);
            }
            List<UsageRelease> releases = history.release(processing.chargeAmountHp());
            history.restorationsByDetailId.put(processing.detailId(), toRestorations(releases));
        }
        return history;
    }

    // 남은 보류가 있는 배분을 최초 배분의 역순으로 채워 충전 HP 반환액을 나누고, 그만큼 남은 보류를 줄이고 반환액을 늘린다.
    // 남은 보류 합계보다 많으면 HOLD_STATE_INCONSISTENT로 거절한다.
    List<UsageRelease> release(long chargeHp) {
        List<UsageRelease> releases = new ArrayList<>();
        long remainingHp = chargeHp;
        for (int index = slots.size() - 1; index >= 0 && remainingHp > 0; index--) {
            Slot slot = slots.get(index);
            long amountHp = Math.min(remainingHp, slot.remainingHeldHp);
            if (amountHp == 0) {
                continue;
            }
            slot.remainingHeldHp -= amountHp;
            slot.releasedHp += amountHp;
            releases.add(new UsageRelease(slot.allocation.usageId(), slot.allocation.chargeId(), amountHp));
            remainingHp -= amountHp;
        }
        if (remainingHp != 0) {
            throw new BillingException(BillingErrorCode.HOLD_STATE_INCONSISTENT);
        }
        return releases;
    }

    // 재생한 상세 한 건의 충전 건별 원금 복원을 복원 순서대로 돌려준다. 재생하지 않은 상세면 HOLD_STATE_INCONSISTENT로 거절한다.
    List<ChargeRestoration> restorationsOf(Long detailId) {
        List<ChargeRestoration> restorations = restorationsByDetailId.get(detailId);
        if (restorations == null) {
            throw new BillingException(BillingErrorCode.HOLD_STATE_INCONSISTENT);
        }
        return restorations;
    }

    // 재생한 배분 하나의 (남은 보류, 소진, 반환)이 DB에 저장된 값과 같은지 확인한다.
    boolean matches(Long usageId, long remainingHeldHp, long consumedHp, long releasedHp) {
        Slot slot = Objects.requireNonNull(slotsByUsageId.get(usageId), "재생하지 않은 배분입니다.");
        return slot.remainingHeldHp == remainingHeldHp && slot.consumedHp == consumedHp
                && slot.releasedHp == releasedHp;
    }

    // 배분별 반환을 충전 건별 원금 복원으로 바꾼다. 같은 충전 건은 한 보류에 배분이 하나뿐이다(UNIQUE charge_id, point_hold_id).
    static List<ChargeRestoration> toRestorations(List<UsageRelease> releases) {
        return releases.stream().map(release -> new ChargeRestoration(release.chargeId(), release.amountHp())).toList();
    }

    // 배분 하나의 재생 중 상태
    private static final class Slot {

        private final Allocation allocation;
        private long remainingHeldHp;
        private long consumedHp;
        private long releasedHp;

        private Slot(Allocation allocation) {
            this.allocation = allocation;
            this.remainingHeldHp = allocation.amountHp();
        }
    }
}
