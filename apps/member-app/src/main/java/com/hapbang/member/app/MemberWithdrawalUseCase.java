package com.hapbang.member.app;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.member.domain.MemberErrorCode;
import com.hapbang.member.domain.MemberException;
import com.hapbang.member.domain.User;
import com.hapbang.member.domain.Withdrawal;
import com.hapbang.member.domain.WithdrawalBlockedException;
import com.hapbang.member.domain.WithdrawalStatus;
import com.hapbang.member.out.RefreshTokenStore;
import com.hapbang.member.out.WalletCloseResult;
import com.hapbang.member.out.WalletWithdrawalGate;
import com.hapbang.member.out.WithdrawalBlockSource;
import com.hapbang.member.out.WithdrawalCallException;
import com.hapbang.member.out.WithdrawalRepository;
import com.hapbang.shared.member.dto.WithdrawalBlockReasonDto;
import com.hapbang.shared.member.event.UserModifiedEvent;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

/**
 * 회원 탈퇴 오케스트레이터 (#593, docs/api/internal-withdrawals.md 1·5장). 회원 앱이 탈퇴 확인을 지휘하는 사가다.
 * <ol>
 *     <li>탈퇴 작업 기록(사가 로그)을 만든다. 작업 ID가 지갑 호출의 멱등 키다</li>
 *     <li>출연·강의에 막힌 이유를 묻는다 (읽기만, 되돌릴 것 없음)</li>
 *     <li>지갑에 "확인하고 닫기"를 부른다. 막힌 이유를 한 번에 모아 보여 주려고 앞에서 막혀도 부른다</li>
 *     <li>이유가 하나라도 있으면 닫힌 지갑을 다시 열고(보상) 409 + 이유 모음. 회원이 직접 정리한 뒤 다시 탈퇴한다</li>
 *     <li>이유가 없으면 회원을 탈퇴시키고(확정 지점) UserModifiedEvent를 아웃박스로 남긴다. 실패하면 지갑을 다시 연다</li>
 *     <li>커밋 뒤 refresh token을 모두 폐기한다</li>
 * </ol>
 * 다른 앱 호출은 모두 트랜잭션 밖이다(AGENTS.md 3장). 상태는 짧은 트랜잭션으로 먼저 남기고 결과도 짧은 트랜잭션으로 기록한다.
 * 결과를 모르는 지갑 호출은 바로 다시 열기를 한 번 시도하고, 그래도 모르면 NEEDS_RECOVERY로 남겨 배치가 이어서 처리한다.
 * <p>
 * 기능 스위치({@value #ENABLED})가 켜졌을 때만 만든다. 켰는데 출연·강의·지갑 연결 코드가 없으면 앱이 시작하지 않는다
 */
@Slf4j
@Service
@ConditionalOnBooleanProperty(MemberWithdrawalUseCase.ENABLED)
public class MemberWithdrawalUseCase {

    /** 탈퇴 기능 스위치. 기본은 꺼짐 (지갑·출연·강의 구현이 끝나면 켠다) */
    public static final String ENABLED = "hapbang.member.withdrawal.enabled";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final WithdrawalRepository withdrawalRepository;
    private final MemberSupport memberSupport;
    private final List<WithdrawalBlockSource> blockSources;
    private final WalletWithdrawalGate walletGate;
    private final RefreshTokenStore refreshTokenStore;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;

