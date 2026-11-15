package com.hapbang.billing.e2e;

import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/** 전용 e2eServer Jar에서만 존재한다. X-Billing-E2e-Key가 맞는 loopback 테스트 요청만 받는다. */
@RestController
@Profile("e2e")
@RequestMapping("/__test/billing")
public class BillingE2eController {

    private final BillingE2eFixtures fixtures;
    private final BillingE2eServerConfiguration.RequestObservation requests;

    public BillingE2eController(BillingE2eFixtures fixtures, BillingE2eServerConfiguration.RequestObservation requests) {
        this.fixtures = fixtures;
        this.requests = requests;
    }

    @PostMapping("/reset")
    public Map<String, Boolean> reset() {
        fixtures.reset();
        requests.clear();
        return Map.of("reset", true);
    }

    @PostMapping("/wallets")
    public BillingE2eFixtures.WalletView wallets(@Valid @RequestBody WalletSeed request) {
        return fixtures.seedWallet(request.userId(), request.chargeHp(), request.revenueHp());
    }

    @PostMapping("/wallets/{userId}/close")
    public BillingE2eFixtures.WalletView close(@PathVariable("userId") long userId) {
        return fixtures.close(userId);
    }

    @GetMapping("/snapshot")
    public BillingE2eFixtures.Snapshot snapshot(@RequestParam("userId") long userId) {
        return fixtures.snapshot(userId);
    }

    @GetMapping("/requests/last")
    public ResponseEntity<BillingE2eServerConfiguration.RequestHeaders> lastRequest() {
        return ResponseEntity.ofNullable(requests.last());
    }

    public record WalletSeed(@NotNull @Positive Long userId, @PositiveOrZero long chargeHp,
                             @PositiveOrZero long revenueHp) { }
}
