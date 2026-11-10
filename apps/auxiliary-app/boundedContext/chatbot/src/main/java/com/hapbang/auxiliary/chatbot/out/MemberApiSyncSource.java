package com.hapbang.auxiliary.chatbot.out;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;

import com.hapbang.shared.member.dto.ContentCategoryDto;
import com.hapbang.shared.member.dto.MemberPageDto;
import com.hapbang.shared.member.out.MemberApiClient;

/**
 * 회원 내부 목록 API로 읽는다 (#717, 계약 docs/api/internal-members.md).
 * <p>
 * auxiliary-app은 shared의 공통 Client 패키지를 스캔하지 않으므로({@code AuxiliaryApplication}) 회원 Client만 가져온다.
 * Client가 없을 때의 처리는 {@link ApiClients}.
 */
@Component
@Import(MemberApiClient.class)
public class MemberApiSyncSource implements MemberSyncSource {

    private final ObjectProvider<MemberApiClient> client;

    public MemberApiSyncSource(ObjectProvider<MemberApiClient> client) {
        this.client = client;
    }

    @Override
    public MemberPageDto members(Long afterId, int size) {
        return ApiClients.require(client, "member").getMembers(afterId, size);
    }

    @Override
    public Map<String, String> categoryNames() {
        Map<String, String> names = new LinkedHashMap<>();
        for (ContentCategoryDto category : ApiClients.require(client, "member").getContentCategories().items()) {
            names.put(category.code(), category.name());
        }
        return names;
    }
}
