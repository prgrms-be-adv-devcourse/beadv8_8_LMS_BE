package com.hapbang.appearance.support;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;

/**
 * RestAssured 응답 검증용 Matcher (#390).
 * <p>
 * RestAssured는 JSON 정수를 크기에 따라 {@code Integer}나 {@code Long}으로 읽는다. 그래서 {@code equalTo(1L)}은 응답 {@code 1}(Integer)과
 * 다르다고 본다. ID·금액처럼 {@code long}으로 다루는 값은 {@link #number(long)}로 비교한다.
 */
public final class E2EMatchers {

    private E2EMatchers() {
    }

    /** 정수 타입과 상관없이 값이 같은 숫자 */
    public static Matcher<Object> number(long expected) {
        return new TypeSafeMatcher<>(Object.class) {
            @Override
            protected boolean matchesSafely(Object actual) {
                return (actual instanceof Integer || actual instanceof Long) && ((Number) actual).longValue() == expected;
            }

            @Override
            public void describeTo(Description description) {
                description.appendText("숫자 ").appendValue(expected);
            }
        };
    }
}
