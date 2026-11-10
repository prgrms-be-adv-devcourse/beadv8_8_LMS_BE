package com.hapbang.auxiliary.search.out;

/**
 * ES가 회원 문서 쓰기를 요청 내용 때문에 거절했다(400: 값이 mapping에 맞지 않음, script 오류 등, #772 코드 리뷰).
 * 같은 회원 정보로 다시 써도 같은 이유로 거절된다. ES 장애(연결 실패, 5xx, 429)와 겹친 쓰기(409)는 여기에 들지 않는다.
 * <p>
 * {@link IllegalStateException}의 하위라 이벤트 소비는 지금처럼 다시 시도한 뒤 DLT로 보낸다. 증분 보정만 이 예외를 따로 잡아 그 회원을 건너뛴다
 * (멈추면 커서가 그 페이지를 영영 넘어가지 못한다).
 */
public class DocumentRejectedException extends IllegalStateException {

    public DocumentRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
