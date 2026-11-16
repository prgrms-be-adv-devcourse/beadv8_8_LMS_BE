package com.hapbang.auxiliary.search.common.out;

import java.util.ArrayList;
import java.util.List;

import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;

/**
 * {@code search_as_you_type} 칸을 낱말 앞부분으로 찾는 쿼리(#973). 자동완성 칸({@code title.suggest} 등)과 초성·자모 칸이 같이 쓴다. 대상과 상관없다.
 * <p>
 * {@code multi_match}의 {@code bool_prefix}로, 마지막 낱말은 앞부분만 맞아도 되고 앞의 낱말은 통째로 맞아야 한다({@code ㅂㅅㅈㅂ ㅊ} →
 * {@code ㅂㅅㅈㅂ ㅊㅊ}). 낱말은 모두 맞아야 한다({@code AND}). ES 문서가 권하는 대로 칸마다 하위 칸 {@code ._2gram}·{@code ._3gram}을 같은
 * 가중치로 함께 찾아, 이어진 낱말이 그대로 맞으면 점수가 더 높다.
 */
public final class SearchAsYouType {

    private SearchAsYouType() {
    }

    /**
     * @param text   찾을 말(이미 칸에 맞게 바꾼 값: 초성 칸이면 초성)
     * @param fields {@code search_as_you_type} 칸과 가중치({@code "titleChosung^3"})
     */
    public static Query boolPrefix(String text, List<String> fields) {
        List<String> expanded = new ArrayList<>();
        for (String field : fields) {
            int caret = field.indexOf('^');
            String name = caret < 0 ? field : field.substring(0, caret);
            String boost = caret < 0 ? "" : field.substring(caret);
            expanded.add(name + boost);
            expanded.add(name + "._2gram" + boost);
            expanded.add(name + "._3gram" + boost);
        }
        return Query.of(q -> q.multiMatch(m -> m.query(text).fields(expanded).type(TextQueryType.BoolPrefix)
                .operator(Operator.And)));
    }
}
