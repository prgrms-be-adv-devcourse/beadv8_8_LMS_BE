package com.hapbang.member.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.hapbang.member.MemberIntegrationTest;
import com.hapbang.member.domain.ChannelPlatform;
import com.hapbang.member.domain.ChannelVerificationStatus;
import com.hapbang.member.domain.InfluencerChannel;
import com.hapbang.member.domain.MemberErrorCode;
import com.hapbang.member.domain.MemberException;
import com.hapbang.member.domain.User;
import com.hapbang.member.fixture.MemberFixture;
import com.hapbang.member.out.InfluencerChannelRepository;
import com.hapbang.member.out.UserRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.event.UserModifiedEvent;

/** 관리자 채널 심사: 실제 DB로 권한·역할 변경·이벤트·목록 쿼리·비관적 락을 확인한다 */
@MemberIntegrationTest
@RecordApplicationEvents
@WithMockUser(roles = "ADMIN")
class MemberChannelReviewIntegrationTest {

    private static final Long ADMIN_ID = 99L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 14, 10, 0);
    private static final AtomicInteger CHANNEL_SEQUENCE = new AtomicInteger();

    @Autowired
    MemberChannelReviewFacade memberChannelReviewFacade;

    @Autowired
    UserRepository userRepository;

    @Autowired
    InfluencerChannelRepository influencerChannelRepository;

    @Autowired
    ApplicationEvents events;

    /** member-app의 실제 JDBC 저장소를 spy로 감싸 커밋 뒤 세션 갱신 요청을 확인한다. */
    @MockitoSpyBean
    JdbcIndexedSessionRepository sessionRepository;

    @Test
    @DisplayName("심사 대기 채널을 승인하면 유저가 인플루언서가 되고 변경 이벤트가 한 번 발행된다")
    void approve() {
        User user = userRepository.save(MemberFixture.user());
        InfluencerChannel channel = pendingChannel(user, NOW);

        ChannelVerificationResult result = memberChannelReviewFacade.approve(ADMIN_ID, channel.getId());

        assertThat(result.status()).isEqualTo(ChannelVerificationStatus.APPROVED);
        assertThat(result.roleChanged()).isTrue();
        InfluencerChannel saved = influencerChannelRepository.findById(channel.getId()).orElseThrow();
        assertThat(saved.getVerificationStatus()).isEqualTo(ChannelVerificationStatus.APPROVED);
        assertThat(saved.getVerifiedBy()).isEqualTo(ADMIN_ID);
        assertThat(userRepository.findById(user.getId()).orElseThrow().getRole()).isEqualTo(Role.INFLUENCER);
        assertThat(events.stream(UserModifiedEvent.class))
                .singleElement()
                .satisfies(event -> assertThat(event.user().id()).isEqualTo(user.getId()));
    }

    @Test
    @DisplayName("이미 인플루언서인 회원의 채널은 채널만 승인되고 역할·이벤트는 그대로다")
    void approve_alreadyInfluencer() {
        User influencer = userRepository.save(MemberFixture.member(Role.INFLUENCER));
        InfluencerChannel channel = pendingChannel(influencer, NOW);

        ChannelVerificationResult result = memberChannelReviewFacade.approve(ADMIN_ID, channel.getId());

        assertThat(result.roleChanged()).isFalse();
        assertThat(influencerChannelRepository.findById(channel.getId()).orElseThrow().isApproved()).isTrue();
        assertThat(userRepository.findById(influencer.getId()).orElseThrow().getRole()).isEqualTo(Role.INFLUENCER);
        assertThat(events.stream(UserModifiedEvent.class)).isEmpty();
    }

    @Test
    @DisplayName("반려하면 사유가 저장되고 역할·이벤트는 그대로다")
    void reject() {
        User user = userRepository.save(MemberFixture.user());
        InfluencerChannel channel = pendingChannel(user, NOW);

        memberChannelReviewFacade.reject(ADMIN_ID, channel.getId(), "방송 시간 부족");

        InfluencerChannel saved = influencerChannelRepository.findById(channel.getId()).orElseThrow();
        assertThat(saved.getVerificationStatus()).isEqualTo(ChannelVerificationStatus.REJECTED);
        assertThat(saved.getRejectionReason()).isEqualTo("방송 시간 부족");
        assertThat(saved.getVerifiedBy()).isEqualTo(ADMIN_ID);
        assertThat(userRepository.findById(user.getId()).orElseThrow().getRole()).isEqualTo(Role.USER);
        assertThat(events.stream(UserModifiedEvent.class)).isEmpty();
    }

    @Test
    @DisplayName("탈퇴한 회원의 채널은 승인·반려할 수 없다 (409)")
    void review_ownerInactive() {
        User withdrawn = userRepository.save(MemberFixture.member(Role.USER, UserStatus.WITHDRAWN));
        InfluencerChannel channel = pendingChannel(withdrawn, NOW);

        assertThatThrownBy(() -> memberChannelReviewFacade.approve(ADMIN_ID, channel.getId()))
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.CHANNEL_OWNER_INACTIVE);
        assertThatThrownBy(() -> memberChannelReviewFacade.reject(ADMIN_ID, channel.getId(), "사유"))
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.CHANNEL_OWNER_INACTIVE);
        assertThat(influencerChannelRepository.findById(channel.getId()).orElseThrow().getVerificationStatus())
                .isEqualTo(ChannelVerificationStatus.PENDING);
    }

    @Test
    @DisplayName("없는 채널은 404")
    void review_channelNotFound() {
        assertThatThrownBy(() -> memberChannelReviewFacade.approve(ADMIN_ID, 999_999L))
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.CHANNEL_NOT_FOUND);
    }

    @Test
    @WithMockUser(roles = "INFLUENCER")
    @DisplayName("관리자가 아니면 목록·승인·반려 모두 거절된다")
    void notAdmin() {
        User user = userRepository.save(MemberFixture.user());
        InfluencerChannel channel = pendingChannel(user, NOW);

        assertThatThrownBy(() -> memberChannelReviewFacade.findChannels(ChannelVerificationStatus.PENDING,
                PageRequest.of(0, 20))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> memberChannelReviewFacade.approve(ADMIN_ID, channel.getId()))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> memberChannelReviewFacade.reject(ADMIN_ID, channel.getId(), "사유"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("심사 대기 목록은 오래된 신청부터, 탈퇴 회원·다른 상태 채널은 빠진다")
    void findChannels_pending() {
        InfluencerChannel newer = pendingChannel(userRepository.save(MemberFixture.user()), NOW.plusHours(1));
        InfluencerChannel older = pendingChannel(userRepository.save(MemberFixture.user()), NOW);
        pendingChannel(userRepository.save(MemberFixture.member(Role.USER, UserStatus.WITHDRAWN)), NOW.minusHours(1));
        InfluencerChannel rejected = pendingChannel(userRepository.save(MemberFixture.user()), NOW.minusHours(2));
        memberChannelReviewFacade.reject(ADMIN_ID, rejected.getId(), "사유");

        Page<InfluencerChannel> page = memberChannelReviewFacade.findChannels(ChannelVerificationStatus.PENDING,
                PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(InfluencerChannel::getId)
                .containsExactly(older.getId(), newer.getId());
        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("상태를 반려로 고르면 반려된 채널만 나온다")
    void findChannels_rejected() {
        pendingChannel(userRepository.save(MemberFixture.user()), NOW);
        InfluencerChannel rejected = pendingChannel(userRepository.save(MemberFixture.user()), NOW);
        memberChannelReviewFacade.reject(ADMIN_ID, rejected.getId(), "사유");

        Page<InfluencerChannel> page = memberChannelReviewFacade.findChannels(ChannelVerificationStatus.REJECTED,
                PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(InfluencerChannel::getId).containsExactly(rejected.getId());
    }

    @Test
    @DisplayName("두 관리자가 같은 채널을 동시에 승인·반려하면 하나만 처리되고 다른 하나는 409")
    void review_concurrently() throws InterruptedException {
        User user = userRepository.save(MemberFixture.user());
        Long channelId = pendingChannel(user, NOW).getId();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);

        pool.submit(() -> runAsAdmin(start, done, failures, () -> memberChannelReviewFacade.approve(1L, channelId)));
        pool.submit(() -> runAsAdmin(start, done, failures,
                () -> memberChannelReviewFacade.reject(2L, channelId, "사유")));
        start.countDown();

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();
        assertThat(failures).singleElement()
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.CHANNEL_REVIEW_NOT_ALLOWED);
        assertThat(influencerChannelRepository.findById(channelId).orElseThrow().getVerificationStatus())
                .isIn(ChannelVerificationStatus.APPROVED, ChannelVerificationStatus.REJECTED);
    }

    @Test
    @DisplayName("승인으로 역할이 바뀌면 커밋 뒤 그 회원의 세션을 찾아 갱신한다")
    void approve_updatesSession() {
        User user = userRepository.save(MemberFixture.user());
        InfluencerChannel channel = pendingChannel(user, NOW);

        memberChannelReviewFacade.approve(ADMIN_ID, channel.getId());

        verify(sessionRepository).findByPrincipalName(String.valueOf(user.getId()));
    }

    @Test
    @DisplayName("반려나 실패(롤백)면 세션을 건드리지 않는다")
    void reject_or_fail_doesNotUpdateSession() {
        User user = userRepository.save(MemberFixture.user());
        InfluencerChannel rejected = pendingChannel(user, NOW);
        InfluencerChannel registered = influencerChannelRepository.save(InfluencerChannel.register(user,
                ChannelPlatform.CHZZK, "channel-" + CHANNEL_SEQUENCE.incrementAndGet(), "테스트 채널", 5, NOW));

        memberChannelReviewFacade.reject(ADMIN_ID, rejected.getId(), "사유");
        assertThatThrownBy(() -> memberChannelReviewFacade.approve(ADMIN_ID, registered.getId()))
                .isInstanceOf(MemberException.class);

        verify(sessionRepository, never()).findByPrincipalName(any());
    }

    /** 새 스레드에는 @WithMockUser가 없어서 관리자 권한을 직접 넣는다 */
    private void runAsAdmin(CountDownLatch start, CountDownLatch done, List<Throwable> failures, Runnable action) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "admin", null, AuthorityUtils.createAuthorityList("ROLE_ADMIN")));
        try {
            start.await();
            action.run();
        } catch (Throwable e) {
            failures.add(e);
        } finally {
            SecurityContextHolder.clearContext();
            done.countDown();
        }
    }

    /** 팔로워 미달로 승인 신청해 심사 대기가 된 채널 (신청 시각 지정) */
    private InfluencerChannel pendingChannel(User user, LocalDateTime requestedAt) {
        InfluencerChannel channel = InfluencerChannel.register(user, ChannelPlatform.CHZZK,
                "channel-" + CHANNEL_SEQUENCE.incrementAndGet(), "테스트 채널", 5, requestedAt);
        channel.requestApproval(5, requestedAt);
        return influencerChannelRepository.save(channel);
    }
}
