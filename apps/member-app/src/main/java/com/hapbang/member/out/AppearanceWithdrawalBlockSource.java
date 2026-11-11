package com.hapbang.member.out;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.hapbang.member.app.MemberWithdrawalUseCase;
import com.hapbang.shared.appearance.out.AppearanceApiCallException;
import com.hapbang.shared.appearance.out.AppearanceApiClient;
import com.hapbang.shared.member.dto.WithdrawalBlocksDto;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 출연 쪽 탈퇴가 막힌 이유 (#654, docs/api/internal-withdrawals.md 3장, 출연 #624). 출연 내부 API를 읽기만 한다.
 * <ul>
 *     <li>어떤 실패든 {@link WithdrawalCallException.Kind#NOT_PROCESSED}다. 읽기라 되돌릴 것이 없고, 회원은 탈퇴를 멈춘다(503).
 *     출연이 4xx로 거절하면(내부 키 오류, 엔드포인트 없음 등) 다시 해도 같아 사람이 보도록 error 로그를 남긴다(지갑 연결과 같은 기준).</li>
 *     <li>탈퇴 기능이 켜져 있고 출연 Client 주소가 있을 때만 만든다(Client와 같은 조건, 채팅 ChatAppearanceApiConfig와 같은 방식).
 *     운영에서 주소를 빠뜨리면 이 빈이 없어, 출연·강의를 모두 요구하는 탈퇴 오케스트레이터가 시작하지 못해 배포 때 드러난다.</li>
 *     <li>출연 회로({@link MemberOutboundCircuitBreakers}, 받은 리뷰 목록과 같은 회로) 안에서 부른다. 회로가 열려 있으면 부르지 않고
 *     바로 실패한다.</li>
 *     <li>출연 → 강의 순서로 묻는다({@link Order}).</li>
 * </ul>
 */
@Slf4j
@Component
@Order(1)
@ConditionalOnBooleanProperty(MemberWithdrawalUseCase.ENABLED)
@ConditionalOnProperty(prefix = "hapbang.clients.appearance", name = "base-url")
@RequiredArgsConstructor
public class AppearanceWithdrawalBlockSource implements WithdrawalBlockSource {

    private final AppearanceApiClient appearanceApiClient;
    private final MemberOutboundCircuitBreakers circuitBreakers;

    @Override
    public String name() {
        return "appearance";
    }

    @Override
    public WithdrawalBlocksDto blocks(long userId) {
        try {
            return circuitBreakers.call(MemberOutboundCircuitBreakers.Target.APPEARANCE,
                    () -> appearanceApiClient.getWithdrawalBlocks(userId));
        } catch (AppearanceApiCallException e) {
            if (e.getReason() == AppearanceApiCallException.Reason.REJECTED) {
                log.error("출연이 탈퇴 확인을 거절했습니다. 설정이나 코드를 확인해야 합니다. userId={}, status={}, code={}",
                        userId, e.getStatus(), e.getCode());
            }
            throw new WithdrawalCallException(WithdrawalCallException.Kind.NOT_PROCESSED,
                    "출연 탈퇴 확인을 부르지 못했습니다.", e);
        } catch (RuntimeException e) {
            throw new WithdrawalCallException(WithdrawalCallException.Kind.NOT_PROCESSED,
                    "출연 탈퇴 확인을 부르지 못했습니다.", e);
        }
    }
}
