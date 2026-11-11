package com.hapbang.testsupport.billing;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;

import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.out.BillingApiClient;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

/** 실제 billing의 테스트 데이터·관측·통신 장애만 관리한다. 금융 결과는 서버가 만든다. */
public final class RealBillingTestFixture {
    private static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() { };
    private final RealBillingServer server;
    private final BillingTransport transport;
    private final RestClient setup;
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final List<CallGate> gates = new ArrayList<>();
    private BillingApiClient client;
    private BillingApiClient target;

    RealBillingTestFixture(RealBillingServer server) {
        this.server = server;
        transport = new BillingTransport(server.baseUrl());
        setup = RestClient.builder().baseUrl(server.baseUrl())
                .defaultHeader("X-Billing-E2e-Key", "test-billing-e2e-key").build();
    }

    public String baseUrl() { return transport.baseUrl(); }
    public String directBaseUrl() { return server.baseUrl(); }
    public BillingApiClient client() { return client; }
    void attach(BillingApiClient proxy, BillingApiClient target) { this.client = proxy; this.target = target; }

    public void reset() {
        transport.reset();
        synchronized (gates) { gates.clear(); }
        clearCalls();
        if (target != null) circuit().reset();
        setup.post().uri("/__test/billing/reset").retrieve().toBodilessEntity();
    }

    /** 양수인 금액만 실제 입금·원장으로 준비한다. 기존 잔액을 덮어쓰지 않는다. */
    public Map<String, Object> wallet(long userId, long chargeHp, long revenueHp) {
        return setup.post().uri("/__test/billing/wallets")
                .body(Map.of("userId", userId, "chargeHp", chargeHp, "revenueHp", revenueHp))
                .retrieve().body(JSON);
    }

    public void closeWallet(long userId) {
        setup.post().uri("/__test/billing/wallets/{userId}/close", userId).retrieve().toBodilessEntity();
    }

    public Map<String, Object> snapshot(long userId) {
        return setup.get().uri("/__test/billing/snapshot?userId={userId}", userId).retrieve().body(JSON);
    }

    public List<Call> calls() { return List.copyOf(calls); }
    public void clearCalls() { calls.clear(); }
    public void duringNextCall(Runnable action) { duringCall(command -> true, action); }
    public void duringCall(Predicate<Object> matches, Runnable action) {
        synchronized (gates) { gates.add(new CallGate(matches, action)); }
    }

    void beforeCall(String method, Object[] arguments) {
        Object command = method.equals("listHolds")
                ? new HoldListQuery((BillingReferenceType) arguments[0], (Instant) arguments[1],
                        (long) arguments[2], (int) arguments[3]) : arguments[0];
        calls.add(new Call(command, TransactionSynchronizationManager.isActualTransactionActive()));
        Runnable gate = null;
        synchronized (gates) {
            for (int i = 0; i < gates.size(); i++) {
                if (gates.get(i).matches().test(command)) { gate = gates.remove(i).action(); break; }
            }
        }
        if (gate != null) gate.run();
    }

    public void disconnect() { transport.disconnect(); }
    public void reconnect() { transport.reconnect(); }
    public void dropResponses(int count) { transport.dropResponses(count); }
    public void afterNextResponse(String pathSuffix, Runnable action) { transport.afterNextResponse(pathSuffix, action); }
    public void dropNextResponse(String pathSuffix) { afterNextResponse(pathSuffix, () -> dropResponses(1)); }
    public void openCircuit() { circuit().transitionToOpenState(); }
    public void closeCircuit() { circuit().reset(); }

    private CircuitBreaker circuit() { return (CircuitBreaker) ReflectionTestUtils.getField(target, "circuitBreaker"); }

    public Map<String, Object> promotionWallet(long amountHp) {
        return RestClient.builder().baseUrl(directBaseUrl()).defaultHeader("X-User-Id", "1")
                .defaultHeader("X-User-Role", "ADMIN").build()
                .post().uri("/api/v1/settlements/admin/promotion-wallet/fundings")
                .body(Map.of("fundingWorkId", UUID.randomUUID().toString(), "amountHp", amountHp,
                        "sourceType", "ADMIN_GRANT", "sourceRef", "통합 테스트", "memo", "통합 테스트 예산"))
                .retrieve().body(JSON);
    }

    public record Call(Object command, boolean transactionActive) { }
    public record HoldListQuery(BillingReferenceType referenceType, Instant updatedAfter, long afterId, int size) { }
    private record CallGate(Predicate<Object> matches, Runnable action) { }
}
