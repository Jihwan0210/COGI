package idu.sba.backend.domain.guest.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GuestReviewHttpTest {

    @ParameterizedTest
    @ValueSource(strings = {"redis", "db"})
    @Timeout(90)
    void allowsRetryAfterAiFailureDespiteRejectedRequests(String backend) throws Exception {
        try (var app = new GuestReviewHttpFixture(backend)) {
            // 1. 같은 게스트의 요청 3건을 AI 처리 중으로 유지한다.
            System.out.println("저장소: " + backend);
            app.startThreeRequests();
            assertThat(app.count()).isEqualTo(3);

            // 2. 초과 요청 10건을 보내고 선택한 저장소의 카운터를 읽는다.
            List<Integer> rejected = new ArrayList<>();
            for (int i = 0; i < 10; i++) rejected.add(app.post("excess"));
            long afterRejected = app.count();
            int aiCallsAfterRejected = app.aiCalls();

            // 3. 기존 1건을 모든 AI 모델에서 실패시키고, 새 요청을 보낸다.
            int failureStatus = app.failOneRequest();
            long afterRefund = app.count();
            int retryStatus = app.post("retry");
            long afterRetry = app.count();
            int totalAiCalls = app.aiCalls();
            List<Integer> completed = app.finishOtherRequests();

            // 4. 하드코딩한 결과가 아니라 이번 실행에서 측정한 값을 출력한다.
            System.out.printf("""

                    [게스트 횟수 제한 HTTP 실험]
                    초과 요청 거절: %d / 10건
                    거절 후 카운터: %d (기대: 3)
                    AI 실패 응답: %d (기대: 502)
                    AI 실패 후 카운터: %d (기대: 2)
                    새 요청 응답: %d (기대: 200)
                    새 요청 후 카운터: %d (기대: 3)
                    AI 호출: 초과 요청 후 %d회 / 전체 %d회 (기대: 3 / 5)
                    나머지 두 요청 응답: %s (기대: [200, 200])
                    """, rejected.stream().filter(s -> s == 403).count(), afterRejected,
                    failureStatus, afterRefund, retryStatus, afterRetry,
                    aiCallsAfterRejected, totalAiCalls, completed);

            assertThat(rejected).hasSize(10).containsOnly(403);
            assertThat(aiCallsAfterRejected).isEqualTo(3);
            assertThat(failureStatus).isEqualTo(502);
            assertThat(completed).containsExactly(200, 200);
            assertThat(afterRejected).isEqualTo(3);
            assertThat(afterRefund).isEqualTo(2);
            assertThat(retryStatus).isEqualTo(200);
            assertThat(afterRetry).isEqualTo(3);
            assertThat(totalAiCalls).isEqualTo(5); // 최초 3회 + 폴백 1회 + 새 요청 1회
            System.out.println("검증: 통과");
        }
    }
}
