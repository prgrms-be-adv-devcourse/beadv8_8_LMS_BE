package com.hapbang.member.app;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientResponseException;

import com.hapbang.member.domain.ChannelPlatform;
import com.hapbang.member.out.ChzzkApiClient;
import com.hapbang.member.out.FollowerRefreshTarget;
import com.hapbang.member.out.InfluencerChannelRepository;
import com.hapbang.shared.member.domain.UserStatus;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 정상 회원의 치지직 채널 팔로워 수를 20개씩 묶어 갱신한다 (#302).
 * <ul>
 *     <li>치지직 조회는 트랜잭션 밖, 저장은 묶음마다 별도 트랜잭션. 팔로워 수·조회 시각 두 칸만 바꾼다.</li>
 *     <li>치지직 결과에 없는 채널(삭제·정지 등)은 그대로 두고 로그만 남긴다.</li>
 *     <li>묶음 조회가 실패하면 다음 묶음으로 넘어간다. 앱 키 오류(401)는 남은 묶음도 모두 실패하므로 바로 중단한다.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberFollowerRefreshUseCase {

    private static final int UNAUTHORIZED = 401;

    private final InfluencerChannelRepository influencerChannelRepository;
    private final ChzzkApiClient chzzkApiClient;
    private final TransactionTemplate transactionTemplate;

    public FollowerRefreshResult refreshAll() {
        List<FollowerRefreshTarget> targets =
                influencerChannelRepository.findFollowerRefreshTargets(ChannelPlatform.CHZZK, UserStatus.ACTIVE);
        int updated = 0;
        int missing = 0;
        int failedChunks = 0;
        for (List<FollowerRefreshTarget> chunk : chunks(targets, ChzzkApiClient.MAX_CHANNELS_PER_REQUEST)) {
            Map<String, Integer> counts;
            try {
                counts = chzzkApiClient.findFollowerCounts(
                        chunk.stream().map(FollowerRefreshTarget::channelIdentifier).toList());
            } catch (RestClientResponseException e) {
                if (e.getStatusCode().value() == UNAUTHORIZED) {
                    log.error("팔로워 갱신 중단: 치지직 앱 키 오류(401), 갱신={}, 남은 대상은 다음 실행에서", updated);
                    return new FollowerRefreshResult(targets.size(), updated, missing, failedChunks, true);
                }
                failedChunks++;
                log.warn("팔로워 갱신 묶음 실패, 다음 묶음으로: status={}", e.getStatusCode());
                continue;
            } catch (RuntimeException e) {
                failedChunks++;
                log.warn("팔로워 갱신 묶음 실패, 다음 묶음으로: {}", e.getMessage());
                continue;
            }
            LocalDateTime now = LocalDateTime.now();
            int saved = transactionTemplate.execute(status -> save(chunk, counts, now));
            updated += saved;
            missing += chunk.size() - saved;
        }
        log.info("팔로워 갱신 완료: 대상={}, 갱신={}, 치지직에 없음={}, 실패 묶음={}",
                targets.size(), updated, missing, failedChunks);
        return new FollowerRefreshResult(targets.size(), updated, missing, failedChunks, false);
    }

    /** 결과에 있는 채널만 두 칸 UPDATE하고 갱신한 수를 돌려준다. 없는 채널은 그대로 두고 로그 */
    private int save(List<FollowerRefreshTarget> chunk, Map<String, Integer> counts, LocalDateTime now) {
        int saved = 0;
        for (FollowerRefreshTarget target : chunk) {
            Integer followerCount = counts.get(target.channelIdentifier());
            if (followerCount == null) {
                log.warn("치지직 조회 결과에 채널 없음, 팔로워 수 그대로: channelId={}, channelIdentifier={}",
                        target.channelId(), target.channelIdentifier());
                continue;
            }
            saved += influencerChannelRepository.updateFollowerCount(target.channelId(), followerCount, now);
        }
        return saved;
    }

    /** size개씩 나눈다 (마지막 묶음은 더 적을 수 있다) */
    private static <T> List<List<T>> chunks(List<T> list, int size) {
        List<List<T>> chunks = new ArrayList<>();
        for (int from = 0; from < list.size(); from += size) {
            chunks.add(list.subList(from, Math.min(from + size, list.size())));
        }
        return chunks;
    }
}
