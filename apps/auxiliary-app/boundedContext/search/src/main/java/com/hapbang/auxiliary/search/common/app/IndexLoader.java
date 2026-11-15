package com.hapbang.auxiliary.search.common.app;

/**
 * 재색인 부품: 처음 적재·재색인이 만든 새 index(alias 없음)를 원본 전체로 채운다(#773, 공통 코드 #868). 대상마다 구현한다(인플루언서: 회원 목록 +
 * 통계).
 * <ul>
 *     <li>alias가 아니라 이름을 받은 새 index에 쓴다. 보이는 문서와 숨김 문서(tombstone)를 모두 넣는다(교체 뒤 늦게 온 옛 이벤트가 숨긴 원본을 다시
 *     보이게 하지 못하게).</li>
 *     <li>그 원본 값 때문에 ES가 거절한 문서는 건너뛰고 센다(멈추면 처음 적재가 영영 끝나지 않는다). 그 밖의 쓰기 실패(장애)는 예외를 던진다.</li>
 *     <li>대상에만 있는 결과(예: 인플루언서 통계 실패 수)는 구현이 로그로 남긴다.</li>
 * </ul>
 */
public interface IndexLoader {

    /**
     * @param index 채울 새 index 이름
     * @return 넣은 결과. {@link LoadResult#visible()}이 새 index의 보이는 문서 수와 같아야 alias를 옮긴다
     * @throws IllegalStateException 쓰지 못한 문서가 있는 경우(장애). alias를 옮기지 않는다
     */
    LoadResult load(String index);

    /**
     * @param read    읽은 원본 수
     * @param visible 넣은 보이는 문서 수(문서 수 비교의 원본 수)
     * @param hidden  넣은 숨김 문서 수
     * @param skipped 원본 정보가 빠졌거나 ES가 그 원본 값 때문에 거절해 건너뛴 수
     */
    record LoadResult(long read, long visible, long hidden, long skipped) {
    }
}
