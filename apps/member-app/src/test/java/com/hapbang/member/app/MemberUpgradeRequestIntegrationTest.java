package com.hapbang.member.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;

import com.hapbang.member.MemberIntegrationTest;
import com.hapbang.member.domain.InfluencerUpgradeRequest;
import com.hapbang.member.domain.MemberErrorCode;
import com.hapbang.member.domain.MemberException;
import com.hapbang.member.domain.UpgradeRequestStatus;
import com.hapbang.member.domain.User;
import com.hapbang.member.fixture.MemberFixture;
import com.hapbang.member.out.InfluencerUpgradeRequestRepository;
import com.hapbang.member.out.UserRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;

/** 승급 신청: 실제 DB로 권한·대기 중 1건(앱 확인 + 부분 유니크 인덱스)·내 신청 내역을 확인한다 */
@MemberIntegrationTest
@WithMockUser(roles = "INFLUENCER")
class MemberUpgradeRequestIntegrationTest {

    @Autowired
    MemberFacade memberFacade;

    @Autowired
    UserRepository userRepository;

    @Autowired
    InfluencerUpgradeRequestRepository influencerUpgradeRequestRepository;

    @Test
    @DisplayName("인플루언서가 신청하면 심사 대기 1건이 저장된다")
    void requestUpgrade() {
        User influencer = userRepository.save(MemberFixture.member(Role.INFLUENCER));

        InfluencerUpgradeRequest request = memberFacade.requestUpgrade(influencer.getId(), "강의 경력 3년");

        InfluencerUpgradeRequest saved = influencerUpgradeRequestRepository.findById(request.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(UpgradeRequestStatus.PENDING);
        assertThat(saved.getRequestReason()).isEqualTo("강의 경력 3년");
        assertThat(saved.getRequestedAt()).isNotNull();
    }

    @Test
    @DisplayName("심사 대기 중인데 또 신청하면 409")
    void requestUpgrade_alreadyPending() {
        User influencer = userRepository.save(MemberFixture.member(Role.INFLUENCER));
        memberFacade.requestUpgrade(influencer.getId(), "첫 신청");

        assertThatThrownBy(() -> memberFacade.requestUpgrade(influencer.getId(), "또 신청"))
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.UPGRADE_REQUEST_ALREADY_PENDING);
        assertThat(influencerUpgradeRequestRepository.findByUserIdOrderByIdDesc(influencer.getId())).hasSize(1);
    }

    @Test
    @DisplayName("반려된 뒤에는 다시 신청할 수 있다")
    void requestUpgrade_afterReject() {
        User influencer = userRepository.save(MemberFixture.member(Role.INFLUENCER));
        InfluencerUpgradeRequest first = memberFacade.requestUpgrade(influencer.getId(), "첫 신청");
        InfluencerUpgradeRequest saved = influencerUpgradeRequestRepository.findById(first.getId()).orElseThrow();
        saved.reject(99L, "활동 내역 부족", LocalDateTime.now());
        influencerUpgradeRequestRepository.save(saved);

        memberFacade.requestUpgrade(influencer.getId(), "다시 신청");

        assertThat(influencerUpgradeRequestRepository.findByUserIdOrderByIdDesc(influencer.getId()))
                .extracting(InfluencerUpgradeRequest::getStatus)
                .containsExactly(UpgradeRequestStatus.PENDING, UpgradeRequestStatus.REJECTED);
    }

    @Test
    @WithMockUser(roles = "VERIFIED_INFLUENCER")
    @DisplayName("인플루언서가 아니면(인증된 인플루언서·유저) 신청이 거절된다")
    void requestUpgrade_notInfluencer() {
        User verified = userRepository.save(MemberFixture.member(Role.VERIFIED_INFLUENCER));

        assertThatThrownBy(() -> memberFacade.requestUpgrade(verified.getId(), "사유"))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(influencerUpgradeRequestRepository.count()).isZero();
    }

    @Test
    @DisplayName("탈퇴한 인플루언서는 신청할 수 없다 (404, 승인 요청과 같은 규칙)")
    void requestUpgrade_withdrawn() {
        User withdrawn = userRepository.save(MemberFixture.member(Role.INFLUENCER, UserStatus.WITHDRAWN));

        assertThatThrownBy(() -> memberFacade.requestUpgrade(withdrawn.getId(), "사유"))
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND);
    }

    @Test
    @DisplayName("DB 인덱스: 같은 회원의 심사 대기 신청 2건은 저장되지 않는다 (앱 확인을 건너뛰어도)")
    void onePendingIndex() {
        User influencer = userRepository.save(MemberFixture.member(Role.INFLUENCER));
        influencerUpgradeRequestRepository.saveAndFlush(
                InfluencerUpgradeRequest.request(influencer, "첫 신청", LocalDateTime.now()));

        assertThatThrownBy(() -> influencerUpgradeRequestRepository.saveAndFlush(
                InfluencerUpgradeRequest.request(influencer, "둘째 신청", LocalDateTime.now())))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(MemberUpgradeRequestUseCase.ONE_PENDING_INDEX);
    }

    @Test
    @DisplayName("같은 인플루언서가 동시에 두 번 신청하면 1건만 저장되고 다른 하나는 409")
    void requestUpgrade_concurrently() throws InterruptedException {
        Long userId = userRepository.save(MemberFixture.member(Role.INFLUENCER)).getId();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);

        for (int i = 0; i < 2; i++) {
            pool.submit(() -> runAsInfluencer(start, done, failures,
                    () -> memberFacade.requestUpgrade(userId, "동시 신청")));
        }
        start.countDown();

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();
        assertThat(failures).singleElement()
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.UPGRADE_REQUEST_ALREADY_PENDING);
        assertThat(influencerUpgradeRequestRepository.findByUserIdOrderByIdDesc(userId)).hasSize(1);
    }

    @Test
    @DisplayName("내 신청 내역은 최신순이고 다른 회원의 신청은 보이지 않는다")
    void findMyUpgradeRequests() {
        User me = userRepository.save(MemberFixture.member(Role.INFLUENCER));
        User other = userRepository.save(MemberFixture.member(Role.INFLUENCER));
        InfluencerUpgradeRequest first = influencerUpgradeRequestRepository.save(
                InfluencerUpgradeRequest.request(me, "첫 신청", LocalDateTime.now()));
        first.reject(99L, "사유", LocalDateTime.now());
        influencerUpgradeRequestRepository.save(first);
        InfluencerUpgradeRequest second = memberFacade.requestUpgrade(me.getId(), "다시 신청");
        memberFacade.requestUpgrade(other.getId(), "다른 회원");

        assertThat(memberFacade.findMyUpgradeRequests(me.getId()))
                .extracting(InfluencerUpgradeRequest::getId)
                .containsExactly(second.getId(), first.getId());
    }

    /** 새 스레드에는 @WithMockUser가 없어서 인플루언서 권한을 직접 넣는다 */
    private void runAsInfluencer(CountDownLatch start, CountDownLatch done, List<Throwable> failures, Runnable action) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "influencer", null, AuthorityUtils.createAuthorityList("ROLE_INFLUENCER")));
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
}
