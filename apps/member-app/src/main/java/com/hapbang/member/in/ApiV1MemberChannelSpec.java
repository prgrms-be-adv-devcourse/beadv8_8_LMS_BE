package com.hapbang.member.in;

import java.util.List;

import org.springframework.http.ResponseEntity;

import com.hapbang.member.global.response.RsData;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** 채널 인증 API Swagger 명세. 매핑은 {@link ApiV1MemberChannelController}. 성공 응답은 RsData의 data에 담는다 (연동 시작·콜백은 302) */
@Tag(name = "채널 인증", description = "치지직 계정 연동으로 본인 채널 인증, 채널 승인 요청 (유저 → 인플루언서)")
public interface ApiV1MemberChannelSpec {

    @Operation(summary = "치지직 연동 시작",
            description = "유저만 (그 외 403). 브라우저로 이 주소를 열면 치지직 로그인·동의 화면으로 302 이동한다. "
                    + "Swagger의 Try it out이 아니라 새 탭에서 주소를 직접 연다.")
    ResponseEntity<Void> connectChzzk(HttpServletRequest request);

    @Operation(summary = "치지직 연동 콜백",
            description = "치지직이 브라우저를 이 주소로 돌려보낸다 (직접 호출하지 않는다). "
                    + "본인 채널과 팔로워 수를 확인해 10명 이상이면 자동 승인(인플루언서), 미만이면 등록만 한다. "
                    + "결과는 프론트 화면으로 302: /mypage?chzzk=approved|registered|failed(&code=오류 코드, 치지직 장애는 INTERNAL_ERROR).")
    ResponseEntity<Void> chzzkCallback(Long userId, String code, String state,
                                       HttpServletRequest request, HttpServletResponse response);

    @Operation(summary = "내 채널 목록",
            description = "로그인한 회원. 등록한 채널 전부 (먼저 등록한 순). "
                    + "상태: REGISTERED(등록, 승인 요청 가능) / PENDING(관리자 심사 대기) / APPROVED(승인) / REJECTED(반려, 다시 요청 가능). "
                    + "followerCountAt은 팔로워 수를 조회한 시각.")
    RsData<List<MyChannelResponse>> myChannels(Long userId);

    @Operation(summary = "채널 승인 요청",
            description = "유저만 (그 외 403). 정상 회원이 아니면 404 MEMBER_NOT_FOUND, 내 채널이 아니면 404 CHANNEL_NOT_FOUND, "
                    + "등록·반려 상태가 아니면 409. "
                    + "팔로워 수를 다시 조회해 10명 이상이면 자동 승인(APPROVED, 유저 → 인플루언서, 세션 권한도 바로 바뀜), "
                    + "미만이면 관리자 심사 대기(PENDING). 치지직에서 채널을 찾지 못하면 404 CHZZK_CHANNEL_NOT_FOUND, "
                    + "치지직 장애는 500 INTERNAL_ERROR.")
    RsData<ChannelVerificationResponse> requestApproval(Long userId, Long channelId,
                                                        HttpServletRequest request, HttpServletResponse response);
}
