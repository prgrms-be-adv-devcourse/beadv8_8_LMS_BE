package com.hapbang.auxiliary.search.common.app;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 검색어 제안({@link KeywordCorrection}, #977)이 대상 index에 묻는 것. 대상(강의·인플루언서)마다 칸만 다르게 구현한다. 낱말은 모두 소문자다.
 */
public interface KeywordIndex {

    /** {@code words} 가운데 원문 칸이나 자모 칸에 낱말(term)로 있는 것 */
    Set<String> indexedWords(Collection<String> words);

    /** 검색 분석기(동의어 포함)로 나눈 결과 */
    Analysis analyze(String word);

    /** 검색어를 nori로 나눈 조각들의 품사 태그({@code VV}, {@code EF}, {@code NNG} …). 조각마다 왼쪽·오른쪽 품사를 다 넣는다 */
    List<String> partsOfSpeech(String keyword);

    /** 낱말마다 고칠 후보. 보이는 문서에 그 낱말 그대로 있는 것만 준다. 자모 칸에서 찾은 후보는 음절로 되돌린 값이다 */
    Map<String, Set<String>> candidates(Collection<String> words);

    /**
     * @param tokens  조사·어미를 뺀 조각({@code 방송을} → {@code [방송]}, {@code 을} → {@code []}). 동의어로 펼친 말은 넣지 않는다
     * @param synonym 낱말 전체가 동의어로 펼쳐지는지({@code 배그} → 배틀그라운드). 낱말 일부만 펼쳐지면({@code 메이플장임}의 {@code 메이플}) 아니다
     */
    record Analysis(List<String> tokens, boolean synonym) {
    }
}
