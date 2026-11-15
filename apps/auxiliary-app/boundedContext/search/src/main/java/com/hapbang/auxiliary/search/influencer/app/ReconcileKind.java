package com.hapbang.auxiliary.search.influencer.app;

/**
 * 증분 보정이 회원 한 명을 반영한 결과(#772 Q1). 쓰기 결과(ES의 updated·noop)가 아니라 <b>검색에 보이는 내용이 실제로 바뀌었는지</b>로 가른다.
 * 프로필 사진 변경처럼 이벤트 없이 version만 오르는 경우를 보정으로 세지 않기 위해서다.
 * {@link #isFix()}인 셋(넣음·숨김·덮음)이 0이 아니면 회원 이벤트가 빠졌다는 뜻이다.
 */
public enum ReconcileKind {

    /** 검색에 안 보이던 회원을 보이게 했다(문서가 없거나 숨김이었음). 승급 이벤트를 놓친 경우 */
    INSERTED("inserted"),
    /** 보이던 회원을 숨겼다. 강등·정지·탈퇴 이벤트를 놓친 경우 */
    HIDDEN("hidden"),
    /** 보이는 회원의 프로필(닉네임·자기소개·역할·분야)을 고쳤다. 변경 이벤트를 놓친 경우 */
    OVERWRITTEN("overwritten"),
    /** 보이는 내용은 같고 version만 올렸다(프로필 사진 변경 등). 숨김 문서끼리의 변경도 여기다 */
    VERSION_ONLY("version_only"),
    /** 이미 같거나 더 새 version이라 바꾸지 않았다(이벤트로 반영됨, 겹쳐 읽은 회원) */
    ALREADY_APPLIED("already_applied"),
    /** 회원 ID·역할·상태가 빠진 정보이거나 ES가 문서 내용 때문에 거절해 건너뛰었다(다시 해도 같다) */
    SKIPPED("skipped");

    private final String tag;

    ReconcileKind(String tag) {
        this.tag = tag;
    }

    /** 로그·지표 태그 값 */
    public String tag() {
        return tag;
    }

    /** 이벤트가 빠져 보정한 것인지 */
    public boolean isFix() {
        return this == INSERTED || this == HIDDEN || this == OVERWRITTEN;
    }
}
