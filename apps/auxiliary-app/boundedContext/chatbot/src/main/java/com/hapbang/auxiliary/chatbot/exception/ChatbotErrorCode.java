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
    SYNC_RUN_NOT_FOUND(HttpStatus.NOT_FOUND, "동기화 실행 기록을 찾을 수 없습니다."),
    /**
     * 관심사를 넣은 강의 추천인데 색인·임베딩을 쓸 수 없다. 관심사를 무시한 인기순으로 답하지 않는다 (#718).
     * 잠시 뒤 다시 보내면 되는 거절이라 공통 핸들러가 요청마다 ERROR로 남기지 않는다. 장애 시작·회복은 추천 쪽이 남긴다
     */
    LECTURE_RECOMMEND_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "지금은 관심사로 강의를 찾을 수 없어요. 잠시 후 다시 시도해 주세요.") {
        @Override
        public boolean isRetryLater() {
            return true;
        }
    };

    private final HttpStatus status;
    private final String message;
}
