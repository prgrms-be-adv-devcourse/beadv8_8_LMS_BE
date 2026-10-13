package com.hapbang.global.storage;

import java.time.Instant;

/**
 * Presigned URL과 만료 시각.
 *
 * @param url       S3에 직접 요청할 주소. 서명이 들어 있으므로 로그에 남기지 않는다
 * @param expiresAt 만료 시각
 */
public record PresignedUrl(String url, Instant expiresAt) {
}
