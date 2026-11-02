package com.hapbang.lecture.fixture;

import java.util.concurrent.atomic.AtomicLong;

import com.hapbang.lecture.app.LectureObjectionCommandResult;
import com.hapbang.lecture.app.LectureObjectionFacade;
import com.hapbang.lecture.domain.LectureErrorCode;
import com.hapbang.lecture.domain.LectureException;
import com.hapbang.shared.customersupport.event.LectureObjectionResolutionRequestedEvent;
import com.hapbang.shared.customersupport.event.LectureObjectionWithdrawalRequestedEvent;
import com.hapbang.shared.lecture.domain.LectureObjectionCommandType;
import com.hapbang.shared.lecture.dto.LectureObjectionFileRequest;

/**
 * 테스트에서 이의 제기 명령을 보낸다 (#278). 부를 때마다 새 명령 ID를 쓰므로 호출 하나가 고객센터의 새 명령 하나다.
 * 거절이면 판단·철회도 접수처럼 {@link LectureException}(OBJECTION_*)으로 던져, 항목 규칙을 확인하는 테스트가 그대로 쓴다.
 * 같은 명령 ID의 재현·충돌은 {@link LectureObjectionFacade}를 직접 불러 확인한다. 접수는 내부 API 역할({@code INTERNAL})이 필요하다.
 */
public final class ObjectionCommands {

    private static final AtomicLong SEQUENCE = new AtomicLong();

    private final LectureObjectionFacade lectureObjectionFacade;

    public ObjectionCommands(LectureObjectionFacade lectureObjectionFacade) {
        this.lectureObjectionFacade = lectureObjectionFacade;
    }

    public void file(Long orderItemId, Long buyerUserId, Long inquiryId, String reason) {
        lectureObjectionFacade.file(new LectureObjectionFileRequest(commandId(LectureObjectionCommandType.FILE, inquiryId),
                inquiryId, orderItemId, buyerUserId, reason));
    }

    public void resolve(Long orderItemId, Long inquiryId, boolean accepted, Long adminUserId) {
        throwIfRefused(lectureObjectionFacade.resolve(new LectureObjectionResolutionRequestedEvent(
                commandId(LectureObjectionCommandType.RESOLVE, inquiryId), inquiryId, orderItemId, accepted,
                adminUserId)));
    }

    public void withdraw(Long orderItemId, Long buyerUserId, Long inquiryId) {
        throwIfRefused(lectureObjectionFacade.withdraw(new LectureObjectionWithdrawalRequestedEvent(
                commandId(LectureObjectionCommandType.WITHDRAW, inquiryId), inquiryId, orderItemId, buyerUserId)));
    }

    private static String commandId(LectureObjectionCommandType type, Long inquiryId) {
        return type.commandIdOf(inquiryId) + ":TEST-" + SEQUENCE.incrementAndGet();
    }

    private static void throwIfRefused(LectureObjectionCommandResult result) {
        if (!result.succeeded()) {
            throw new LectureException(LectureErrorCode.valueOf(result.failureCode().name()));
        }
    }
}
