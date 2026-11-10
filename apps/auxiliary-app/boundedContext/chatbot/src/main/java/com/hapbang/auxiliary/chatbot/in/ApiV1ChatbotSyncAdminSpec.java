package com.hapbang.auxiliary.chatbot.in;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.hapbang.auxiliary.chatbot.app.dto.SyncRunDetailResDto;
import com.hapbang.auxiliary.chatbot.app.dto.SyncRunResDto;
import com.hapbang.auxiliary.chatbot.app.dto.SyncRunStartResDto;
import com.hapbang.auxiliary.chatbot.domain.SyncType;
import com.hapbang.auxiliary.global.response.RsData;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "챗봇 동기화 관리", description = "관리자 전용 (#716, #717). 챗봇이 다른 모듈 데이터의 읽기용 사본을 바로잡는 싱크(종류마다 매시, "
        + "기록 30일 보관)를 지금 실행하고 기록을 본다. 관리자가 아니면 403. 토큰이 관리자여도 챗봇의 회원 사본에 정상 상태 관리자로 "
        + "없으면 403 NOT_ADMIN(첫 배포 뒤 첫 회원 싱크 전에는 사본에 없을 수 있다).")
public interface ApiV1ChatbotSyncAdminSpec {

    @Operation(summary = "싱크 지금 실행",
            description = "그 종류의 원본을 처음부터 끝까지 읽어 사본을 바로잡는다. 회차를 만들고 바로 202와 회차 ID를 준다(실행은 비동기, "
                    + "진행은 회차 상세로 본다). 같은 종류가 이 서버나 다른 서버에서 실행 중이면 409 SYNC_ALREADY_RUNNING, "
                    + "실행 락을 시도하지 못하면 503 SYNC_LOCK_UNAVAILABLE.")
    RsData<SyncRunStartResDto> start(Long adminId,
            @Parameter(description = "APPEARANCE(출연), APPEARANCE_REVIEW(공개된 출연 리뷰), MEMBER(회원), LECTURE(강의), "
                    + "LECTURE_PURCHASE(구매), CART_ITEM(장바구니), LECTURE_REVIEW(강의 리뷰)", required = true) SyncType type);

    @Operation(summary = "싱크 회차 목록",
            description = "최신순. 종류를 생략하면 모든 종류. 상태는 RUNNING·SUCCEEDED·FAILED·SKIPPED(다른 실행이 락을 쥐고 있어 건너뜀).")
    RsData<Page<SyncRunResDto>> getRuns(Long adminId, @Parameter(description = "종류. 생략하면 모든 종류") SyncType type,
            Pageable pageable);

    @Operation(summary = "싱크 회차 상세",
            description = "회차와 어긋난 항목(회차당 최대 100건, 종류마다 최근 10회만 남김). 첫 실행은 원본 전체를 넣으므로 어긋남 행 없이 "
                    + "건수만 있다. 없으면 404 SYNC_RUN_NOT_FOUND.")
    RsData<SyncRunDetailResDto> getRun(Long adminId, Long runId);
}
