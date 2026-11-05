package com.hapbang.lecture.fake;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;

import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.domain.HoldVoidOutcome;
import com.hapbang.shared.billing.dto.HoldCommand;
import com.hapbang.shared.billing.dto.HoldQueryCommand;
import com.hapbang.shared.billing.dto.HoldResultDto;
import com.hapbang.shared.billing.dto.HoldStateDto;
import com.hapbang.shared.billing.dto.HoldStatePageDto;
import com.hapbang.shared.billing.dto.HoldVoidCommand;
import com.hapbang.shared.billing.dto.HoldVoidResultDto;
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
 *     <li>정한 응답이 없으면 성공이다. 보류는 요청 금액, 반환·지급은 {@link #setSettledHp}(기본 10,000 HP)를 돌려준다.
 *     보류 무효화는 보류가 없었던 결과({@code VOIDED}, 0 HP)다.</li>
 *     <li>{@link #respond}로 다음 호출의 응답(실패·예외 포함)을 차례로 정한다.</li>
 *     <li>대사 조회(#580): 참조 대상별 조회는 {@link #putHoldState}로 넣은 기록을, 없으면 빈 기록을 준다.
 *     목록 조회는 {@link #addHoldPage}로 넣은 페이지를 차례로 주고, 없으면 요청한 커서 그대로의 빈 마지막 페이지다.</li>
 *     <li>호출마다 요청과 그때 강의 트랜잭션이 열려 있었는지를 남긴다.</li>
 *     <li>{@link #setCircuitOpen}으로 지갑 회로가 열린 상태를 흉내 낸다(D79). 기본은 닫혀 있다.</li>
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
    private final Map<Long, HoldStateDto> holdStates = new ConcurrentHashMap<>();
    private final Deque<HoldStatePageDto> holdPages = new ArrayDeque<>();
    private volatile boolean circuitOpen;

    public FakeBillingApiClient() {
        super(RestClient.builder(), new BillingClientProperties("http://127.0.0.1:9", null, null), "test");
    }

    public synchronized void reset() {
        responses.clear();
        calls.clear();
        settledHp = 10_000L;
        duringCall = () -> {
        };
        holdStates.clear();
        holdPages.clear();
        circuitOpen = false;
    }

    /** 지갑 회로가 열려 있는지. 열려 있으면 실행기는 작업을 선점하지 않는다 (D79) */
    public void setCircuitOpen(boolean circuitOpen) {
        this.circuitOpen = circuitOpen;
    }

    @Override
    public boolean isCircuitOpen() {
        return circuitOpen;
    }

    /** 참조 대상별 조회가 돌려줄 지갑 기록 (주문 항목 ID 기준) */
    public void putHoldState(HoldStateDto state) {
        holdStates.put(state.referenceId(), state);
    }

    /** 목록 조회가 다음에 돌려줄 페이지 */
    public synchronized void addHoldPage(HoldStatePageDto page) {
        holdPages.addLast(page);
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

    public List<Call> calls() {
        return List.copyOf(calls);
    }

    @Override
    public BillingCallResult<HoldResultDto> hold(HoldCommand command) {
        return answer(command, () -> new HoldResultDto(command.referenceType(), command.referenceId(),
                command.amountHp(), false));
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
    public BillingCallResult<HoldVoidResultDto> voidHold(HoldVoidCommand command) {
        return answer(command, () -> new HoldVoidResultDto(command.referenceType(), command.referenceId(),
                HoldVoidOutcome.VOIDED, 0L, false));
    }

    @Override
    public BillingCallResult<List<HoldStateDto>> queryHolds(HoldQueryCommand command) {
        return answer(command, () -> command.referenceIds().stream()
                .map(id -> holdStates.getOrDefault(id,
                        new HoldStateDto(command.referenceType(), id, null, null, List.of())))
                .toList());
    }

    @Override
    public BillingCallResult<HoldStatePageDto> listHolds(BillingReferenceType referenceType, Instant updatedAfter,
                                                        long afterId, int size) {
        HoldListQuery query = new HoldListQuery(referenceType, updatedAfter, afterId, size);
        return answer(query, () -> {
            synchronized (this) {
                HoldStatePageDto page = holdPages.pollFirst();
                return page != null ? page : new HoldStatePageDto(List.of(), updatedAfter, afterId, false);
            }
        });
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

    /** 목록 조회 요청 (호출 기록용) */
    public record HoldListQuery(BillingReferenceType referenceType, Instant updatedAfter, long afterId, int size) {
    }

    /** 받은 요청과 그때 트랜잭션이 열려 있었는지 */
    public record Call(Object command, boolean transactionActive) {
    }
}
