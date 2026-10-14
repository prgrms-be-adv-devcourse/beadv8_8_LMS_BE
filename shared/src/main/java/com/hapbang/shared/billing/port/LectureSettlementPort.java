package com.hapbang.shared.billing.port;

import com.hapbang.shared.billing.dto.LectureSettlementCommand;
import com.hapbang.shared.billing.dto.LectureSettlementResultDto;

/**
 * 강의가 주문 항목별 판매 수익 지급 예정 건(Billing SETTLEMENT)을 등록·취소하는 연동 계약 (#142·#182). 구현은 Billing 모듈이 Spring 빈으로 제공한다.
 * <p>
 * 호출 규칙 ({@link BillingPort}와 같다)
 * <ul>
 *     <li>같은 애플리케이션 안의 동기 Java 호출이며 호출한 쪽 트랜잭션에 참여한다(REQUIRED). 금융 실패는 런타임 예외로 알리고,
 *         예외가 나면 호출한 쪽의 같은 트랜잭션 변경도 롤백된다. 호출한 쪽은 예외를 잡아 정상 처리로 바꾸지 않는다.</li>
 *     <li><b>구매</b>: 주문 항목 저장 → {@link BillingPort#hold} → {@link #schedule}을 한 트랜잭션에서 부른다.</li>
 *     <li><b>구매 취소</b>: {@link BillingPort#release} → {@link #cancel}을 한 트랜잭션에서 <b>이 순서로</b> 부른다
 *         (Billing 락 순서 지갑 → 보류 → 지급 건. 순서를 바꾸면 지급 처리와 교착할 수 있다).</li>
 *     <li>지급 건은 주문 항목당 1건이다. 같은 내용의 재등록·재취소는 처음 결과를 돌려주고({@code replayed=true}),
 *         같은 주문 항목에 다른 판매자·금액·구매 날짜면 {@code IDEMPOTENCY_KEY_CONFLICT}, 취소된 항목의 재등록은 {@code SETTLEMENT_INVALID_STATE}다.</li>
 * </ul>
 * 이의 제기 중단·재개와 실제 지급은 아직 이 계약에 없다(고객센터 연동·강의 지급 #150에서 추가한다).
 */
public interface LectureSettlementPort {

    /** 주문 항목 보류를 확인하고 판매자에게 줄 지급 예정 건을 등록한다. HP는 움직이지 않는다 */
    LectureSettlementResultDto schedule(LectureSettlementCommand command);

    /** 구매 취소가 확정된 주문 항목의 지급 예정 건을 취소한다(종료 상태). 보류 반환은 먼저 {@link BillingPort#release}로 한다 */
    LectureSettlementResultDto cancel(Long orderItemId);
}
