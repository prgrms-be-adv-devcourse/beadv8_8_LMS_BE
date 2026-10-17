package com.hapbang.journey.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.appearance.out.AppearanceUserRepository;
import com.hapbang.billing.app.LedgerEntry;
import com.hapbang.billing.app.LedgerOperation;
import com.hapbang.billing.app.LedgerRecordUseCase;
import com.hapbang.billing.app.WalletFacade;
import com.hapbang.billing.domain.BalanceBucket;
import com.hapbang.billing.domain.LedgerTransactionType;
import com.hapbang.billing.domain.PointType;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.chat.out.ChatUserRepository;
import com.hapbang.lecture.out.LectureUserRepository;
import com.hapbang.member.app.MemberFacade;
import com.hapbang.member.app.MemberSupport;
import com.hapbang.member.domain.OAuthProvider;
import com.hapbang.member.out.UserRepository;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.member.domain.Role;

import lombok.RequiredArgsConstructor;

/**
 * 여정 테스트 공용 준비 (#326). 회원은 실제 가입 경로(구글 로그인 → 가입 이벤트)로 만들고, 역할은 회원 모듈의 역할 변경
 * ({@code MemberSupport.changeRole} → {@code UserModifiedEvent})으로 바꾼 뒤 각 모듈 복제본에 반영될 때까지 기다린다.
 * 충전 HP는 PG(토스) 없이 충전 건과 CHARGE 원장을 직접 남긴다 (AppearanceBillingFlowIntegrationTest와 같은 방식).
 */
@TestComponent
@RequiredArgsConstructor
public class JourneyFixtures {

    public static final Duration EVENT_TIMEOUT = Duration.ofSeconds(10);
    private static final Instant FUNDED_AT = Instant.parse("2026-10-01T00:00:00Z");

    private final MemberFacade memberFacade;
    private final MemberSupport memberSupport;
    private final UserRepository userRepository;
    private final TransactionTemplate transactionTemplate;
    private final WalletFacade walletFacade;
    private final WalletRepository walletRepository;
    private final LedgerRecordUseCase ledgerRecordUseCase;
    private final JdbcTemplate jdbcTemplate;
    private final ChatUserRepository chatUserRepository;
    private final LectureUserRepository lectureUserRepository;
    private final AppearanceUserRepository appearanceUserRepository;

    /** 구글로 가입한 일반 회원. 지갑과 각 모듈 복제본이 생길 때까지 기다린다 */
    public Long user(String googleSub, String nickname) {
        Long userId = memberFacade.oauthLogin(OAuthProvider.GOOGLE, googleSub, googleSub + "@example.com", nickname)
                .getId();
        awaitReplicas(userId, Role.USER);
        return userId;
    }

    /** 가입한 뒤 역할을 바꾼 회원 (예: 인플루언서). 각 모듈 복제본에 새 역할이 반영될 때까지 기다린다 */
    public Long member(String googleSub, String nickname, Role role) {
        Long userId = user(googleSub, nickname);
        transactionTemplate.executeWithoutResult(status ->
                memberSupport.changeRole(userRepository.findById(userId).orElseThrow(), role));
        awaitReplicas(userId, role);
        return userId;
    }

    private void awaitReplicas(Long userId, Role role) {
        await().atMost(EVENT_TIMEOUT).untilAsserted(() -> {
            assertThat(walletRepository.findByUserId(userId)).isPresent();
            assertThat(chatUserRepository.findById(userId)).hasValueSatisfying(
                    replica -> assertThat(replica.getRole()).isEqualTo(role));
            assertThat(lectureUserRepository.findById(userId)).hasValueSatisfying(
                    replica -> assertThat(replica.getRole()).isEqualTo(role));
            assertThat(appearanceUserRepository.findById(userId)).hasValueSatisfying(
                    replica -> assertThat(replica.getRole()).isEqualTo(role));
        });
    }

    /** 충전 HP를 넣는다 (충전 건 + CHARGE 원장). {@code orderKey}는 PG 주문 번호라 충전마다 달라야 한다 */
    public void fund(Long userId, long amountHp, String orderKey) {
        long walletId = walletFacade.getOrCreateWallet(userId).getId();
        Long chargeId = jdbcTemplate.queryForObject("""
                INSERT INTO billing.point_charge (wallet_id, amount_hp, payment_amount_krw, remaining_principal_hp,
                    pg_provider, pg_order_id, status, requested_at, completed_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'TOSS_PAYMENTS', ?, 'COMPLETED', ?, ?, now(), now())
                RETURNING id""", Long.class,
                walletId, amountHp, amountHp, amountHp, "journey-" + orderKey, Timestamp.from(FUNDED_AT),
                Timestamp.from(FUNDED_AT));
        ledgerRecordUseCase.record(new LedgerOperation("CHARGE:" + chargeId, LedgerTransactionType.CHARGE,
                BillingReferenceType.POINT_CHARGE, chargeId, null, FUNDED_AT,
                List.of(LedgerEntry.of(walletId, PointType.CHARGE, BalanceBucket.AVAILABLE, amountHp))));
    }

    /** DB에서 새로 읽은 지갑 */
    public Wallet wallet(Long userId) {
        return walletRepository.findByUserId(userId).orElseThrow();
    }

    /** 참조 대상의 남은 보류 HP (billing.point_hold) */
    public long heldHp(BillingReferenceType referenceType, Long referenceId) {
        Long held = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(held_charge_hp + held_revenue_hp), 0) FROM billing.point_hold
                WHERE reference_type = ? AND reference_id = ?""", Long.class, referenceType.name(), referenceId);
        return held == null ? 0 : held;
    }
}
