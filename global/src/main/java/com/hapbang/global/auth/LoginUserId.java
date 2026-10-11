package com.hapbang.global.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 로그인 회원 ID를 컨트롤러 파라미터로 받는다 (예: @LoginUserId Long userId) */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface LoginUserId {
}
