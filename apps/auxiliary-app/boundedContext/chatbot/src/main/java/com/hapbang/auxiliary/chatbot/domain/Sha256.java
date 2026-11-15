package com.hapbang.auxiliary.chatbot.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256을 16진수 소문자 64자로 낸다 (#835). 챗봇의 해시(사본·FAQ·색인 글)는 모두 이것을 쓴다 */
public final class Sha256 {

    private Sha256() {
    }

    /** 글을 UTF-8로 바꿔 해시한다 */
    public static String hex(String text) {
        return hex(text.getBytes(StandardCharsets.UTF_8));
    }

    public static String hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 쓸 수 없습니다", e);
        }
    }
}
