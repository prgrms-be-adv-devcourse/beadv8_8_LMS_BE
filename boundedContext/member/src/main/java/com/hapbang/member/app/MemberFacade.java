package com.hapbang.member.app;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.member.domain.ChannelPlatform;
import com.hapbang.member.domain.InfluencerChannel;
import com.hapbang.member.domain.MemberErrorCode;
import com.hapbang.member.domain.MemberException;
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
    private final MemberChannelApprovalUseCase memberChannelApprovalUseCase;
    private final MemberChannelSupport memberChannelSupport;
    private final MemberProfileImageUseCase memberProfileImageUseCase;

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
    public ChannelVerificationResult connectChzzkChannel(Long userId, String code, String state) {
        // 우리 DB로 판단할 수 있는 건 치지직 호출 전에 막는다 (저장 때 한 번 더 확인)
        memberSupport.findActiveUser(userId);
        ChzzkChannel chzzkChannel = memberChzzkUseCase.fetchChannel(code, state);
        return memberChannelRegisterUseCase.register(userId, ChannelPlatform.CHZZK, chzzkChannel);
    }

    /**
     * 채널 승인 요청: 내 채널 확인 → 치지직에서 팔로워 수 다시 조회 → 기준 이상이면 자동 승인, 아니면 심사 대기.
     * 연동과 같은 이유로 트랜잭션을 두지 않는다 (저장은 UseCase에서)
     */
    @PreAuthorize(USER_ONLY)
    public ChannelVerificationResult requestChannelApproval(Long userId, Long channelId) {
        // 우리 DB로 판단할 수 있는 건 치지직 호출 전에 막는다 (저장 때 한 번 더 확인)
        memberSupport.findActiveUser(userId);
        InfluencerChannel channel = memberChannelSupport.findMyChannel(userId, channelId);
        if (!channel.canRequestApproval()) {
            throw new MemberException(MemberErrorCode.CHANNEL_APPROVAL_NOT_ALLOWED);
        }
        int followerCount = memberChzzkUseCase.fetchFollowerCount(channel.getChannelIdentifier());
        return memberChannelApprovalUseCase.request(userId, channelId, followerCount);
    }

    /** 회원의 채널 전부 (내 채널 목록, 회원 프로필) */
    @Transactional(readOnly = true)
    public List<InfluencerChannel> findChannels(Long userId) {
        return memberChannelSupport.findChannels(userId);
    }

    /**
     * 프로필 사진 올리기: 본인·정상 회원·형식·크기·내용(매직 넘버) 검사 → S3 업로드 → key 저장.
     * 트랜잭션을 두지 않는다: 업로드하는 동안 DB 커넥션을 잡지 않도록 저장(UseCase)만 트랜잭션으로 한다
     */
    public User uploadProfileImage(Long userId, Long memberId, String contentType, long contentLength,
                                   InputStream body) throws IOException {
        if (!userId.equals(memberId)) {
            throw new MemberException(MemberErrorCode.PROFILE_IMAGE_FORBIDDEN);
        }
        // 우리 DB로 판단할 수 있는 건 업로드 전에 막는다 (저장 때 한 번 더 확인)
        memberSupport.findActiveUser(userId);
        String key = memberProfileImageUseCase.store(userId, contentType, contentLength, body);
        return memberProfileImageUseCase.change(userId, key);
    }

    /** 프로필 사진 연결 해제 (DB에서만, S3 파일은 그대로) */
    public User deleteProfileImage(Long userId) {
        return memberProfileImageUseCase.change(userId, null);
    }

    /** 프로필 사진 조회 URL (응답을 만들 때 쓴다). 사진이 없으면 null */
    public String profileImageUrl(String key) {
        return memberProfileImageUseCase.urlOf(key);
    }
}
