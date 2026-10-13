package com.hapbang.member.in;

import org.springframework.http.ResponseEntity;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** 채널 인증 API Swagger 명세. 매핑은 {@link ApiV1MemberChannelController} */
@Tag(name = "채널 인증", description = "치지직 계정 연동으로 본인 채널 인증 (유저만)")
public interface ApiV1MemberChannelSpec {

    @Operation(summary = "치지직 연동 시작",
            description = "유저만 (그 외 403). 브라우저로 이 주소를 열면 치지직 로그인·동의 화면으로 302 이동한다. "
                    + "Swagger의 Try it out이 아니라 새 탭에서 주소를 직접 연다.")
    ResponseEntity<Void> connectChzzk(HttpServletRequest request);

    @Operation(summary = "치지직 연동 콜백",
            description = "치지직이 브라우저를 이 주소로 돌려보낸다 (직접 호출하지 않는다). "
                    + "본인 채널과 팔로워 수를 확인해 10명 이상이면 자동 승인(인플루언서), 미만이면 등록만 한다. "
                    + "결과는 프론트 화면으로 302: /mypage?chzzk=approved|registered|failed(&code=오류 코드).")
    ResponseEntity<Void> chzzkCallback(Long userId, String code, String state,
                                       HttpServletRequest request, HttpServletResponse response);
}
