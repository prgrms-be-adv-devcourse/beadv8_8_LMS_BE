package com.hapbang.member.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import com.hapbang.member.MemberIntegrationTest;
import com.hapbang.member.domain.ChannelPlatform;
import com.hapbang.member.domain.InfluencerChannel;
import com.hapbang.member.domain.OAuthAccount;
import com.hapbang.member.domain.OAuthProvider;
import com.hapbang.member.domain.PasswordCredential;
import com.hapbang.member.domain.User;
import com.hapbang.member.fixture.MemberFixture;
import com.hapbang.member.global.storage.S3FileStorage;
import com.hapbang.member.global.storage.StoredFile;
import com.hapbang.member.out.InfluencerChannelRepository;
import com.hapbang.member.out.OAuthAccountRepository;
import com.hapbang.member.out.PasswordCredentialRepository;
import com.hapbang.member.out.UserRepository;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.event.UserMaskedEvent;
import com.hapbang.shared.member.event.UserModifiedEvent;
import com.hapbang.testsupport.S3MockTestcontainersConfig;

/** 탈퇴 회원 마스킹 (#594): 실제 DB·S3Mock으로 대상 고르기, 파기 내용, 이벤트, 다시 가입을 확인한다 */
@MemberIntegrationTest
@RecordApplicationEvents
@Import(S3MockTestcontainersConfig.class)
class MemberMaskingIntegrationTest {

    @Autowired
    MemberMaskingUseCase memberMaskingUseCase;

    @Autowired
    UserRepository userRepository;

    @Autowired
    OAuthAccountRepository oAuthAccountRepository;

    @Autowired
    PasswordCredentialRepository passwordCredentialRepository;

    @Autowired
    InfluencerChannelRepository influencerChannelRepository;

    @Autowired
    S3FileStorage s3FileStorage;

    @Autowired
    ApplicationEvents events;

    @Test
    @DisplayName("복구 기한이 지난 탈퇴 회원만 고른다: 30일 안, 기한 안 복구 실패로 유예 중, 정상 회원은 건너뛴다")
    void selectsOnlyDue() {
        LocalDateTime now = LocalDateTime.now();
        User due = withdrawn(now.minusDays(31));
        User restorable = withdrawn(now.minusDays(29));
        User inGrace = withdrawn(now.minusDays(31), now.minusDays(2));
        User graceOver = withdrawn(now.minusDays(38), now.minusDays(9));
        User active = userRepository.save(MemberFixture.user());

        assertThat(memberMaskingUseCase.maskDue(100)).isEqualTo(2);

        assertThat(reload(due).isMasked()).isTrue();
        assertThat(reload(graceOver).isMasked()).isTrue();
        assertThat(reload(restorable).isMasked()).isFalse();
        assertThat(reload(inGrace).isMasked()).isFalse();
        assertThat(reload(active).isMasked()).isFalse();
        assertThat(memberMaskingUseCase.maskDue(100)).isZero();
    }

