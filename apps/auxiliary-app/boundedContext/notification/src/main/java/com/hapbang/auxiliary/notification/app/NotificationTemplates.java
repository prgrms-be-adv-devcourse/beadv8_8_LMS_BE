package com.hapbang.auxiliary.notification.app;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** 종류 코드별 문구 템플릿 모음. 같은 종류가 둘이면 기동에 실패한다 */
@Component
public class NotificationTemplates {

    private final Map<String, NotificationTemplate> byKind;

    /** 템플릿이 하나도 없어도 뜬다(N2에는 알림을 만드는 종류가 없다, ND-050) */
    public NotificationTemplates(ObjectProvider<NotificationTemplate> templates) {
        this.byKind = templates.orderedStream()
                .collect(Collectors.toUnmodifiableMap(NotificationTemplate::kind, t -> t));
    }

    /**
     * @throws IllegalStateException 그 종류의 템플릿이 없을 때(코드 결함이라 재시도 뒤 DLT로 간다)
     */
    public Function<Map<String, Object>, String> renderer(String kind) {
        NotificationTemplate template = byKind.get(kind);
        if (template == null) {
            throw new IllegalStateException("알림 종류의 문구 템플릿이 없습니다: " + kind);
        }
        return template::render;
    }
}
