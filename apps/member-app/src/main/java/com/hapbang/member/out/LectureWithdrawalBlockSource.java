package com.hapbang.member.out;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.hapbang.member.app.MemberWithdrawalUseCase;
import com.hapbang.shared.lecture.out.LectureApiClient;
import com.hapbang.shared.member.dto.WithdrawalBlocksDto;

import lombok.RequiredArgsConstructor;

/**
 * 강의 쪽 탈퇴가 막힌 이유 (#654, docs/api/internal-withdrawals.md 3장, 강의 #625). 강의 내부 API를 읽기만 한다.
 * 실패 처리와 만드는 조건은 {@link AppearanceWithdrawalBlockSource}와 같다. 출연 다음에 묻는다
 */
@Component
@Order(2)
@ConditionalOnBooleanProperty(MemberWithdrawalUseCase.ENABLED)
@ConditionalOnProperty(prefix = "hapbang.clients.lecture", name = "base-url")
@RequiredArgsConstructor
public class LectureWithdrawalBlockSource implements WithdrawalBlockSource {

    private final LectureApiClient lectureApiClient;

    @Override
    public String name() {
        return "lecture";
    }

    @Override
    public WithdrawalBlocksDto blocks(long userId) {
        try {
            return lectureApiClient.getWithdrawalBlocks(userId);
        } catch (RuntimeException e) {
            throw new WithdrawalCallException(WithdrawalCallException.Kind.NOT_PROCESSED,
                    "강의 탈퇴 확인을 부르지 못했습니다.", e);
        }
    }
}
