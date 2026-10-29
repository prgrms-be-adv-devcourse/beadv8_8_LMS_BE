package com.hapbang.lecture.fake;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;

import com.hapbang.shared.billing.dto.HoldCommand;
import com.hapbang.shared.billing.dto.HoldResultDto;
import com.hapbang.shared.billing.dto.PayoutCommand;
import com.hapbang.shared.billing.dto.PayoutResultDto;
import com.hapbang.shared.billing.dto.PenaltyCommand;
import com.hapbang.shared.billing.dto.PenaltyResultDto;
import com.hapbang.shared.billing.dto.ReleaseCommand;
import com.hapbang.shared.billing.dto.ReleaseResultDto;
import com.hapbang.shared.billing.out.BillingApiClient;
import com.hapbang.shared.billing.out.BillingCallResult;
import com.hapbang.shared.billing.out.BillingClientProperties;

/**
 * 강의 모듈 테스트용 지갑 내부 API Client (#275). 모듈 테스트에는 {@code hapbang.clients.billing.base-url}이 없어
 * 실제 Client 빈이 생기지 않으므로 이 대역으로 채운다. HTTP를 부르지 않는다.
 * <ul>
 *     <li>정한 응답이 없으면 성공이다. 보류는 요청 금액, 반환·지급은 {@link #setSettledHp}(기본 10,000 HP)를 돌려준다.</li>
 *     <li>{@link #respond}로 다음 호출의 응답(실패·예외 포함)을 차례로 정한다.</li>
 *     <li>호출마다 요청과 그때 강의 트랜잭션이 열려 있었는지를 남긴다.</li>
 * </ul>
 * 컨텍스트가 테스트 사이에 재사용되므로 테스트마다 {@link #reset()}한다.
 * 빈은 {@link FakeBillingApiClientConfig}가 등록한다(부모 클래스의 설정 애노테이션이 처리되지 않게 {@code @Component}로 두지 않는다).
 */
public class FakeBillingApiClient extends BillingApiClient {

    private final Deque<Function<Object, BillingCallResult<?>>> responses = new ArrayDeque<>();
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private volatile long settledHp = 10_000L;
    private volatile Runnable duringCall = () -> {
    };
    private volatile Consumer<HoldCommand> heldListener = hold -> {
    };

    public FakeBillingApiClient() {
        super(RestClient.builder(), new BillingClientProperties("http://127.0.0.1:9", null, null), "test");
    }

    public synchronized void reset() {
        responses.clear();
        calls.clear();
        settledHp = 10_000L;
        duringCall = () -> {
        };
    }

    /** 다음 호출 하나의 응답. 요청을 받아 결과를 돌려주거나 예외를 던진다 */
    public synchronized void respond(Function<Object, BillingCallResult<?>> response) {
        responses.addLast(response);
    }

    /** 반환·지급 결과 금액 */
    public void setSettledHp(long settledHp) {
        this.settledHp = settledHp;
    }

    /** 호출하는 동안(응답 전) 실행할 일. 늦은 결과·동시 실행을 만든다 */
    public void setDuringCall(Runnable duringCall) {
        this.duringCall = duringCall;
    }

    /** 보류가 성공할 때마다 알린다. 설정({@link FakeBillingApiClientConfig})이 한 번 정하고 {@link #reset()}해도 남는다 */
    void onHeld(Consumer<HoldCommand> heldListener) {
        this.heldListener = heldListener;
    }

    public List<Call> calls() {
        return List.copyOf(calls);
    }

    @Override
    public BillingCallResult<HoldResultDto> hold(HoldCommand command) {
        BillingCallResult<HoldResultDto> result = answer(command, () -> new HoldResultDto(command.referenceType(),
                command.referenceId(), command.amountHp(), false));
        if (result instanceof BillingCallResult.Succeeded<HoldResultDto>) {
            heldListener.accept(command);
        }
        return result;
    }

    @Override
    public BillingCallResult<ReleaseResultDto> release(ReleaseCommand command) {
        return answer(command, () -> new ReleaseResultDto(command.referenceType(), command.referenceId(),
                command.releaseRequestId(), settledHp, 0L, false));
    }

    @Override
    public BillingCallResult<PayoutResultDto> payout(PayoutCommand command) {
        return answer(command, () -> new PayoutResultDto(command.referenceType(), command.referenceId(),
                command.payoutRequestId(), command.receiverUserId(), command.sourceType(), command.sourceId(),
                settledHp, 0L, false));
    }

    @Override
    public BillingCallResult<PenaltyResultDto> settlePenalty(PenaltyCommand command) {
        throw new UnsupportedOperationException("강의는 위약금을 정산하지 않는다");
    }

    @SuppressWarnings("unchecked")
    private <T> BillingCallResult<T> answer(Object command, Supplier<T> success) {
        calls.add(new Call(command, TransactionSynchronizationManager.isActualTransactionActive()));
        duringCall.run();
        Function<Object, BillingCallResult<?>> response;
        synchronized (this) {
            response = responses.pollFirst();
        }
        if (response == null) {
            return new BillingCallResult.Succeeded<>(success.get());
        }
        return (BillingCallResult<T>) response.apply(command);
    }

    /** 받은 요청과 그때 트랜잭션이 열려 있었는지 */
    public record Call(Object command, boolean transactionActive) {
    }
}
