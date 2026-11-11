package com.hapbang.auxiliary.chatbot.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Objects;

import com.hapbang.shared.jpa.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * FAQ 하나(#715). 원본은 시드 파일이고, 시작할 때 {@code code}로 맞춘다. 벡터 색인(Elasticsearch)은 이 테이블에서 만든다.
 * <p>
 * {@code contentHash}는 임베딩에 쓰는 내용(분류·질문·답변)의 해시, {@code indexedHash}는 마지막으로 색인에 성공한 내용의 해시다.
 * 둘이 다르면 다시 색인해야 한다. 색인이 실패하면 {@code indexedHash}를 바꾸지 않아 다음 시도에서 다시 한다.
 */
@Entity
@Table(schema = "chatbot", name = "faq")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Faq extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 20)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private FaqCategory category;

    @Column(nullable = false, length = 200)
    private String question;

    @Column(nullable = false, length = 2000)
    private String answer;

    @Column(nullable = false, length = 300)
    private String policyRef;

    /** 시드 파일 안의 순서. 목록 정렬에 쓴다 */
    @Column(nullable = false)
    private int sortOrder;

    @Column(nullable = false, length = 64)
    private String contentHash;

    @Column(length = 64)
    private String indexedHash;

    private LocalDateTime indexedAt;

    private Faq(String code) {
        this.code = code;
    }

    public static Faq create(String code, FaqContent content) {
        Faq faq = new Faq(code);
        faq.apply(content);
        return faq;
    }

    /**
     * 시드 값으로 고친다.
     *
     * @return 값이 하나라도 바뀌었으면 true
     */
    public boolean update(FaqContent content) {
        if (content().equals(content)) {
            return false;
        }
        apply(content);
        return true;
    }

    public FaqContent content() {
        return new FaqContent(category, question, answer, policyRef, sortOrder);
    }

    /** 임베딩에 넣는 글. 질문과 답변을 한 덩어리로 넣는다(FAQ 하나 = 문서 하나) */
    public String embeddingText() {
        return question + "\n" + answer;
    }

    /** 마지막 색인 뒤 내용이 바뀌었거나 아직 색인하지 않았다 */
    public boolean needsIndexing() {
        return !Objects.equals(contentHash, indexedHash);
    }

    /** 이 내용(해시)으로 색인에 성공했다. 색인 중 내용이 바뀌었으면 그 해시만 기록해 다음 시도에서 다시 색인한다 */
    public void markIndexed(String hash, LocalDateTime now) {
        this.indexedHash = hash;
        this.indexedAt = now;
    }

    private void apply(FaqContent content) {
        this.category = content.category();
        this.question = content.question();
        this.answer = content.answer();
        this.policyRef = content.policyRef();
        this.sortOrder = content.sortOrder();
        this.contentHash = hash(category.name() + "\n" + embeddingText());
    }

    static String hash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 쓸 수 없습니다", e);
        }
    }
}
