/**
 * 다른 모듈이 쓰는 성공 응답 형식 (RsData). 성공은 RsData로, 오류는 {@code exception}의 ProblemDetail로 응답한다
 * (2026-10-13 팀 규칙).
 */
@NamedInterface("response")
package com.hapbang.global.response;

import org.springframework.modulith.NamedInterface;
