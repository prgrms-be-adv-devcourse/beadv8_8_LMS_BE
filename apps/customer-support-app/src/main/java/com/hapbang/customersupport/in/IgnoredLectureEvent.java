package com.hapbang.customersupport.in;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 고객센터가 받지 않는 강의 이벤트를 읽어 두는 빈 레코드 (#722). 본문의 필드는 읽지 않고 버린다.
 * <p>
 * 이 타입으로 읽으면 이벤트 클래스를 찾지 않으므로, native 이미지에 등록하지 않은 새 이벤트 타입도 역직렬화에 실패하지 않는다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IgnoredLectureEvent() {
}
