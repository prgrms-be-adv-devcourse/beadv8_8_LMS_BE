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
import com.hapbang.member.domain.User;
import com.hapbang.member.global.storage.S3FileStorage;
import com.hapbang.member.out.InfluencerChannelRepository;
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
 *     <li>회원 한 명씩 처리하고, 한 명이 실패해도 다음 회원으로 넘어간다. 실패한 회원은 마스킹되지 않은 채라 다음 실행에서 다시 고른다.</li>
 *     <li>프로필 사진은 먼저 S3에서 빈 파일로 덮어 원본을 지운다({@link S3FileStorage#erase}). 실패하면 그 회원은 이번에 건너뛴다.
 *     덮은 뒤 DB 마스킹이 실패해도 다음에 다시 덮을 뿐이라 안전하다.</li>
 *     <li>DB는 한 트랜잭션에서 회원(이메일·닉네임·사진 연결), 구글 계정 연결, 비밀번호 정보, 채널을 마스킹하고
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
    private final MemberSupport memberSupport;
    private final S3FileStorage s3FileStorage;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;

    /**
     * 복구 기한이 지난 탈퇴 회원을 오래된 순으로 최대 {@code limit}명 마스킹한다
     *
     * @return 이번에 마스킹한 회원 수
     */
    public int maskDue(int limit) {
        LocalDateTime now = LocalDateTime.now();
        List<Long> targetIds = userRepository.findMaskingTargetIds(now.minus(User.RESTORE_PERIOD),
                now.minus(User.RESTORE_PERIOD).minus(User.RESTORE_GRACE), Limit.of(limit));
        int masked = 0;
        for (Long userId : targetIds) {
            try {
                if (mask(userId)) {
                    masked++;
                }
            } catch (RuntimeException e) {
                log.error("탈퇴 회원 마스킹에 실패했습니다. 다음 실행에서 다시 합니다. userId={}", userId, e);
            }
        }
        return masked;
    }

    /** 한 명 마스킹. 이미 마스킹했으면 false */
    boolean mask(Long userId) {
        String profileImageKey = transactionTemplate.execute(status -> memberSupport.findUser(userId).getProfileImageKey());
        if (profileImageKey != null) {
            s3FileStorage.erase(profileImageKey);
        }
        Instant maskedAt = Instant.now();
        LocalDateTime now = LocalDateTime.ofInstant(maskedAt, ZoneId.systemDefault());
        Boolean done = transactionTemplate.execute(status -> {
            User user = memberSupport.findUser(userId);
            if (user.isMasked()) {
                return false;
            }
            user.mask(now);
            oAuthAccountRepository.findByUserId(userId).forEach(OAuthAccount::mask);
            passwordCredentialRepository.findByUserId(userId).ifPresent(PasswordCredential::wipe);
            influencerChannelRepository.findByUserId(userId).forEach(channel -> channel.mask(now));
            memberSupport.flush();
            eventPublisher.publishEvent(new UserModifiedEvent(user.toDto()));
            eventPublisher.publishEvent(new UserMaskedEvent(userId, maskedAt));
            return true;
        });
        return Boolean.TRUE.equals(done);
    }
}
