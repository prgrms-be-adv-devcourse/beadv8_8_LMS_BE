package com.hapbang.auxiliary.notification.app.exception;

import org.springframework.http.HttpStatus;

import com.hapbang.auxiliary.global.exception.BaseErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 알림 모듈 오류 코드. 응답 형식은 global {@code GlobalExceptionHandler}가 만든다(루트 AGENTS 7장) */
@Getter
@RequiredArgsConstructor
public enum NotificationErrorCode implements BaseErrorCode {

    /** 회원 이벤트의 회원 정보가 복제할 수 없는 값이다(ID·닉네임·역할·상태 없음) */
    INVALID_MEMBER_EVENT(HttpStatus.BAD_REQUEST, "복제할 수 없는 회원 정보입니다."),
    /** 남의·없는·밀려난(Soft Delete된) 알림. 응답으로 다른 회원 알림의 존재를 구분할 수 없게 하나로 쓴다(ND-052) */
    NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "알림을 찾을 수 없습니다."),
    /** 받는 사람의 회원 복제본이 아직 없다. 이벤트 처리를 실패시켜 재시도 뒤 DLT로 보낸다(ND-035) */
    RECEIVER_NOT_REPLICATED(HttpStatus.CONFLICT, "받는 사람의 회원 복제본이 없습니다."),
    /**
     * 문구에 닉네임을 넣을 다른 회원(출연 요청의 호스트, 모집 지원의 게스트)의 복제본이 아직 없다. 받는 사람 복제본 없음(ND-035)과
     * 같이 처리를 실패시켜 재시도 뒤 DLT로 보낸다. 첫 배포 때는 Backfill이 채운다(ND-077)
     */
    MENTIONED_NOT_REPLICATED(HttpStatus.CONFLICT, "문구에 넣을 회원의 복제본이 없습니다."),
    /** 회원 복제본 Backfill을 다른 실행(기동 때 자동 실행, 다른 pod, 다른 관리자 요청)이 하고 있다(ND-078·ND-079) */
    BACKFILL_RUNNING(HttpStatus.CONFLICT, "회원 복제본 Backfill이 이미 실행 중입니다."),
    /** 회원 복제본 Backfill을 끝까지 하지 못했다(회원 API·DB 장애). 결과 없이 완료가 아니다. 다시 실행하면 된다(ND-089) */
    BACKFILL_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "회원 복제본 Backfill을 마치지 못했습니다. 잠시 뒤 다시 실행해 주세요."),
    /** 연결 티켓 저장소(Redis)를 쓸 수 없어 발급하지 못했다(ND-058) */
    CONNECTION_TICKET_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "지금은 실시간 연결을 준비할 수 없습니다.");

    private final HttpStatus status;
    private final String message;
}