    @Test
    @DisplayName("이메일·닉네임·구글 계정·비밀번호·채널·사진을 파기하고, 회원 변경·마스킹 이벤트를 한 번씩 남긴다")
    void masksPersonalInformation() {
        User user = withdrawn(LocalDateTime.now().minusDays(31));
        Long id = user.getId();
        OAuthAccount account = oAuthAccountRepository.save(OAuthAccount.connect(user, OAuthProvider.GOOGLE, "google-sub-" + id));
        passwordCredentialRepository.save(PasswordCredential.register(user, "{bcrypt}hash", LocalDateTime.now()));
        InfluencerChannel channel = influencerChannelRepository.save(InfluencerChannel.register(user, ChannelPlatform.CHZZK,
                "channel-" + id, "방송인", 10, LocalDateTime.now()));
        String photoKey = "member/profile/" + id + "/photo";
        s3FileStorage.upload(photoKey, "image/png", 3, new ByteArrayInputStream(new byte[] {1, 2, 3}));
        User withPhoto = reload(user);
        withPhoto.changeProfileImage(photoKey);
        userRepository.save(withPhoto);
        events.clear();

        assertThat(memberMaskingUseCase.maskDue(100)).isEqualTo(1);

        User masked = reload(user);
        assertThat(masked.getEmail()).isEqualTo("withdrawn_" + id + "@masked.invalid");
        assertThat(masked.getNickname()).isEqualTo("탈퇴회원#" + id);
        assertThat(masked.getProfileImageKey()).isNull();
        assertThat(masked.getStatus()).isEqualTo(UserStatus.WITHDRAWN);
        assertThat(oAuthAccountRepository.findById(account.getId()).orElseThrow().getProviderUserId())
                .isEqualTo("withdrawn_" + account.getId());
        assertThat(passwordCredentialRepository.findByUserId(id).orElseThrow().getPasswordHash())
                .isEqualTo(PasswordCredential.WIPED_HASH);
        InfluencerChannel maskedChannel = influencerChannelRepository.findById(channel.getId()).orElseThrow();
        assertThat(maskedChannel.getChannelIdentifier()).isEqualTo("withdrawn_" + channel.getId());
        assertThat(maskedChannel.getChannelUrl()).isEqualTo("withdrawn_" + channel.getId());
        assertThat(maskedChannel.getDeletedAt()).isNotNull();
        assertThat(s3FileStorage.find(photoKey)).contains(new StoredFile(0, "application/octet-stream"));
        assertThat(events.stream(UserModifiedEvent.class)).singleElement()
                .satisfies(event -> assertThat(event.user().nickname()).isEqualTo("탈퇴회원#" + id));
        assertThat(events.stream(UserMaskedEvent.class)).singleElement()
                .satisfies(event -> assertThat(event.userId()).isEqualTo(id));
    }

    @Test
    @DisplayName("마스킹한 뒤에는 같은 이메일·같은 구글 계정·같은 채널로 새로 가입·등록할 수 있다")
    void allowsRejoin() {
        User user = withdrawn(LocalDateTime.now().minusDays(31));
        String email = user.getEmail();
        String nickname = user.getNickname();
        oAuthAccountRepository.save(OAuthAccount.connect(user, OAuthProvider.GOOGLE, "google-sub-rejoin"));
        influencerChannelRepository.save(InfluencerChannel.register(user, ChannelPlatform.CHZZK, "channel-rejoin", "방송인",
                10, LocalDateTime.now()));

        memberMaskingUseCase.maskDue(100);

        assertThat(userRepository.existsByEmailIgnoreCase(email)).isFalse();
        assertThat(userRepository.existsByNicknameLower(nickname)).isFalse();
        assertThat(oAuthAccountRepository.findByProviderAndProviderUserId(OAuthProvider.GOOGLE, "google-sub-rejoin")).isEmpty();
        assertThat(influencerChannelRepository.existsByPlatformAndChannelIdentifier(ChannelPlatform.CHZZK, "channel-rejoin"))
                .isFalse();
        User rejoined = userRepository.save(User.join(email, nickname));
        assertThat(rejoined.getId()).isNotEqualTo(user.getId());
    }

    private User withdrawn(LocalDateTime withdrawnAt) {
        return withdrawn(withdrawnAt, null);
    }

    /** 탈퇴 시각과, 있으면 기한 안 복구 실패 시각을 남긴 탈퇴 회원 */
    private User withdrawn(LocalDateTime withdrawnAt, LocalDateTime restoreFailedAt) {
        User user = MemberFixture.user();
        user.withdraw(withdrawnAt);
        if (restoreFailedAt != null) {
            user.recordRestoreFailure(restoreFailedAt);
        }
        return userRepository.save(user);
    }

    private User reload(User user) {
        return userRepository.findById(user.getId()).orElseThrow();
    }
}
