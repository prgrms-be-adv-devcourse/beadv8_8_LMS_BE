package com.hapbang.lecture.app;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import com.hapbang.lecture.domain.LectureErrorCode;
import com.hapbang.lecture.domain.LectureException;
import com.hapbang.shared.customersupport.event.LectureObjectionResolutionRequestedEvent;
import com.hapbang.shared.customersupport.event.LectureObjectionWithdrawalRequestedEvent;
import com.hapbang.shared.lecture.dto.LectureObjectionFileRequest;
import com.hapbang.shared.lecture.dto.LectureObjectionFiledDto;

import lombok.RequiredArgsConstructor;

/**
 * 내용 다름 이의 제기 접수·판단·철회 진입점 (설계 6.2, #278, D60·D71). 고객센터가 보낸 명령을 강의 트랜잭션 하나로 처리한다.
 * <ul>
 *     <li>접수: 내부 HTTP({@code POST /api/v1/internal/lectures/objections/file}). 내부 API 키로 인증한 호출만 쓴다.
 *     거절은 커밋 뒤 {@link LectureException}(OBJECTION_*)으로 응답한다.</li>
 *     <li>판단·철회: 고객센터 이벤트({@code customersupport.events.v1})를 Kafka 리스너가 넘긴다. 거절도 결과 이벤트로 나가므로 예외가 아니다.</li>
 * </ul>
 * 트랜잭션과 명령 ID 중복 처리는 {@link LectureObjectionCommandUseCase}가 한다. 이 Facade는 트랜잭션을 열지 않는다.
 */
@Service
@RequiredArgsConstructor
public class LectureObjectionFacade {

    private final LectureObjectionCommandUseCase lectureObjectionCommandUseCase;

    // 내부 API 보안 체인(global InternalApiSecurityConfig)이 키를 검사하고, 회원 세션이 이 진입점에 오지 못하게 한 번 더 막는다 (D63)
    @PreAuthorize("hasRole('INTERNAL')")
    public LectureObjectionFiledDto file(LectureObjectionFileRequest request) {
        LectureObjectionCommandResult result = lectureObjectionCommandUseCase.file(request);
        if (!result.succeeded()) {
            // 거절도 명령 이력에 커밋한 뒤 응답한다. 같은 명령이 다시 오면 같은 사유로 거절된다
            throw new LectureException(LectureErrorCode.valueOf(result.failureCode().name()));
        }
        return new LectureObjectionFiledDto(result.commandId(), result.inquiryId(), result.orderItemId());
    }

    public LectureObjectionCommandResult resolve(LectureObjectionResolutionRequestedEvent command) {
        return lectureObjectionCommandUseCase.resolve(command);
    }

    public LectureObjectionCommandResult withdraw(LectureObjectionWithdrawalRequestedEvent command) {
        return lectureObjectionCommandUseCase.withdraw(command);
    }
}
