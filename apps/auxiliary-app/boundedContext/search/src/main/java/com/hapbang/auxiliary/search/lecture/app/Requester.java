package com.hapbang.auxiliary.search.lecture.app;

/**
 * 강의 검색을 부른 사람(#883 L2·L19). 회원이면 회원 ID, 비회원이면 client IP 키다. 0건 검색어의 "서로 다른 검색자"를 세는 데 쓰고 남기지 않는다.
 *
 * @param userId      회원 ID. 비회원이면 null
 * @param clientIpKey 비회원의 client IP 해시. 회원이거나 IP를 믿을 수 없으면(공인 주소가 아님) null
 */
public record Requester(Long userId, String clientIpKey) {

    public static Requester member(long userId) {
        return new Requester(userId, null);
    }

    public static Requester guest(String clientIpKey) {
        return new Requester(null, clientIpKey);
    }
}
