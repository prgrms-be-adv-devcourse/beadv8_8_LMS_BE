package com.hapbang.member.domain;

import lombok.RequiredArgsConstructor;

/** 방송 플랫폼. 새 플랫폼은 값과 채널 주소 규칙을 추가한다 (지금은 치지직만) */
@RequiredArgsConstructor
public enum ChannelPlatform {
    /** 치지직 (네이버). 채널 주소: https://chzzk.naver.com/{채널 ID} */
    CHZZK("https://chzzk.naver.com/");

    private final String channelUrlPrefix;

    /** 채널 고유 ID로 채널 주소를 만든다 */
    public String channelUrl(String channelIdentifier) {
        return channelUrlPrefix + channelIdentifier;
    }
}
