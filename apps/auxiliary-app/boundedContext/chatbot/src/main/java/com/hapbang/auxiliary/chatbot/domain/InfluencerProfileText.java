package com.hapbang.auxiliary.chatbot.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 인플루언서 프로필 임베딩 글 (#719). 분야 한글 이름들 + 자기소개.
 * <ul>
 *     <li>자기소개는 이메일·전화번호를 지운 뒤({@link PersonalInfoScrubber}) 임베딩 API에만 보낸다(공개 프로필, 11/05 결정).
 *     ES에는 글이 아니라 {@link #hash()}만 둔다.</li>
 *     <li>해시가 같으면 다시 임베딩하지 않는다. 분야 이름이 바뀌어도 글이 바뀌므로 다음 정합 점검이 다시 색인한다.</li>
 * </ul>
 *
 * @param text 임베딩할 글. 분야와 자기소개가 모두 없으면 빈 글
 * @param hash 글의 SHA-256(hex)
 */
public record InfluencerProfileText(String text, String hash) {

    /**
     * @param categoryNames 분야 한글 이름(회원이 고른 순서)
     * @param bio           자기소개(없으면 null)
     */
    public static InfluencerProfileText of(List<String> categoryNames, String bio) {
        List<String> parts = new ArrayList<>();
        if (!categoryNames.isEmpty()) {
            parts.add("분야: " + String.join(", ", categoryNames));
        }
        String scrubbed = bio == null ? "" : PersonalInfoScrubber.scrub(bio).strip();
        if (!scrubbed.isEmpty()) {
            parts.add("소개: " + scrubbed);
        }
        String text = String.join("\n", parts);
        return new InfluencerProfileText(text, sha256(text));
    }

    /** 분야·자기소개가 모두 없다. 문서를 만들지 않는다(인기순 후보로만 쓰인다) */
    public boolean isEmpty() {
        return text.isEmpty();
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 쓸 수 없습니다", e);
        }
    }
}
