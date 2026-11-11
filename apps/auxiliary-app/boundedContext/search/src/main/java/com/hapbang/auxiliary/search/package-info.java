/**
 * 검색 모듈: 인플루언서 검색(#767). 회원 이벤트와 회원 앱 통계로 Elasticsearch(nori)에 인플루언서 사본을 두고 검색한다.
 * <p>
 * 공통 모듈({@code global})만 의존한다. 같은 앱의 다른 업무 모듈(챗봇·알림)을 직접 참조하지 않고, 챗봇의 ES index·client도 쓰지 않는다.
 * ES는 검색 전용 client({@code ES_SEARCH_API_KEY})로 {@code search-*} index에만 접근한다. 테이블은 {@code search} schema에만 둔다.
 */
@ApplicationModule(displayName = "Search", allowedDependencies = "global")
package com.hapbang.auxiliary.search;

import org.springframework.modulith.ApplicationModule;