    public MemberWithdrawalUseCase(WithdrawalRepository withdrawalRepository, MemberSupport memberSupport,
                                   List<WithdrawalBlockSource> blockSources, WalletWithdrawalGate walletGate,
                                   RefreshTokenStore refreshTokenStore, ApplicationEventPublisher eventPublisher,
                                   TransactionTemplate transactionTemplate) {
        if (blockSources.isEmpty()) {
            throw new IllegalStateException("탈퇴 기능을 켰지만 막힌 이유를 알려 줄 앱(출연·강의) 연결 코드가 없습니다.");
        }
        this.withdrawalRepository = withdrawalRepository;
        this.memberSupport = memberSupport;
        this.blockSources = List.copyOf(blockSources);
        this.walletGate = walletGate;
        this.refreshTokenStore = refreshTokenStore;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 탈퇴한다
     *
     * @throws WithdrawalBlockedException 남은 돈·거래가 있어 막힘 (409, 이유 모음)
     * @throws MemberException            {@code WITHDRAWAL_IN_PROGRESS}(409), {@code WITHDRAWAL_UNAVAILABLE}(503),
     *                                    정상 회원이 아니면 {@code MEMBER_NOT_FOUND}(404)
     */
    public void withdraw(Long userId) {
        long withdrawalId = start(userId);

        List<WithdrawalBlockReasonDto> reasons = new ArrayList<>();
        try {
            for (WithdrawalBlockSource source : blockSources) {
                reasons.addAll(source.blocks(userId).reasons());
            }
        } catch (RuntimeException e) {
            log.warn("탈퇴 막힌 이유를 확인하지 못했습니다. withdrawalId={}", withdrawalId, e);
            change(withdrawalId, withdrawal -> withdrawal.rollBack(error("막힌 이유 확인 실패", e), now()));
            throw new MemberException(MemberErrorCode.WITHDRAWAL_UNAVAILABLE, e);
        }

        WalletCloseResult wallet = closeWallet(withdrawalId, userId);
        reasons.addAll(wallet.reasons());
        if (!reasons.isEmpty()) {
            block(withdrawalId, userId, wallet.closed(), reasons);
        }

        change(withdrawalId, Withdrawal::walletClosed);
        complete(withdrawalId, userId);
        revokeTokens(withdrawalId, userId);
    }

    /** [트랜잭션] 정상 회원 확인 → 탈퇴 작업 시작. 진행 중인 작업이 있으면 부분 유일 인덱스가 막는다 */
    private long start(Long userId) {
        try {
            return transactionTemplate.execute(status -> {
                User user = memberSupport.findActiveUser(userId);
                return withdrawalRepository.saveAndFlush(Withdrawal.start(user)).getId();
            });
        } catch (DataIntegrityViolationException e) {
            throw new MemberException(MemberErrorCode.WITHDRAWAL_IN_PROGRESS, e);
        }
    }

    /** 지갑 "확인하고 닫기". 답을 못 받으면 되돌리고(결과 불명이면 다시 열기까지) 503 */
    private WalletCloseResult closeWallet(long withdrawalId, Long userId) {
        try {
            return walletGate.close(withdrawalId, userId);
        } catch (WithdrawalCallException e) {
            log.warn("지갑 닫기 결과를 받지 못했습니다. withdrawalId={}, kind={}", withdrawalId, e.getKind(), e);
            if (e.getKind() == WithdrawalCallException.Kind.NOT_PROCESSED) {
                change(withdrawalId, withdrawal -> withdrawal.rollBack(error("지갑 닫기 처리 안 됨", e), now()));
            } else {
                change(withdrawalId, withdrawal -> withdrawal.needRecovery(error("지갑 닫기 결과 불명", e)));
                reopenWallet(withdrawalId, userId, withdrawal -> withdrawal.rollBack("지갑 닫기 결과 불명, 다시 열기 완료", now()));
            }
            throw new MemberException(MemberErrorCode.WITHDRAWAL_UNAVAILABLE, e);
        }
    }

    /** 막힘: 닫힌 지갑이 있으면 다시 열고(보상) 이유를 남긴 뒤 409. 다시 열기를 못 하면 배치에 맡기고 이유는 그대로 알려 준다 */
    private void block(long withdrawalId, Long userId, boolean walletClosed, List<WithdrawalBlockReasonDto> reasons) {
        String reasonsJson = JSON.writeValueAsString(reasons);
        if (!walletClosed) {
            change(withdrawalId, withdrawal -> withdrawal.block(reasonsJson, now()));
        } else {
            change(withdrawalId, Withdrawal::walletClosed);
            reopenWallet(withdrawalId, userId, withdrawal -> withdrawal.block(reasonsJson, now()));
        }
        throw new WithdrawalBlockedException(reasons);
    }

    /** [트랜잭션 · 확정 지점] 회원 탈퇴 + 작업 완료 + UserModifiedEvent(아웃박스). 실패하면 지갑을 다시 연다 */
    private void complete(long withdrawalId, Long userId) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                Withdrawal withdrawal = find(withdrawalId);
                User user = withdrawal.getUser();
                LocalDateTime now = now();
                user.withdraw(now);
                memberSupport.flush();
                withdrawal.complete(now);
                eventPublisher.publishEvent(new UserModifiedEvent(user.toDto()));
            });
        } catch (RuntimeException e) {
            log.warn("회원 탈퇴 저장에 실패해 지갑을 다시 엽니다. withdrawalId={}", withdrawalId, e);
            reopenWallet(withdrawalId, userId, withdrawal -> withdrawal.rollBack(error("회원 탈퇴 저장 실패", e), now()));
            throw new MemberException(MemberErrorCode.WITHDRAWAL_UNAVAILABLE, e);
        }
    }

    /**
     * 지갑 다시 열기(보상). 성공하면 {@code afterReopen}으로 작업을 끝내고, 답을 못 받으면 NEEDS_RECOVERY로 남겨 배치가 이어서 연다
     */
    private void reopenWallet(long withdrawalId, Long userId, Consumer<Withdrawal> afterReopen) {
        try {
            walletGate.reopen(withdrawalId, userId);
        } catch (RuntimeException e) {
            log.error("지갑을 다시 열지 못했습니다. 배치가 다시 시도합니다. withdrawalId={}", withdrawalId, e);
            String error = error("지갑 다시 열기 실패", e);
            change(withdrawalId, withdrawal -> {
                if (withdrawal.getStatus() == WithdrawalStatus.NEEDS_RECOVERY) {
                    withdrawal.recoveryFailed(error);
                } else {
                    withdrawal.needRecovery(error);
                }
            });
            return;
        }
        change(withdrawalId, afterReopen);
    }

    /** 커밋 뒤 그 회원의 refresh token을 모두 폐기한다. 실패해도 재발급이 탈퇴 회원을 거절하므로(#370) 로그만 남긴다 */
    private void revokeTokens(long withdrawalId, Long userId) {
        try {
            refreshTokenStore.revokeAll(userId);
        } catch (RuntimeException e) {
            log.warn("탈퇴한 회원의 refresh token을 폐기하지 못했습니다. withdrawalId={}", withdrawalId, e);
        }
    }

    /** [트랜잭션] 탈퇴 작업 상태를 바꾼다 */
    private void change(long withdrawalId, Consumer<Withdrawal> change) {
        transactionTemplate.executeWithoutResult(status -> change.accept(find(withdrawalId)));
    }

    private Withdrawal find(long withdrawalId) {
        return withdrawalRepository.findById(withdrawalId)
                .orElseThrow(() -> new IllegalStateException("탈퇴 작업이 없습니다. id=" + withdrawalId));
    }

    private static String error(String step, Exception e) {
        return step + ": " + e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    private static LocalDateTime now() {
        return LocalDateTime.now();
    }
}
