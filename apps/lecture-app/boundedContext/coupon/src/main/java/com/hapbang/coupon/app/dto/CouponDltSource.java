package com.hapbang.coupon.app.dto;

/**
 * DLT로 격리할 Kafka 메시지의 위치. Kafka 수신 어댑터가 레코드에서 꺼내 넘기고, 격리 기록({@code CouponDltLog})의 원본 위치로 남긴다.
 *
 * @param topic     원 토픽
 * @param partition 원 partition
 * @param offset    원 offset
 * @param key       메시지 key. 없으면 null
 */
public record CouponDltSource(String topic, int partition, long offset, String key) {
}
