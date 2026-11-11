package com.hapbang.member.out;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.hapbang.member.app.MemberWithdrawalUseCase;
import com.hapbang.shared.lecture.out.LectureApiCallException;
import com.hapbang.shared.lecture.out.LectureApiClient;
import com.hapbang.shared.member.dto.WithdrawalBlocksDto;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 강의 쪽 탈퇴가 막힌 이유 (#654, docs/api/internal-withdrawals.md 3장, 강의 #625). 강의 내부 API를 읽기만 한다.
 * 실패 처리와 만드는 조건은 {@link AppearanceWithdrawalBlockSource}와 같다. 강의 회로 안에서 부르고, 출연 다음에 묻는다
 */
@Slf4j
@Component
@Order(2)
@ConditionalOnBooleanProperty(MemberWithdrawalUseCase.ENABLED)
@ConditionalOnProperty(prefix = "hapbang.clients.lecture", name = "base-url")
@RequiredArgsConstructor
public class LectureWithdrawalBlockSource implements WithdrawalBlockSource {

    private final LectureApiClient lectureApiClient;
    private final MemberOutboundCircuitBreakers circuitBreakers;

    @Override
    public String name() {
        return "lecture";
    }

    @Override
    public WithdrawalBlocksDto blocks(long userId) {
        try {
            return circuitBreakers.call(MemberOutboundCircuitBreakers.Target.LECTURE,
                    () -> lectureApiClient.getWithdrawalBlocks(userId));
        } catch (LectureApiCallException e) {
            if (e.getReason() == LectureApiCallException.Reason.REJECTED) {
                log.error("강의가 탈퇴 확인을 거절했습니다. 설정이나 코드를 확인해야 합니다. userId={}, status={}, code={}",
                        userId, e.getStatus(), e.getCode());
            }
            throw new WithdrawalCallException(WithdrawalCallException.Kind.NOT_PROCESSED,
                    "강의 탈퇴 확인을 부르지 못했습니다.", e);
        } catch (RuntimeException e) {
            throw new WithdrawalCallException(WithdrawalCallException.Kind.NOT_PROCESSED,
                    "강의 탈퇴 확인을 부르지 못했습니다.", e);
        }
    }
}
