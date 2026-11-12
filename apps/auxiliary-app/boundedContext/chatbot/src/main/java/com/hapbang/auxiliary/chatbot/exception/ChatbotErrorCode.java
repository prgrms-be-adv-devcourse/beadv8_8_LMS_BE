package com.hapbang.auxiliary.chatbot.exception;

import org.springframework.http.HttpStatus;

import com.hapbang.auxiliary.global.exception.BaseErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 챗봇 모듈 오류 코드 */
@Getter
@RequiredArgsConstructor
public enum ChatbotErrorCode implements BaseErrorCode {

    /** 같은 종류의 싱크가 이 pod나 다른 pod에서 실행 중이다 (#716) */
    SYNC_ALREADY_RUNNING(HttpStatus.CONFLICT, "이미 실행 중인 동기화가 있습니다."),
    /** 실행 락을 시도하지 못했다(DB 연결 불가 등) (#716) */
    SYNC_LOCK_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "동기화를 시작하지 못했습니다. 잠시 후 다시 시도해 주세요."),
    /** 회원 사본으로 확인한 결과 지금 정상 상태의 관리자가 아니다(사본 없음 포함) (#717) */
    NOT_ADMIN(HttpStatus.FORBIDDEN, "관리자만 이용할 수 있습니다."),
    /** 싱크 회차가 없다 (#716) */
    SYNC_RUN_NOT_FOUND(HttpStatus.NOT_FOUND, "동기화 실행 기록을 찾을 수 없습니다.");

    private final HttpStatus status;
    private final String message;
}
