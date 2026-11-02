package com.hapbang.shared.lecture.out;

/**
 * 테스트에서 {@link LectureApiCallException}을 만든다. 생성자가 shared 패키지 안에서만 열려 있어 같은 패키지에 둔다.
 * 운영 코드는 이 예외를 {@link LectureApiClient}에서만 받는다.
 */
public final class LectureApiCallExceptions {

    private LectureApiCallExceptions() {
    }

    /** 강의가 4xx로 거절했다. {@code code}는 오류 응답의 code다 */
    public static LectureApiCallException rejected(int status, String code) {
        return new LectureApiCallException(LectureApiCallException.Reason.REJECTED, status, code,
                "테스트 강의 거절 " + code, null);
    }

    /** 결과를 받지 못했다(연결 실패·시간 초과·5xx) */
    public static LectureApiCallException unavailable() {
        return new LectureApiCallException(LectureApiCallException.Reason.UNAVAILABLE, 0, null, "테스트 강의 호출 불가",
                null);
    }
}
