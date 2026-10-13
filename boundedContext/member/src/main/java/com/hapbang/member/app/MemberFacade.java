package com.hapbang.member.app;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.member.domain.ChannelPlatform;
import com.hapbang.member.domain.OAuthProvider;
import com.hapbang.member.domain.User;
import com.hapbang.shared.member.domain.Role;

import lombok.RequiredArgsConstructor;

/** 회원 모듈 진입점 */
@Service
@RequiredArgsConstructor
public class MemberFacade {

    private final MemberOAuthLoginUseCase memberOAuthLoginUseCase;
    private final MemberTestAccountUseCase memberTestAccountUseCase;
    private final MemberUpdateProfileUseCase memberUpdateProfileUseCase;
    private final MemberSupport memberSupport;
    private final MemberChzzkUseCase memberChzzkUseCase;
    private final MemberChannelRegisterUseCase memberChannelRegisterUseCase;

    // 채널 인증은 유저만 (AGENTS.md 6장 권한표)
    private static final String USER_ONLY = "hasRole('USER')";
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    /** OAuth 로그인 (처음이면 가입) */
    @Transactional
    public User oauthLogin(OAuthProvider provider, String providerUserId, String email, String nickname) {
        return memberOAuthLoginUseCase.login(provider, providerUserId, email, nickname);
    }

    /** 역할별 테스트 계정 만들기 (이미 있으면 건너뜀) */
    @Transactional
    public void createTestAccounts() {
        memberTestAccountUseCase.createAll();
    }

    /** 역할·번호로 테스트 계정 조회 */
    @Transactional(readOnly = true)
    public Optional<User> findTestAccount(Role role, int number) {
        return memberTestAccountUseCase.find(role, number);
    }

    /** 내 정보 수정 */
    @Transactional
    public User updateProfile(Long userId, String nickname) {
        return memberUpdateProfileUseCase.update(userId, nickname);
    }

    /** 회원 조회 */
    @Transactional(readOnly = true)
    public User findById(Long userId) {
        return memberSupport.findUser(userId);
    }

    /** 인플루언서 목록 (최신 가입순). 정렬은 요청과 상관없이 고정 */
    @Transactional(readOnly = true)
    public Page<User> findInfluencers(Pageable pageable) {
        return memberSupport.findInfluencers(
                PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), NEWEST_FIRST));
    }

    /** 회원 프로필 (정상 회원만) */
    @Transactional(readOnly = true)
    public User findProfile(Long memberId) {
        return memberSupport.findActiveUser(memberId);
    }

    /** 치지직 연동 화면 주소 */
    @PreAuthorize(USER_ONLY)
    public String chzzkAuthorizeUrl(String state) {
        return memberChzzkUseCase.authorizeUrl(state);
    }

    /**
     * 치지직 채널 연동: 치지직에서 본인 채널 확인 → 채널 등록 (팔로워 10명 이상이면 자동 승인).
     * 트랜잭션을 두지 않는다: 치지직을 기다리는 동안 DB 커넥션을 잡지 않도록 저장(UseCase)만 트랜잭션으로 한다
     */
    @PreAuthorize(USER_ONLY)
    public ChannelConnectResult connectChzzkChannel(Long userId, String code, String state) {
        ChzzkChannel chzzkChannel = memberChzzkUseCase.fetchChannel(code, state);
        return memberChannelRegisterUseCase.register(userId, ChannelPlatform.CHZZK, chzzkChannel);
    }
}
