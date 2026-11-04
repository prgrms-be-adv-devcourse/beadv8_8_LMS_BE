package com.hapbang.member.app;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.member.domain.OAuthAccount;
import com.hapbang.member.domain.PasswordCredential;
import com.hapbang.member.domain.ProfileImage;
import com.hapbang.member.domain.User;
import com.hapbang.member.global.storage.S3FileStorage;
import com.hapbang.member.out.InfluencerChannelRepository;
import com.hapbang.member.out.NicknameDedupRepository;
import com.hapbang.member.out.OAuthAccountRepository;
import com.hapbang.member.out.PasswordCredentialRepository;
import com.hapbang.member.out.UserRepository;
import com.hapbang.shared.member.event.UserMaskedEvent;
import com.hapbang.shared.member.event.UserModifiedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 탈퇴 회원 개인정보 마스킹 (#594, docs/policy.md 삭제 정책). 복구 기한({@link User#restorableUntil()})이 지난 탈퇴 회원의
 * 식별 정보를 파기한다. 회원 행·ID·탈퇴 상태와 돈 기록은 남긴다.
 * <ul>
 *     <li>후보는 "탈퇴하고 30일 지남"으로 넓게 고르고, 마스킹해도 되는지는 {@link User#canRestore}(복구 유예 포함) 한 곳에서 정한다.
 *     유예 중인 회원은 건너뛰고 다음 날 다시 본다.</li>
 *     <li>회원 한 명씩 처리하고 ID 순으로 이어 읽는다. 한 명이 실패해도 그 회원만 건너뛰고 끝까지 처리한다. 실패한 회원은 마스킹되지
 *     않은 채라 다음 실행에서 다시 고른다.</li>
 *     <li>먼저 마스킹 대상인지 확인한 뒤에만 프로필 사진 폴더({@link ProfileImage#folderOf})의 파일을 모두 빈 파일로 덮는다
 *     (지금 사진, 예전에 바꾼 사진, 내린 사진 모두). 덮은 뒤 DB 마스킹이 실패해도 다음에 다시 덮을 뿐이라 안전하다.</li>
 *     <li>DB는 한 트랜잭션에서 회원(이메일·닉네임·사진 연결), 구글 계정 연결, 비밀번호 정보, 채널, 닉네임 정리 기록(#646)을 마스킹하고
 *     {@link UserModifiedEvent}(바뀐 닉네임)와 {@link UserMaskedEvent}(다른 앱이 자기 개인정보를 지우는 신호)를 아웃박스로 남긴다.</li>
 *     <li>같은 회원을 복구와 동시에 처리하면 회원 버전(@Version)이 겹쳐 늦은 쪽이 실패한다. 마스킹 대상은 복구 기한이 지난 회원뿐이라
 *     실제로는 기한 경계에서만 생긴다.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberMaskingUseCase {

    private final UserRepository userRepository;
    private final OAuthAccountRepository oAuthAccountRepository;
    private final PasswordCredentialRepository passwordCredentialRepository;
    private final InfluencerChannelRepository influencerChannelRepository;
    private final NicknameDedupRepository nicknameDedupRepository;
    private final MemberSupport memberSupport;
    private final S3FileStorage s3FileStorage;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;

    /**
     * 복구 기한이 지난 탈퇴 회원을 모두 마스킹한다. 후보를 {@code pageSize}명씩 ID 순으로 이어 읽는다
     *
     * @return 이번에 마스킹한 회원 수
     */
    public int maskAllDue(int pageSize) {
        LocalDateTime deadlineCutoff = LocalDateTime.now().minus(User.RESTORE_PERIOD);
        int masked = 0;
        long afterId = 0;
        while (true) {
            List<Long> candidateIds = userRepository.findMaskingCandidateIds(deadlineCutoff, afterId, Limit.of(pageSize));
            if (candidateIds.isEmpty()) {
                return masked;
            }
            for (Long userId : candidateIds) {
                try {
                    if (mask(userId)) {
                        masked++;
                    }
                } catch (RuntimeException e) {
                    log.error("탈퇴 회원 마스킹에 실패했습니다. 다음 실행에서 다시 합니다. userId={}", userId, e);
                }
            }
            afterId = candidateIds.getLast();
        }
    }

    /** 한 명 마스킹. 마스킹 대상이 아니면(복구 유예 중, 이미 마스킹함 등) 아무것도 바꾸지 않고 false */
    boolean mask(Long userId) {
        Boolean due = transactionTemplate.execute(status -> isDue(memberSupport.findUser(userId), LocalDateTime.now()));
        if (!Boolean.TRUE.equals(due)) {
            return false;
        }
        for (String key : s3FileStorage.listKeys(ProfileImage.folderOf(userId))) {
            s3FileStorage.erase(key);
        }
        Instant maskedAt = Instant.now();
        LocalDateTime now = LocalDateTime.ofInstant(maskedAt, ZoneId.systemDefault());
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            User user = memberSupport.findUser(userId);
            if (!isDue(user, now)) {
                return false;
            }
            user.mask(now);
            oAuthAccountRepository.findByUserId(userId).forEach(OAuthAccount::mask);
            passwordCredentialRepository.findByUserId(userId).ifPresent(PasswordCredential::wipe);
            influencerChannelRepository.findByUserId(userId).forEach(channel -> channel.mask(now));
            nicknameDedupRepository.findById(userId).ifPresent(dedup -> dedup.mask(user.getNickname()));
            memberSupport.flush();
            eventPublisher.publishEvent(new UserModifiedEvent(user.toDto()));
            eventPublisher.publishEvent(new UserMaskedEvent(userId, maskedAt));
            return true;
        }));
    }

    /** 마스킹할 때가 됐는지: 탈퇴했고, 아직 마스킹하지 않았고, 복구 기한(유예 포함)이 지났다 */
    private static boolean isDue(User user, LocalDateTime now) {
        return user.isWithdrawn() && !user.isMasked() && !user.canRestore(now);
    }
}
