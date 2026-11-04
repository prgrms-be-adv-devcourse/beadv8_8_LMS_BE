package com.hapbang.member.app;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
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
 * <p>
 * 꼬인 작업 정리: 지갑 결과를 모르거나 처리 도중 앱이 꺼져 남은 작업은, 회원이 다시 탈퇴를 누를 때 그 자리에서 정리하고
 * ({@link #withdraw}), 다시 누르지 않고 떠난 회원은 배치가 정리한다({@link #recoverStuck}). 둘 다 지갑을 다시 열고 되돌림으로 끝낸다.
 * 아직 처리 중일 수 있는 작업({@link Withdrawal#STALE_AFTER} 안)은 건드리지 않고, 겹치면 낙관적 락이 한쪽만 반영되게 한다.
 * <p>
 * 기능 스위치({@value #ENABLED})가 켜졌을 때만 만든다. 켰는데 출연·강의·지갑 연결 코드가 없으면 앱이 시작하지 않는다
 */
@Slf4j
@Service
@ConditionalOnBooleanProperty(MemberWithdrawalUseCase.ENABLED)
public class MemberWithdrawalUseCase {

    /** 탈퇴 기능 스위치. 기본은 꺼짐 (지갑·출연·강의 구현이 끝나면 켠다) */
    public static final String ENABLED = "hapbang.member.withdrawal.enabled";
    /** 정리를 이만큼 실패하면 error 로그로 알린다 (그 뒤에도 배치는 계속 시도한다) */
    static final int ALERT_ATTEMPTS = 10;
    /** 탈퇴 전에 막힌 이유를 반드시 물어야 하는 앱 ({@link WithdrawalBlockSource#name()}) */
    static final Set<String> REQUIRED_BLOCK_SOURCES = Set.of("appearance", "lecture");

    private static final Set<WithdrawalStatus> IN_PROGRESS =
            EnumSet.of(WithdrawalStatus.CHECKING, WithdrawalStatus.WALLET_CLOSED, WithdrawalStatus.NEEDS_RECOVERY);
    private static final Set<WithdrawalStatus> RUNNING = EnumSet.of(WithdrawalStatus.CHECKING, WithdrawalStatus.WALLET_CLOSED);
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
        // 출연·강의 중 하나라도 빠지면(Client 주소 누락 등) 그 앱의 막힌 이유를 묻지 않고 탈퇴되므로 시작하지 않는다 (#654)
        Set<String> names = blockSources.stream().map(WithdrawalBlockSource::name).collect(Collectors.toSet());
        if (!names.containsAll(REQUIRED_BLOCK_SOURCES)) {
            throw new IllegalStateException("탈퇴 기능을 켰지만 막힌 이유를 알려 줄 앱 연결 코드가 모자랍니다. 필요: "
                    + REQUIRED_BLOCK_SOURCES + ", 있음: " + names);
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
     * 탈퇴한다. 이미 탈퇴한 회원이면 아무것도 하지 않는다(같은 요청을 다시 보내도 성공, 멱등)
     *
     * @throws WithdrawalBlockedException 남은 돈·거래가 있어 막힘 (409, 이유 모음)
     * @throws MemberException            {@code WITHDRAWAL_IN_PROGRESS}(409), {@code WITHDRAWAL_UNAVAILABLE}(503),
     *                                    정상 회원이 아니면 {@code MEMBER_NOT_FOUND}(404)
     */
    public void withdraw(Long userId) {
        Optional<Long> started = start(userId);
        if (started.isEmpty()) {
            return;
        }
        long withdrawalId = started.get();
        try {
            proceed(withdrawalId, userId);
        } catch (ObjectOptimisticLockingFailureException | IllegalStateException e) {
            // 그 사이 복구(다시 누를 때 정리·배치)가 이 작업을 먼저 바꿨다. 지갑은 복구 쪽이 다시 연다
            log.warn("탈퇴 작업을 다른 처리가 먼저 바꿨습니다. withdrawalId={}", withdrawalId, e);
            throw new MemberException(MemberErrorCode.WITHDRAWAL_UNAVAILABLE, e);
        }
    }

    /**
     * 탈퇴 회원 복구 (#593). 탈퇴 때 닫은 지갑을 먼저 다시 열고(같은 작업 ID라 몇 번 불러도 안전), 성공하면 회원을 정상으로 되돌리고
     * UserModifiedEvent(ACTIVE)로 다른 앱 복제본도 되돌린다. 지갑을 열지 못하면 복구하지 않는다(503, 회원·지갑 상태가 어긋나지 않게).
     * 지갑만 열리고 회원 복구가 실패해도 회원은 탈퇴 상태라 거래할 수 없고, 다시 시도하면 된다.
     * 이미 정상 회원이면 그대로 돌려준다(두 번 누름).
     * 지갑을 열지 못하면 실패를 남겨, 기한 안의 시도였으면 복구 기한과 마스킹을 {@link User#RESTORE_GRACE}만큼 미룬다 (#594).
     * 기한은 복구를 누른 시각({@code requestedAt})으로 판단한다. 기한 직전에 눌렀는데 지갑 처리에 몇 초 걸리는 사이 기한이 지나도
     * 복구(또는 유예)를 인정해, 지갑만 열리고 회원은 복구되지 않거나 장애인데 유예를 못 받는 일이 없게 한다
     *
     * @throws MemberException {@code RESTORE_UNAVAILABLE}(503), 복구할 수 없는 회원이면 {@code LOGIN_NOT_ALLOWED}(403)
     */
    public User restore(Long userId) {
        LocalDateTime requestedAt = now();
        User user = memberSupport.findUser(userId);
        if (user.isActive()) {
            return user;
        }
        if (!user.canRestore(requestedAt)) {
            throw new MemberException(MemberErrorCode.LOGIN_NOT_ALLOWED);
        }
        withdrawalRepository.findFirstByUserIdAndStatusOrderByIdDesc(userId, WithdrawalStatus.COMPLETED)
                .ifPresent(completed -> {
                    try {
                        walletGate.reopen(completed.getId(), userId);
                    } catch (RuntimeException e) {
                        log.warn("복구하려고 지갑을 다시 열지 못했습니다. withdrawalId={}", completed.getId(), e);
                        recordRestoreFailure(userId, requestedAt);
                        throw new MemberException(MemberErrorCode.RESTORE_UNAVAILABLE, e);
                    }
                });
        try {
            return transactionTemplate.execute(status -> {
                User target = memberSupport.findUser(userId);
                target.restore(requestedAt);
                memberSupport.flush();
                eventPublisher.publishEvent(new UserModifiedEvent(target.toDto()));
                return target;
            });
        } catch (IllegalStateException e) {
            // 지갑을 여는 사이 다른 처리(마스킹 등)로 복구할 수 없게 됐다
            throw new MemberException(MemberErrorCode.LOGIN_NOT_ALLOWED, e);
        }
    }

    /** 복구 실패(누른 시각)를 따로 커밋한다. 남기지 못해도 원래 오류(503)를 가리지 않게 로그만 남긴다 */
    private void recordRestoreFailure(Long userId, LocalDateTime requestedAt) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                memberSupport.findUser(userId).recordRestoreFailure(requestedAt);
                memberSupport.flush();
            });
        } catch (RuntimeException e) {
            log.error("복구 실패 시각을 남기지 못했습니다. 복구 유예가 적용되지 않을 수 있습니다. userId={}", userId, e);
        }
    }

    /**
     * 배치: 복구가 필요한 작업(다시 열기 실패, {@link Withdrawal#STALE_AFTER} 넘게 멈춤)을 오래된 순으로 최대 {@code limit}개 정리한다.
     * 작업마다 따로 처리하고, 하나가 실패해도 다음 작업으로 넘어간다
     *
     * @return 이번에 정리(지갑 다시 열기)한 작업 수
     */
    public int recoverStuck(int limit) {
        List<Long> targetIds = withdrawalRepository.findRecoveryTargetIds(RUNNING, now().minus(Withdrawal.STALE_AFTER),
                PageRequest.of(0, limit));
        int recovered = 0;
        for (Long withdrawalId : targetIds) {
            try {
                Long userId = transactionTemplate.execute(status -> find(withdrawalId).getUser().getId());
                RecoveryResult result = recover(withdrawalId, userId);
                if (result == RecoveryResult.RECOVERED) {
                    recovered++;
                } else if (result == RecoveryResult.FAILED) {
                    alertIfRepeated(withdrawalId);
                }
            } catch (RuntimeException e) {
                log.warn("탈퇴 작업 정리 중 예외가 났습니다. withdrawalId={}", withdrawalId, e);
            }
        }
        return recovered;
    }

    /**
     * 탈퇴 작업을 시작한다. 이미 탈퇴한 회원이면 빈 값(멱등).
     * 진행 중인 작업이 있으면 복구가 필요할 때(다시 열기 실패, 멈춤) 그 자리에서 정리하고 새로 시작하고, 아직 처리 중일 수 있으면 409
     */
    private Optional<Long> start(Long userId) {
        User user = memberSupport.findUser(userId);
        if (user.isWithdrawn()) {
            return Optional.empty();
        }
        if (!user.isActive()) {
            throw new MemberException(MemberErrorCode.MEMBER_NOT_FOUND);
        }
        withdrawalRepository.findFirstByUserIdAndStatusIn(userId, IN_PROGRESS)
                .ifPresent(inProgress -> {
                    switch (recover(inProgress.getId(), userId)) {
                        case RECOVERED, ALREADY_FINISHED -> {
                        }
                        case STILL_IN_PROGRESS -> throw new MemberException(MemberErrorCode.WITHDRAWAL_IN_PROGRESS);
                        case FAILED -> throw new MemberException(MemberErrorCode.WITHDRAWAL_UNAVAILABLE);
                    }
                });
        try {
            return Optional.of(transactionTemplate.execute(status -> {
                User active = memberSupport.findActiveUser(userId);
                return withdrawalRepository.saveAndFlush(Withdrawal.start(active)).getId();
            }));
        } catch (DataIntegrityViolationException e) {
            throw new MemberException(MemberErrorCode.WITHDRAWAL_IN_PROGRESS, e);
        }
    }

    /** 확인 → 지갑 → 판단 → 확정 → 토큰 폐기 */
    private void proceed(long withdrawalId, Long userId) {
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

    /** 막힘: 닫힌 지갑이 있으면 다시 열고(보상) 이유를 남긴 뒤 409. 다시 열기를 못 하면 복구에 맡기고 이유는 그대로 알려 준다 */
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
     * 꼬인 작업 하나를 정리한다: 복구가 필요한지 트랜잭션 안에서 다시 확인하고 복구 대상으로 표시한 뒤(낙관적 락으로 처리 중인 요청과 겹치지 않게)
     * 지갑을 다시 열고 되돌림으로 끝낸다
     */
    private RecoveryResult recover(long withdrawalId, Long userId) {
        RecoveryResult notRecoverable;
        try {
            notRecoverable = transactionTemplate.execute(status -> {
                Withdrawal withdrawal = find(withdrawalId);
                if (withdrawal.getStatus().isFinished()) {
                    return RecoveryResult.ALREADY_FINISHED;
                }
                if (!withdrawal.needsRecovery(now())) {
                    return RecoveryResult.STILL_IN_PROGRESS;
                }
                if (withdrawal.getStatus() != WithdrawalStatus.NEEDS_RECOVERY) {
                    withdrawal.needRecovery("처리하던 요청이 멈춰 정리합니다");
                }
                return null;
            });
        } catch (ObjectOptimisticLockingFailureException e) {
            // 그 사이 처리 중인 요청이나 다른 정리가 이 작업을 바꿨다
            return RecoveryResult.STILL_IN_PROGRESS;
        }
        if (notRecoverable != null) {
            return notRecoverable;
        }
        boolean reopened = reopenWallet(withdrawalId, userId,
                withdrawal -> withdrawal.rollBack("멈춘 작업 정리: 지갑 다시 열기 완료", now()));
        return reopened ? RecoveryResult.RECOVERED : RecoveryResult.FAILED;
    }

    /**
     * 지갑 다시 열기(보상). 성공하면 {@code afterReopen}으로 작업을 끝내고, 답을 못 받으면 NEEDS_RECOVERY로 남겨 복구가 이어서 연다.
     * 기록하는 사이 다른 처리가 먼저 작업을 끝냈으면(낙관적 락·상태 확인) 그쪽 결과를 둔다
     *
     * @return 다시 열었으면 true
     */
    private boolean reopenWallet(long withdrawalId, Long userId, Consumer<Withdrawal> afterReopen) {
        try {
            walletGate.reopen(withdrawalId, userId);
        } catch (RuntimeException e) {
            log.error("지갑을 다시 열지 못했습니다. 다시 누를 때나 배치가 다시 시도합니다. withdrawalId={}", withdrawalId, e);
            String error = error("지갑 다시 열기 실패", e);
            recordQuietly(withdrawalId, withdrawal -> {
                if (withdrawal.getStatus() == WithdrawalStatus.NEEDS_RECOVERY) {
                    withdrawal.recoveryFailed(error);
                } else {
                    withdrawal.needRecovery(error);
                }
            });
            return false;
        }
        recordQuietly(withdrawalId, afterReopen);
        return true;
    }

    /** 정리를 여러 번 실패한 작업은 error 로그로 알린다 (사람이 지갑 상태를 확인해야 할 수 있다) */
    private void alertIfRepeated(long withdrawalId) {
        int attempts = transactionTemplate.execute(status -> find(withdrawalId).getAttempts());
        if (attempts >= ALERT_ATTEMPTS) {
            log.error("탈퇴 작업 정리를 {}번 실패했습니다. 지갑 상태를 확인해 주세요. withdrawalId={}", attempts, withdrawalId);
        }
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

    /** 복구 결과를 기록한다. 그 사이 다른 처리가 작업을 바꿨으면 그쪽 결과를 두고 로그만 남긴다 */
    private void recordQuietly(long withdrawalId, Consumer<Withdrawal> change) {
        try {
            change(withdrawalId, change);
        } catch (ObjectOptimisticLockingFailureException | IllegalStateException e) {
            log.info("탈퇴 작업을 다른 처리가 먼저 바꿔 기록을 건너뜁니다. withdrawalId={}", withdrawalId, e);
        }
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

    /** 꼬인 작업 정리 결과 */
    private enum RecoveryResult {
        /** 지갑을 다시 열고 되돌림으로 끝냄 */
        RECOVERED,
        /** 이미 끝난 작업 (다른 처리가 먼저 끝냄) */
        ALREADY_FINISHED,
        /** 아직 처리 중일 수 있어 건드리지 않음 */
        STILL_IN_PROGRESS,
        /** 지갑을 다시 열지 못함 (다음에 다시 시도) */
        FAILED
    }
}
