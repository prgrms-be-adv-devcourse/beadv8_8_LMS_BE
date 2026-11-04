package com.hapbang.member.app;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.member.domain.AppearanceRatingSummary;
import com.hapbang.member.domain.ChannelPlatform;
import com.hapbang.member.domain.EmailVerificationPurpose;
import com.hapbang.member.domain.InfluencerChannel;
import com.hapbang.member.domain.InfluencerUpgradeRequest;
import com.hapbang.member.domain.MemberErrorCode;
import com.hapbang.member.domain.MemberException;
import com.hapbang.member.domain.Nickname;
import com.hapbang.member.domain.OAuthProvider;
import com.hapbang.member.domain.User;
import com.hapbang.member.global.exception.BusinessException;
import com.hapbang.member.global.exception.GlobalErrorCode;
import com.hapbang.member.out.RestoreTicketStore;
import com.hapbang.shared.member.domain.Role;

import lombok.RequiredArgsConstructor;

/** 회원 모듈 진입점 */
@Service
@RequiredArgsConstructor
public class MemberFacade {

    private final MemberOAuthLoginUseCase memberOAuthLoginUseCase;
    private final MemberSeedAccountUseCase memberSeedAccountUseCase;
    private final MemberNicknameDedupNotifyUseCase memberNicknameDedupNotifyUseCase;
    private final MemberUpdateProfileUseCase memberUpdateProfileUseCase;
    private final MemberSupport memberSupport;
    private final MemberChzzkUseCase memberChzzkUseCase;
    private final MemberChannelRegisterUseCase memberChannelRegisterUseCase;
    private final MemberChannelApprovalUseCase memberChannelApprovalUseCase;
    private final MemberChannelSupport memberChannelSupport;
    private final MemberProfileImageUseCase memberProfileImageUseCase;
    private final MemberUpgradeRequestUseCase memberUpgradeRequestUseCase;
    private final MemberFollowerRefreshUseCase memberFollowerRefreshUseCase;
    private final MemberTokenUseCase memberTokenUseCase;
    private final MemberSignUpUseCase memberSignUpUseCase;
    private final MemberEmailVerificationUseCase memberEmailVerificationUseCase;
    private final MemberPasswordResetUseCase memberPasswordResetUseCase;
    private final MemberPasswordLoginUseCase memberPasswordLoginUseCase;
    private final MemberPasswordChangeUseCase memberPasswordChangeUseCase;
    private final MemberAppearanceRatingUseCase memberAppearanceRatingUseCase;
    private final MemberAppearanceReviewUseCase memberAppearanceReviewUseCase;
    /** 탈퇴 기능 스위치가 켜졌을 때만 있다 (#593) */
    private final ObjectProvider<MemberWithdrawalUseCase> memberWithdrawalUseCase;
    private final RestoreTicketStore restoreTicketStore;

    // 채널 인증은 유저만 (AGENTS.md 6장 권한표)
    private static final String USER_ONLY = "hasRole('USER')";
    // 승급 신청은 인플루언서만 (인증된 인플루언서·유저·관리자 불가)
    private static final String INFLUENCER_ONLY = "hasRole('INFLUENCER')";
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    /** OAuth 로그인 (처음이면 가입) */
    @Transactional
    public User oauthLogin(OAuthProvider provider, String providerUserId, String email, String nickname) {
        return memberOAuthLoginUseCase.login(provider, providerUserId, email, nickname);
    }

    /** 채널 팔로워 수 일일 갱신 (배치용, 묶음마다 트랜잭션을 UseCase가 직접 연다) */
    public FollowerRefreshResult refreshFollowerCounts() {
        return memberFollowerRefreshUseCase.refreshAll();
    }

    /** 역할별 초기 계정 만들기 (#520, 이미 있으면 건너뜀) */
    @Transactional
    public void createSeedAccounts() {
        memberSeedAccountUseCase.createAll();
    }

    /** 마이그레이션이 바꾼 중복 닉네임을 다른 앱에 알리기 (#646, 앱 시작 시 한 번) */
    @Transactional
    public int notifyNicknameDedup() {
        return memberNicknameDedupNotifyUseCase.notifyPending();
    }

