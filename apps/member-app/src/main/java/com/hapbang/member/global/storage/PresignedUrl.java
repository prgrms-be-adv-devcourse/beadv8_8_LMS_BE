package com.hapbang.member.global.storage;

import java.time.Instant;

/**
 * Presigned URL과 만료 시각.
 *
 * @param url                  S3에 직접 요청할 주소. 서명이 들어 있으므로 로그에 남기지 않는다
 * @param expiresAt            만료 시각
 * @param credentialsExpiresAt 서명에 쓴 자격 증명이 끝나는 시각. 이 시각이 지나면 URL도 {@code expiresAt} 전에 끝난다.
 *                             만료가 없는 자격 증명(로컬 S3Mock의 고정 키)이면 null
 */
public record PresignedUrl(String url, Instant expiresAt, Instant credentialsExpiresAt) {
}
