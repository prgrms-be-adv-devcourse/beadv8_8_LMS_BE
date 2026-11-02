package com.hapbang.shared.lecture.dto;

import java.util.Objects;

import com.hapbang.shared.lecture.domain.LectureObjectionCommandType;

/**
 * 강의 내용 다름 이의 제기 접수 요청 (#278, D60 (1)). 고객센터가 문의를 커밋한 뒤 강의 내부 API로 보낸다.
 * 같은 명령 ID로 다시 보내면 강의는 처음 결과를 돌려준다(성공은 성공, 거절은 같은 사유).
 *
 * @param commandId   명령 ID. {@link LectureObjectionCommandType#FILE}의 {@code FILE:<문의 ID>}
 * @param inquiryId   이의 제기 문의 ID (고객센터)
 * @param orderItemId 이의 제기할 강의 주문 항목 ID
 * @param buyerUserId 문의를 쓴 회원 ID. 주문 항목의 구매자여야 한다
 * @param reason      이의 제기 사유 (문의 내용)
 */
public record LectureObjectionFileRequest(String commandId, Long inquiryId, Long orderItemId, Long buyerUserId,
                                          String reason) {

    // 빠진 값은 강의가 400(INVALID_REQUEST)으로 거절한다
    public LectureObjectionFileRequest {
        Objects.requireNonNull(commandId, "commandId는 필수입니다.");
        Objects.requireNonNull(inquiryId, "inquiryId는 필수입니다.");
        Objects.requireNonNull(orderItemId, "orderItemId는 필수입니다.");
        Objects.requireNonNull(buyerUserId, "buyerUserId는 필수입니다.");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason은 필수입니다.");
        }
    }

    /** 문의 하나의 접수 요청. 명령 ID는 문의 ID로 정해진다 */
    public static LectureObjectionFileRequest of(long inquiryId, long orderItemId, long buyerUserId, String reason) {
        return new LectureObjectionFileRequest(LectureObjectionCommandType.FILE.commandIdOf(inquiryId), inquiryId,
                orderItemId, buyerUserId, reason);
    }
}
