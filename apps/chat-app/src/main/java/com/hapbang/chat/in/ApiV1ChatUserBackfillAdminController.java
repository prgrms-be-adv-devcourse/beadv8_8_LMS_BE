package com.hapbang.chat.in;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.chat.app.ChatUserBackfillFacade;
import com.hapbang.chat.app.dto.ChatUserBackfillResult;
import com.hapbang.chat.global.response.RsData;

import lombok.RequiredArgsConstructor;

/**
 * 관리자 수동 기존 회원 백필 API(설계 문서 5.4). 관리자 API는 /admin 경로(global SecurityConfig)와
 * {@link ChatUserBackfillFacade}의 {@code @PreAuthorize}로 함께 막는다.
 * limit을 생략하면 관리자 HTTP 요청 시간을 줄이려고 100명만 처리한다.
 */
@RestController
@RequestMapping("/api/v1/chats/admin/chat-users")
@RequiredArgsConstructor
public class ApiV1ChatUserBackfillAdminController implements ApiV1ChatUserBackfillAdminSpec {

    private final ChatUserBackfillFacade chatUserBackfillFacade;

    @Override
    @PostMapping("/backfill")
    public RsData<ChatUserBackfillResult> backfill(
            @RequestParam(name = "afterId", defaultValue = "0") long afterId,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        return RsData.ok("채팅 회원 백필을 실행했습니다.", chatUserBackfillFacade.backfill(afterId, limit));
    }
}
