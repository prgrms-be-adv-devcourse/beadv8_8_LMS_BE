package com.hapbang.lecture.global.storage;

/**
 * S3에 올라간 파일 정보 (HEAD 응답).
 *
 * @param size        바이트 크기
 * @param contentType 업로드할 때 보낸 Content-Type
 */
public record StoredFile(long size, String contentType) {
}
