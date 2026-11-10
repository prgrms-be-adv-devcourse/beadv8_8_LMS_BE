package com.hapbang.auxiliary.search.out;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.core.io.ClassPathResource;

/**
 * 인플루언서 검색 index 정의(#771). 레포 파일 셋이 원본이다.
 * <ul>
 *     <li>{@code search/influencers/index.json}: 설정·mapping. 사용자 사전과 동의어 묶음 이름은 자리표시자로 두고 여기서 채운다.</li>
 *     <li>{@code search/influencers/user-dictionary.txt}: nori 사용자 사전(한 줄 한 단어).</li>
 *     <li>{@code search/influencers/synonyms.txt}: 검색 동의어(한 줄 한 묶음). ES Synonyms API 묶음으로 올린다.</li>
 * </ul>
 * index 이름은 {@code search-influencers-<설정 해시>}이고 검색·쓰기는 alias {@link #ALIAS}로만 한다. 해시는 채운 index 본문
 * (mapping·분석기·사용자 사전)으로 만들고 <b>동의어는 넣지 않는다</b>. 동의어는 검색할 때만 쓰여 바꿔도 재색인이 필요 없다(#767 Q21).
 */
public final class InfluencerIndexDefinition {

    /** 검색·쓰기에 쓰는 alias */
    public static final String ALIAS = "search-influencers";
    /** index 이름 앞부분. 뒤에 설정 해시가 붙는다 */
    public static final String INDEX_PREFIX = ALIAS + "-";
    /** ES Synonyms API 동의어 묶음 이름. index가 아니라 cluster에 하나다 */
    public static final String SYNONYMS_SET = "search-influencers-synonyms";

    private static final String BASE = "search/influencers/";
    private static final String USER_DICTIONARY_PLACEHOLDER = "\"__USER_DICTIONARY__\"";
    private static final String SYNONYMS_SET_PLACEHOLDER = "__SYNONYMS_SET__";
    /** 한 단어 규칙만 받는다. 공백·따옴표가 들어가면 규칙 형식이 바뀌거나 JSON이 깨진다 */
    private static final Pattern ONE_WORD = Pattern.compile("[\\p{L}\\p{N}]+");
    /** index 이름에 붙이는 해시 길이(16진수 글자 수) */
    private static final int HASH_LENGTH = 12;

    private final String indexBody;
    private final String hash;
    private final List<String> synonymRules;

    private InfluencerIndexDefinition(String indexTemplate, List<String> userDictionary, List<String> synonymRules) {
        this.indexBody = render(indexTemplate, userDictionary);
        this.hash = sha256(indexBody).substring(0, HASH_LENGTH);
        this.synonymRules = List.copyOf(synonymRules);
    }

    /** 클래스패스의 레포 파일로 만든다 */
    public static InfluencerIndexDefinition load() {
        return of(read(BASE + "index.json"), lines(read(BASE + "user-dictionary.txt")),
                lines(read(BASE + "synonyms.txt")));
    }

    /** 테스트용. 파일 내용을 직접 넘긴다 */
    static InfluencerIndexDefinition of(String indexTemplate, List<String> userDictionary, List<String> synonymRules) {
        return new InfluencerIndexDefinition(indexTemplate, userDictionary, synonymRules);
    }

    /** 지금 설정의 index 이름 */
    public String indexName() {
        return INDEX_PREFIX + hash;
    }

    /** index 생성 요청 본문(설정·mapping, 사용자 사전과 동의어 묶음 이름을 채운 JSON) */
    public String indexBody() {
        return indexBody;
    }

    /** 동의어 묶음 규칙(Solr 형식 한 줄씩). 파일 순서다 */
    public List<String> synonymRules() {
        return synonymRules;
    }

    private static String render(String template, List<String> userDictionary) {
        if (!template.contains(USER_DICTIONARY_PLACEHOLDER) || !template.contains(SYNONYMS_SET_PLACEHOLDER)) {
            throw new IllegalStateException("index.json에 사용자 사전·동의어 묶음 자리표시자가 없습니다.");
        }
        userDictionary.forEach(word -> {
            if (!ONE_WORD.matcher(word).matches()) {
                throw new IllegalStateException("사용자 사전은 한 줄에 공백 없는 한 단어만 둡니다: " + word);
            }
        });
        String dictionary = userDictionary.stream().map(word -> "\"" + word + "\"")
                .collect(Collectors.joining(", ", "[", "]"));
        return template.replace(USER_DICTIONARY_PLACEHOLDER, dictionary).replace(SYNONYMS_SET_PLACEHOLDER, SYNONYMS_SET);
    }

    /** 빈 줄과 #으로 시작하는 줄을 뺀 줄들(앞뒤 공백 제거) */
    static List<String> lines(String text) {
        return text.lines().map(String::strip).filter(line -> !line.isEmpty() && !line.startsWith("#")).toList();
    }

    private static String read(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            // 줄바꿈을 맞춰 OS(체크아웃 설정)에 따라 해시가 달라지지 않게 한다
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new UncheckedIOException("검색 index 정의 파일을 읽지 못했습니다 path=" + path, e);
        }
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
