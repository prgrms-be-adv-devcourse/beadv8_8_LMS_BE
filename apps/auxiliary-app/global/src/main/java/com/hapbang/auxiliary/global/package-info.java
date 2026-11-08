/**
 * auxiliary-app의 업무 모듈(chatbot 등)이 함께 쓰는 공통 코드: 응답 형식, 오류 처리, 로그인 회원, 내부 API 보안, 스케줄링 조건.
 * 업무 규칙은 두지 않는다. 모든 업무 모듈이 쓸 수 있도록 열린 모듈({@code OPEN})로 둔다.
 */
@ApplicationModule(displayName = "Global", type = ApplicationModule.Type.OPEN)
package com.hapbang.auxiliary.global;

import org.springframework.modulith.ApplicationModule;
