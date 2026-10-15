package idu.sba.backend.domain.guest.service;

import idu.sba.backend.domain.guest.dto.GuestReviewRequest;
import idu.sba.backend.domain.review.repository.AiUsageLogRepository;
import idu.sba.backend.domain.review.repository.ReviewIssueRepository;
import idu.sba.backend.domain.review.repository.ReviewRepository;
import idu.sba.backend.domain.review.service.PromptBuilder;
import idu.sba.backend.global.ai.AiReviewClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.Optional;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GuestReviewServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private GuestQuotaStore guestQuotaStore;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private AiReviewClient aiReviewClient;

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private ReviewIssueRepository reviewIssueRepository;

    @Mock
    private PromptBuilder promptBuilder;

    @Mock
    private AiUsageLogRepository aiUsageLogRepository;

    @InjectMocks
    private GuestReviewService guestReviewService;

    @Test
    void rejectsWithoutCallingAiOrRefundingWhenQuotaIsFull() {
        String guestToken = "test-guest";
        GuestReviewRequest request = mock(GuestReviewRequest.class);
        when(guestQuotaStore.tryAcquire(guestToken)).thenReturn(Optional.empty());

        assertThrows(GuestReviewService.TrialLimitExceededException.class,
                () -> guestReviewService.createReview(request, guestToken));

        verify(guestQuotaStore).tryAcquire(guestToken);
        verify(guestQuotaStore, never()).refund(any());
        verifyNoInteractions(aiReviewClient, redisTemplate);
    }
}