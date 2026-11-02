package com.hapbang.chat.domain;

/**
 * 전달 outbox 작업의 종류. 전달 Stream·전달 채널 payload의 {@code type}과 같다.
 */
public enum ChatDeliveryType {

    /** 일반 메시지·시스템 안내·알림 메시지의 전달 */
    MESSAGE
}