    /**
     * 닉네임 중복 확인 (#646). 규칙에 맞지 않으면 닉네임 규칙 오류(400). 대소문자를 무시하고 탈퇴 회원 닉네임도 쓸 수 없다.
     * 로그인한 회원(userId)이 자기 닉네임을 넣으면 쓸 수 있다고 답한다
     */
    @Transactional(readOnly = true)
    public boolean isNicknameAvailable(String nickname, Long userId) {
        return memberSupport.isNicknameAvailable(Nickname.validate(nickname), userId);
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

    /** 회원 출연 평점 (프로필, #555). 받은 리뷰가 없으면 모두 비어 있다 */
    @Transactional(readOnly = true)
    public AppearanceRatingSummary findAppearanceRating(Long userId) {
        return memberAppearanceRatingUseCase.find(userId);
    }

    /**
     * 회원이 받은 출연 리뷰 목록 (프로필, #555). 프로필과 같이 정상 회원만 본다. 정렬은 요청과 상관없이 최신 공개순.
     * 출연 내부 API를 부르므로 트랜잭션을 두지 않는다
     */
    public Page<ReceivedAppearanceReview> findReceivedAppearanceReviews(Long memberId, Pageable pageable) {
        memberSupport.findActiveUser(memberId);
        return memberAppearanceReviewUseCase.findReceived(memberId, pageable.getPageNumber(), pageable.getPageSize());
    }

    /** 여러 회원의 출연 평점 (인플루언서 목록, #555). 받은 리뷰가 없는 회원은 결과에 없다 */
    @Transactional(readOnly = true)
    public Map<Long, AppearanceRatingSummary> findAppearanceRatings(Collection<Long> userIds) {
        return memberAppearanceRatingUseCase.findAll(userIds);
    }

    /** 치지직 연동 시작 (#370): 연동하는 회원과 state를 맡기고 치지직 화면 주소를 준다 */
    @PreAuthorize(USER_ONLY)
    public ChzzkAuthorization startChzzkConnect(Long userId) {
        return memberChzzkUseCase.start(userId);
    }

    /**
     * 치지직 연동 콜백 (#370). 브라우저 이동이라 로그인 정보가 없으므로, 시작할 때 맡긴 회원으로 연동한다.
     * 시작 뒤 역할이 바뀌었을 수 있어 유저인지 DB로 다시 확인한다 (시작 때는 @PreAuthorize로 확인).
     * 트랜잭션을 두지 않는다: 치지직을 기다리는 동안 DB 커넥션을 잡지 않도록 저장(UseCase)만 트랜잭션으로 한다
     */
    public ChzzkConnection completeChzzkConnect(String browserId, String code, String state) {
        Long userId = memberChzzkUseCase.takeUserId(browserId, state);
        if (code == null) {
            // 치지직 동의를 취소하는 등 code 없이 돌아온 경우
            throw new MemberException(MemberErrorCode.CHZZK_REQUEST_REJECTED);
        }
        if (memberSupport.findActiveUser(userId).getRole() != Role.USER) {
            throw new MemberException(MemberErrorCode.CHANNEL_CONNECT_NOT_ALLOWED);
        }
        ChzzkChannel chzzkChannel = memberChzzkUseCase.fetchChannel(code, state);
        return new ChzzkConnection(userId,
                memberChannelRegisterUseCase.register(userId, ChannelPlatform.CHZZK, chzzkChannel));
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

    /** 승급 신청 (인플루언서만, 심사 대기 중이면 409) */
    @PreAuthorize(INFLUENCER_ONLY)
    @Transactional
    public InfluencerUpgradeRequest requestUpgrade(Long userId, String reason) {
        return memberUpgradeRequestUseCase.request(userId, reason);
    }

    /** 내 승급 신청 내역 (최신순) */
    @Transactional(readOnly = true)
    public List<InfluencerUpgradeRequest> findMyUpgradeRequests(Long userId) {
        return memberUpgradeRequestUseCase.findMine(userId);
    }

    /** 회원의 채널 전부 (내 채널 목록, 회원 프로필) */
    @Transactional(readOnly = true)
    public List<InfluencerChannel> findChannels(Long userId) {
        return memberChannelSupport.findChannels(userId);
    }

    /**
     * 프로필 사진 올리기: 본인·정상 회원·형식·크기·내용(매직 넘버) 검사 → S3 업로드 → key 저장 → 옛 사진 파일 비우기(#594).
     * 트랜잭션을 두지 않는다: 업로드하는 동안 DB 커넥션을 잡지 않도록 저장(UseCase)만 트랜잭션으로 한다
     */
    public User uploadProfileImage(Long userId, Long memberId, String contentType, long contentLength,
                                   InputStream body) throws IOException {
        if (!userId.equals(memberId)) {
            throw new MemberException(MemberErrorCode.PROFILE_IMAGE_FORBIDDEN);
        }
        // 우리 DB로 판단할 수 있는 건 업로드 전에 막는다 (저장 때 한 번 더 확인)
        String previousKey = memberSupport.findActiveUser(userId).getProfileImageKey();
        String key = memberProfileImageUseCase.store(userId, contentType, contentLength, body);
        User user = memberProfileImageUseCase.change(userId, key);
        memberProfileImageUseCase.eraseReplaced(previousKey, key);
        return user;
    }

    /** 프로필 사진 내리기: 연결을 끊고, 커밋 뒤 S3 파일을 빈 파일로 덮어 지운다 (#594) */
    public User deleteProfileImage(Long userId) {
        String previousKey = memberSupport.findActiveUser(userId).getProfileImageKey();
        User user = memberProfileImageUseCase.change(userId, null);
        memberProfileImageUseCase.eraseReplaced(previousKey, null);
        return user;
    }

    /** 프로필 사진 조회 URL (응답을 만들 때 쓴다). 사진이 없으면 null */
    public String profileImageUrl(String key) {
        return memberProfileImageUseCase.urlOf(key);
    }

    /**
     * 회원 탈퇴 (#593). 탈퇴 기능 스위치가 꺼져 있으면 없는 기능처럼 404.
     * 다른 앱(지갑·출연·강의) 내부 API를 부르므로 트랜잭션을 두지 않는다 (오케스트레이터가 단계마다 짧게 연다)
     */
    public void withdraw(Long userId) {
        MemberWithdrawalUseCase useCase = memberWithdrawalUseCase.getIfAvailable();
        if (useCase == null) {
            throw new BusinessException(GlobalErrorCode.NOT_FOUND);
        }
        useCase.withdraw(userId);
    }

    /**
     * 탈퇴 회원 복구 (#593). 로그인 때 받은 복구 티켓으로 본인을 확인한다. 성공하면 티켓을 지우고,
     * 실패하면(지갑 장애 등) 남겨 둬 10분 안에 그대로 다시 시도할 수 있다. 다른 앱을 부르므로 트랜잭션을 두지 않는다.
     * 계정만 되살리고 로그인 토큰은 주지 않는다(로그인은 다시 한다, #633)
     */
    public void restore(String restoreTicket) {
        // 탈퇴 기능이 꺼져 있으면 티켓을 발급하지 않으므로 받은 티켓도 없는 것으로 본다
        MemberWithdrawalUseCase useCase = memberWithdrawalUseCase.getIfAvailable();
        if (useCase == null) {
            throw new MemberException(MemberErrorCode.INVALID_RESTORE_TICKET);
        }
        Long userId = restoreTicketStore.find(restoreTicket)
                .orElseThrow(() -> new MemberException(MemberErrorCode.INVALID_RESTORE_TICKET));
        useCase.restore(userId);
        restoreTicketStore.delete(restoreTicket);
    }

    /**
     * 복구 취소 (#633). "복구할까요?"에서 복구하지 않으면 남은 복구 티켓을 지운다. 공용 PC에서 다음 사람이 그 티켓으로
     * 원치 않는 복구를 하지 못하게 한다. 지우기만 하므로 티켓이 없거나 이미 지워졌어도 그대로 끝난다
     */
    public void cancelRestore(String restoreTicket) {
        if (restoreTicket == null || restoreTicket.isBlank()) {
            return;
        }
        restoreTicketStore.delete(restoreTicket);
    }

    /** 로그인 토큰 발급 (access token + refresh token, #370) */
    public TokenPair issueTokens(Long userId, Role role) {
        return memberTokenUseCase.issue(userId, role);
    }

    /** refresh token으로 재발급. 회원의 최신 역할·상태를 읽는다 */
    @Transactional(readOnly = true)
    public TokenPair refreshTokens(String refreshToken) {
        return memberTokenUseCase.refresh(refreshToken);
    }

    /**
     * 로그아웃: 이 기기의 refresh token 폐기 + 로그아웃 이벤트 (#539).
     * 이벤트는 가입·변경 이벤트처럼 발행 기록(아웃박스)을 이 트랜잭션에 남기고 커밋 뒤 Kafka로 나간다.
     * 폐기는 Redis라 트랜잭션에 묶이지 않는다: 폐기 뒤 커밋이 실패하면 로그아웃은 되고 이벤트만 나가지 않는다
     */
    @Transactional
    public void logout(String refreshToken) {
        memberTokenUseCase.logout(refreshToken);
    }

    /** 인증 코드 메일 보내기 (#557, 가입·비밀번호 재설정). 메일 발송을 DB 트랜잭션에 두지 않으려고 트랜잭션을 두지 않는다 */
    public void sendEmailCode(String email, EmailVerificationPurpose purpose, String clientIp) {
        memberEmailVerificationUseCase.sendCode(email, purpose, clientIp);
    }

    /** 인증 코드 확인 → 그 용도의 인증 토큰 (#557) */
    public String verifyEmailCode(String email, EmailVerificationPurpose purpose, String code) {
        return memberEmailVerificationUseCase.verifyCode(email, purpose, code);
    }

    /**
     * 이메일·비밀번호 가입 (#499). 로그인은 따로 한다. 이메일 인증(#557)을 마친 토큰이 있어야 하고, 가입이 커밋되면 토큰을 지운다.
     * 초기 계정·관리자 계정은 이 메서드가 아니라 가입 처리(register)를 바로 불러 인증을 거치지 않는다
     */
    @Transactional
    public User signUp(String email, String password, String nickname, String emailVerificationToken) {
        memberEmailVerificationUseCase.requireVerified(email, EmailVerificationPurpose.SIGN_UP, emailVerificationToken);
        User user = memberSignUpUseCase.signUp(email, password, nickname);
        memberEmailVerificationUseCase.consumeAfterUse(emailVerificationToken, EmailVerificationPurpose.SIGN_UP);
        return user;
    }

    /** 이메일·비밀번호 로그인 (#499). 토큰은 issueTokens로 발급한다 */
    @Transactional(readOnly = true)
    public User passwordLogin(String email, String password) {
        return memberPasswordLoginUseCase.login(email, password);
    }

    /**
     * 비밀번호 재설정 (#557): 재설정용 인증 토큰을 확인하고 새 비밀번호로 바꾼 뒤, 그 회원의 모든 기기를 로그아웃시킨다.
     * 로그아웃(refresh token 폐기)은 비밀번호 변경과 같이 커밋 전에 한다(바꾸기가 실패해도 로그아웃만 되는 안전한 쪽).
     * 토큰은 커밋된 뒤 지운다(실패하면 남아 다시 시도할 수 있다)
     */
    @Transactional
    public void resetPassword(String email, String emailVerificationToken, String newPassword) {
        memberEmailVerificationUseCase.requireVerified(email, EmailVerificationPurpose.PASSWORD_RESET, emailVerificationToken);
        User user = memberPasswordResetUseCase.reset(email, newPassword);
        memberTokenUseCase.revokeAll(user.getId());
        memberEmailVerificationUseCase.consumeAfterUse(emailVerificationToken, EmailVerificationPurpose.PASSWORD_RESET);
    }

    /**
     * 비밀번호 변경 (#499): 모든 기기의 refresh token을 폐기하고, 이 기기에는 새 토큰을 준다.
     * 폐기는 커밋 전에 하므로 변경이 실패해도 로그아웃만 되고 예전 비밀번호는 그대로다 (안전한 쪽)
     */
    @Transactional
    public TokenPair changePassword(Long userId, String currentPassword, String newPassword) {
        User user = memberPasswordChangeUseCase.change(userId, currentPassword, newPassword);
        memberTokenUseCase.revokeAll(userId);
        return memberTokenUseCase.issue(user.getId(), user.getRole());
    }
}
