package com.hapbang.shared.lecture.port;

import com.hapbang.shared.lecture.exception.LectureObjectionRefusedException;

/**
 * 고객센터가 강의 내용 다름 이의 제기의 접수·판단·철회를 강의 주문 항목에 반영하는 연동 계약. 구현은 강의 모듈이 Spring 빈으로 제공한다
 * (강의 설계 6.2, D9).
 * <p>
 * 호출 규칙
 * <ul>
 *     <li>같은 애플리케이션 안의 동기 Java 호출이다. 각 메서드는 호출한 쪽 트랜잭션에 참여하고(전파 REQUIRED), 없으면 새로 연다.
 *         문의 저장과 강의 항목 변경(인정 시 HP 반환까지)이 함께 커밋되거나 함께 롤백된다.</li>
 *     <li>접수·판단·철회할 수 없으면 {@link LectureObjectionRefusedException}(사유 코드 포함)을 던진다. 이 예외가 나면 트랜잭션은
 *         이미 롤백 전용이다. 호출한 쪽은 잡아서 자기 업무 예외로 바꿔 던질 수 있지만, 정상 처리로 바꾸면 커밋할 때
 *         {@code UnexpectedRollbackException}이 난다.</li>
 *     <li>billing 예외(HP 반환 실패)는 바꾸지 않고 그대로 전파한다.</li>
 *     <li>필수 값 누락(null ID, 빈 사유)은 호출한 쪽 코드 오류라 {@code NullPointerException}·{@code IllegalArgumentException}이다.</li>
 * </ul>
 */
public interface LectureObjectionPort {

    /**
     * 내용 다름 이의 제기를 접수해 해당 주문 항목의 지급과 구매 취소를 멈춘다 (objection FILED).
     * 접수할 수 있는 항목: 구매자 본인 항목, 구매·구매 확정 상태, 이의 제기 이력 없음, 지급 예정 시각 전.
     *
     * @param inquiryId 접수한 문의 ID. 판단할 때 같은 값이어야 한다
     * @param reason    이의 제기 사유 (비어 있으면 안 된다)
     * @throws LectureObjectionRefusedException 접수할 수 없을 때. 사유는 {@code NOT_BUYER}, {@code NOT_OBJECTIONABLE},
     *                                          {@code ALREADY_PAID_OUT}, {@code ALREADY_FILED}, {@code PERIOD_EXPIRED}
     *                                          순서로 검사한다
     */
    void fileObjection(Long orderItemId, Long buyerUserId, Long inquiryId, String reason);

    /**
     * 관리자 판단을 반영한다. 접수된(FILED) 항목이고 문의 ID가 접수 때와 같을 때만 처리한다.
     * <ul>
     *     <li>인정: 항목을 반환(RETURNED)하고 그 항목의 보류 HP를 구매자에게 같은 트랜잭션에서 반환한다.
     *         모든 항목이 끝나면 주문을 취소한다.</li>
     *     <li>기각: 지급 예정 시각이 되면(이미 지났으면 다음 지급 배치에서) 지급한다.</li>
     * </ul>
     *
     * @throws LectureObjectionRefusedException 판단할 수 없을 때. 사유는 {@code NOT_FILED}(없는 항목 포함), {@code INQUIRY_MISMATCH}
     */
    void resolveObjection(Long orderItemId, Long inquiryId, boolean accepted, Long adminUserId);

    /**
     * 판단 전 이의 제기를 철회해 지급 중단을 푼다 (고객센터 문의 삭제, #203). 구매자 본인이고, 접수된(FILED) 항목이며,
     * 문의 ID가 접수 때와 같을 때만 처리한다. 항목은 접수 전 상태(objection NONE)로 돌아가므로 지급(지급 예정 시각이 이미
     * 지났으면 다음 지급 배치), 구매 취소, 다시 이의 제기가 원래 기한 그대로 가능하다. HP는 움직이지 않는다.
     *
     * @throws LectureObjectionRefusedException 철회할 수 없을 때. 사유는 {@code NOT_BUYER}(없는 항목 포함),
     *                                          {@code NOT_FILED}(이미 판단한 항목 포함), {@code INQUIRY_MISMATCH}
     *                                          순서로 검사한다
     */
    void withdrawObjection(Long orderItemId, Long buyerUserId, Long inquiryId);
}
