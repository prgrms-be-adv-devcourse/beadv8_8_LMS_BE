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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.util.ReflectionTestUtils;

import com.hapbang.member.MemberIntegrationTest;
import com.hapbang.member.domain.ChannelPlatform;
import com.hapbang.member.domain.InfluencerChannel;
import com.hapbang.member.domain.InfluencerUpgradeRequest;
import com.hapbang.member.domain.MemberErrorCode;
import com.hapbang.member.domain.MemberException;
import com.hapbang.member.domain.UpgradeRequestStatus;
import com.hapbang.member.domain.User;
import com.hapbang.member.fixture.MemberFixture;
import com.hapbang.member.out.InfluencerChannelRepository;
import com.hapbang.member.out.InfluencerUpgradeRequestRepository;
import com.hapbang.member.out.UserRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.event.UserModifiedEvent;

/** 관리자 승급 심사: 실제 DB로 권한·역할 변경·이벤트·세션 갱신 호출·목록(승인된 채널)·비관적 락을 확인한다 */
@MemberIntegrationTest
@RecordApplicationEvents
@WithMockUser(roles = "ADMIN")
class MemberUpgradeReviewIntegrationTest {

    private static final Long ADMIN_ID = 99L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 15, 10, 0);
    private static final AtomicInteger CHANNEL_SEQUENCE = new AtomicInteger();

    @Autowired
    MemberUpgradeReviewFacade memberUpgradeReviewFacade;

    @Autowired
    UserRepository userRepository;

    @Autowired
    InfluencerUpgradeRequestRepository influencerUpgradeRequestRepository;

    @Autowired
    InfluencerChannelRepository influencerChannelRepository;

    @Autowired
    ApplicationEvents events;

    /** member-app의 실제 JDBC 저장소를 spy로 감싸 커밋 뒤 세션 갱신 요청을 확인한다. */
    @MockitoSpyBean
    JdbcIndexedSessionRepository sessionRepository;

    @Test
    @DisplayName("승인하면 인증된 인플루언서가 되고 변경 이벤트 1번, 커밋 뒤 그 회원의 세션 갱신을 부른다")
    void approve() {
        User influencer = userRepository.save(MemberFixture.member(Role.INFLUENCER));
        InfluencerUpgradeRequest request = pendingRequest(influencer, NOW);

        memberUpgradeReviewFacade.approve(ADMIN_ID, request.getId());

        InfluencerUpgradeRequest saved = influencerUpgradeRequestRepository.findById(request.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(UpgradeRequestStatus.APPROVED);
        assertThat(saved.getReviewedBy()).isEqualTo(ADMIN_ID);
        assertThat(userRepository.findById(influencer.getId()).orElseThrow().getRole())
                .isEqualTo(Role.VERIFIED_INFLUENCER);
        assertThat(events.stream(UserModifiedEvent.class))
                .singleElement()
                .satisfies(event -> assertThat(event.user().role()).isEqualTo(Role.VERIFIED_INFLUENCER));
        verify(sessionRepository).findByPrincipalName(String.valueOf(influencer.getId()));
    }

    @Test
    @DisplayName("반려하면 사유가 저장되고 역할·이벤트·세션은 그대로다")
    void reject() {
        User influencer = userRepository.save(MemberFixture.member(Role.INFLUENCER));
        InfluencerUpgradeRequest request = pendingRequest(influencer, NOW);

        memberUpgradeReviewFacade.reject(ADMIN_ID, request.getId(), "활동 내역 부족");

        InfluencerUpgradeRequest saved = influencerUpgradeRequestRepository.findById(request.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(UpgradeRequestStatus.REJECTED);
        assertThat(saved.getRejectionReason()).isEqualTo("활동 내역 부족");
        assertThat(userRepository.findById(influencer.getId()).orElseThrow().getRole()).isEqualTo(Role.INFLUENCER);
        assertThat(events.stream(UserModifiedEvent.class)).isEmpty();
        verify(sessionRepository, never()).findByPrincipalName(any());
    }

    @Test
    @DisplayName("탈퇴한 회원의 신청은 승인·반려할 수 없다 (409)")
    void review_memberInactive() {
        User influencer = userRepository.save(MemberFixture.member(Role.INFLUENCER));
        InfluencerUpgradeRequest request = pendingRequest(influencer, NOW);
        ReflectionTestUtils.setField(influencer, "status", UserStatus.WITHDRAWN);
        userRepository.save(influencer);

        assertThatThrownBy(() -> memberUpgradeReviewFacade.approve(ADMIN_ID, request.getId()))
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.UPGRADE_REQUEST_MEMBER_INACTIVE);
        assertThatThrownBy(() -> memberUpgradeReviewFacade.reject(ADMIN_ID, request.getId(), "사유"))
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.UPGRADE_REQUEST_MEMBER_INACTIVE);
    }

    @Test
    @DisplayName("승인하려는데 회원이 더는 인플루언서가 아니면 409, 신청은 심사 대기 그대로")
    void approve_notInfluencer() {
        User influencer = userRepository.save(MemberFixture.member(Role.INFLUENCER));
        InfluencerUpgradeRequest request = pendingRequest(influencer, NOW);
        influencer.changeRole(Role.USER);
        userRepository.save(influencer);

        assertThatThrownBy(() -> memberUpgradeReviewFacade.approve(ADMIN_ID, request.getId()))
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.UPGRADE_MEMBER_NOT_INFLUENCER);
        assertThat(influencerUpgradeRequestRepository.findById(request.getId()).orElseThrow().isPending()).isTrue();
    }

    @Test
    @DisplayName("없는 신청은 404, 이미 처리한 신청은 409")
    void review_notFoundOrProcessed() {
        InfluencerUpgradeRequest request = pendingRequest(userRepository.save(MemberFixture.member(Role.INFLUENCER)), NOW);
        memberUpgradeReviewFacade.reject(ADMIN_ID, request.getId(), "사유");

        assertThatThrownBy(() -> memberUpgradeReviewFacade.approve(ADMIN_ID, 999_999L))
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.UPGRADE_REQUEST_NOT_FOUND);
        assertThatThrownBy(() -> memberUpgradeReviewFacade.approve(ADMIN_ID, request.getId()))
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.UPGRADE_REQUEST_REVIEW_NOT_ALLOWED);
    }

    @Test
    @WithMockUser(roles = "INFLUENCER")
    @DisplayName("관리자가 아니면 목록·승인·반려 모두 거절된다")
    void notAdmin() {
        InfluencerUpgradeRequest request = pendingRequest(userRepository.save(MemberFixture.member(Role.INFLUENCER)), NOW);

        assertThatThrownBy(() -> memberUpgradeReviewFacade.findRequests(UpgradeRequestStatus.PENDING, PageRequest.of(0, 20)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> memberUpgradeReviewFacade.approve(ADMIN_ID, request.getId()))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> memberUpgradeReviewFacade.reject(ADMIN_ID, request.getId(), "사유"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("심사 목록: 오래된 신청부터, 탈퇴 회원 제외, 회원마다 승인된 채널만 붙는다")
    void findRequests() {
        User older = userRepository.save(MemberFixture.member(Role.INFLUENCER));
        User newer = userRepository.save(MemberFixture.member(Role.INFLUENCER));
        User withdrawn = userRepository.save(MemberFixture.member(Role.INFLUENCER));
        InfluencerChannel approved = channel(older, 20);
        channel(older, 5);  // 등록만 된 채널 (목록에 안 나옴)
        InfluencerUpgradeRequest olderRequest = pendingRequest(older, NOW);
        InfluencerUpgradeRequest newerRequest = pendingRequest(newer, NOW.plusHours(1));
        pendingRequest(withdrawn, NOW.minusHours(1));
        ReflectionTestUtils.setField(withdrawn, "status", UserStatus.WITHDRAWN);
        userRepository.save(withdrawn);

        Page<UpgradeRequestReview> page = memberUpgradeReviewFacade.findRequests(UpgradeRequestStatus.PENDING,
                PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(review -> review.request().getId())
                .containsExactly(olderRequest.getId(), newerRequest.getId());
        assertThat(page.getContent().get(0).approvedChannels()).extracting(InfluencerChannel::getId)
                .containsExactly(approved.getId());
        assertThat(page.getContent().get(1).approvedChannels()).isEmpty();
        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("두 관리자가 같은 신청을 동시에 승인·반려하면 하나만 처리되고 다른 하나는 409")
    void review_concurrently() throws InterruptedException {
        Long requestId = pendingRequest(userRepository.save(MemberFixture.member(Role.INFLUENCER)), NOW).getId();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);

        pool.submit(() -> runAsAdmin(start, done, failures, () -> memberUpgradeReviewFacade.approve(1L, requestId)));
        pool.submit(() -> runAsAdmin(start, done, failures,
                () -> memberUpgradeReviewFacade.reject(2L, requestId, "사유")));
        start.countDown();

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();
        assertThat(failures).singleElement()
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.UPGRADE_REQUEST_REVIEW_NOT_ALLOWED);
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

    private InfluencerUpgradeRequest pendingRequest(User user, LocalDateTime requestedAt) {
        return influencerUpgradeRequestRepository.save(InfluencerUpgradeRequest.request(user, "강의 경력 3년", requestedAt));
    }

    /** 팔로워 10명 이상이면 바로 승인, 미만이면 등록만 (F4 규칙) */
    private InfluencerChannel channel(User user, int followerCount) {
        return influencerChannelRepository.save(InfluencerChannel.register(user, ChannelPlatform.CHZZK,
                "upgrade-channel-" + CHANNEL_SEQUENCE.incrementAndGet(), "테스트 채널", followerCount, NOW));
    }
}
