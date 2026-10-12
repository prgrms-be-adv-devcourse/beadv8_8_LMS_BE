package com.hapbang.appearance.global.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 컨트롤러 파라미터에 붙이면 로그인한 회원 ID({@code Long})를 넣어 준다.
 * <pre>{@code
 * @PostMapping
 * public ResponseEntity<?> create(@LoginMember Long memberId, ...) { ... }
 * }</pre>
 * 회원 ID를 어디서 꺼내는지는 {@link LoginMemberArgumentResolver} 한 곳에만 있다.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface LoginMember {
}
