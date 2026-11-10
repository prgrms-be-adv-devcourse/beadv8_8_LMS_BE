package com.hapbang.auxiliary.search.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;

/**
 * 검색 기능 스위치. {@code hapbang.search.enabled=true}일 때만 검색 모듈의 빈(ES client, 이벤트 소비자, 배치, API)을 만든다.
 * 없으면 꺼진다.
 * <p>
 * 운영 ES의 검색용 API key({@code ES_SEARCH_API_KEY}, #806)가 생기기 전에 #771을 병합하려고 둔 임시 장치다. 꺼져 있어도
 * auxiliary-app(챗봇·알림)은 그대로 뜬다. #806과 검색 2(#772, 증분 보정)가 모두 끝나면 이 애노테이션과 설정을 <b>지운다</b>(켜는 것이
 * 아니라 없앤다). 증분 보정을 기다리는 이유: 그 전에는 ES가 잠깐 멈춰 DLT로 빠진 회원 이벤트를 사람이 되돌려야 한다({@code docs/search/module.md} 5장).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnBooleanProperty(name = ConditionalOnSearchEnabled.PROPERTY)
public @interface ConditionalOnSearchEnabled {

    /** 스위치 설정 키 */
    String PROPERTY = "hapbang.search.enabled";
}
