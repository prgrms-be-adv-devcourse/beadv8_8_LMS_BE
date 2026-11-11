package com.hapbang.auxiliary.search.common.out;

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
 * 검색 index 하나의 정의(#771, 공통 코드 #868). 레포 파일이 원본이다.
 * <ul>
 *     <li>{@code search/<대상>/index.json}: 그 대상의 설정·mapping. 사용자 사전과 동의어 묶음 이름은 자리표시자로 두고 여기서 채운다.</li>
 *     <li>{@code search/common/user-dictionary.txt}: nori 사용자 사전(한 줄 한 단어). 모든 대상이 함께 쓴다.</li>
 *     <li>{@code search/common/synonyms.txt}: 검색 동의어(한 줄 한 묶음). ES Synonyms API 묶음 {@link #SYNONYMS_SET} 하나로 올려 모든 대상이 함께
 *     쓴다.</li>
 * </ul>
 * index 이름은 {@code search-<대상>-<설정 해시>}이고 검색·쓰기는 alias {@code search-<대상>}으로만 한다. 해시는 채운 index 본문
 * (mapping·분석기·사용자 사전)으로 만들고 <b>동의어는 넣지 않는다</b>. 동의어는 검색할 때만 쓰여 바꿔도 재색인이 필요 없다(#767 Q21).
 */
public final class IndexDefinition {

    /**
     * ES Synonyms API 동의어 묶음 이름. index가 아니라 cluster에 하나이고 모든 대상이 함께 쓴다(L17). 이름이 index 본문에 들어가 바꾸면 설정 해시가
     * 바뀌므로, 공통 이름으로 바꾸는 일은 사용자 사전이 바뀌어 어차피 재색인하는 강의 검색 PR에서 함께 한다(#868 R4)
     */
    public static final String SYNONYMS_SET = "search-influencers-synonyms";

    private static final String COMMON = "search/common/";
    private static final String USER_DICTIONARY_PLACEHOLDER = "\"__USER_DICTIONARY__\"";
    private static final String SYNONYMS_SET_PLACEHOLDER = "__SYNONYMS_SET__";
    /** 한 단어 규칙만 받는다. 공백·따옴표가 들어가면 규칙 형식이 바뀌거나 JSON이 깨진다 */
    private static final Pattern ONE_WORD = Pattern.compile("[\\p{L}\\p{N}]+");
    /** index 이름에 붙이는 해시 길이(16진수 글자 수) */
    private static final int HASH_LENGTH = 12;

    private final String alias;
    private final String indexBody;
    private final String hash;
    private final List<String> synonymRules;

    private IndexDefinition(String target, String indexTemplate, List<String> userDictionary,
            List<String> synonymRules) {
        this.alias = aliasOf(target);
        this.indexBody = render(indexTemplate, userDictionary);
        this.hash = sha256(indexBody).substring(0, HASH_LENGTH);
        this.synonymRules = List.copyOf(synonymRules);
    }

    /**
     * 클래스패스의 레포 파일로 만든다.
     *
     * @param target 대상 이름(예: {@code influencers}). {@code search/<대상>/index.json}을 읽고 alias는 {@code search-<대상>}이다
     */
    public static IndexDefinition load(String target) {
        return of(target, read("search/" + target + "/index.json"), lines(read(COMMON + "user-dictionary.txt")),
                loadSynonymRules());
    }

    /** 공통 동의어 파일의 규칙(Solr 형식 한 줄씩, 파일 순서). 모든 대상이 묶음 하나로 함께 쓴다({@link SynonymsSetup}) */
    public static List<String> loadSynonymRules() {
        return lines(read(COMMON + "synonyms.txt"));
    }

    /** 파일 대신 내용을 직접 넘긴다(테스트용) */
    public static IndexDefinition of(String target, String indexTemplate, List<String> userDictionary,
            List<String> synonymRules) {
        return new IndexDefinition(target, indexTemplate, userDictionary, synonymRules);
    }

    /** 대상의 alias 이름({@code search-<대상>}) */
    public static String aliasOf(String target) {
        return "search-" + target;
    }

    /** 검색·쓰기에 쓰는 alias */
    public String alias() {
        return alias;
    }

    /** index 이름 앞부분. 뒤에 설정 해시가 붙는다 */
    public String indexPrefix() {
        return alias + "-";
    }

    /** 지금 설정의 index 이름 */
    public String indexName() {
        return indexPrefix() + hash;
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
