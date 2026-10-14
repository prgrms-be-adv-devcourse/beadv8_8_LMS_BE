package com.hapbang.billing.in;

import org.springframework.stereotype.Component;

import com.hapbang.billing.app.LectureSettlementFacade;
import com.hapbang.billing.app.LectureSettlementResult;
import com.hapbang.shared.billing.dto.LectureSettlementCommand;
import com.hapbang.shared.billing.dto.LectureSettlementResultDto;
import com.hapbang.shared.billing.port.LectureSettlementPort;

import lombok.RequiredArgsConstructor;

// 강의가 shared의 LectureSettlementPort로 보내는 지급 예정 건 등록·취소를 LectureSettlementFacade에 넘기고, 내부 결과를 외부 계약 결과로 바꾼다(#182).
// 트랜잭션은 열지 않는다. Facade가 부르는 UseCase 프록시의 @Transactional(REQUIRED)이 호출한 쪽 트랜잭션에 참여한다.
// Billing 예외(BillingException)는 잡거나 바꾸지 않고 그대로 전파한다.
@Component
@RequiredArgsConstructor
class LectureSettlementPortAdapter implements LectureSettlementPort {

    private final LectureSettlementFacade lectureSettlementFacade;

    @Override
    public LectureSettlementResultDto schedule(LectureSettlementCommand command) {
        return toDto(lectureSettlementFacade.schedule(command));
    }

    @Override
    public LectureSettlementResultDto cancel(Long orderItemId) {
        return toDto(lectureSettlementFacade.cancel(orderItemId));
    }

    private static LectureSettlementResultDto toDto(LectureSettlementResult result) {
        return new LectureSettlementResultDto(result.orderItemId(), result.receiverUserId(), result.amountHp(),
                result.scheduledAt(), result.replayed());
    }
}
