package idu.sba.backend.domain.payment.service;

import idu.sba.backend.domain.payment.client.TossPaymentClient;
import idu.sba.backend.domain.payment.dto.*;
import idu.sba.backend.domain.payment.entity.*;
import idu.sba.backend.domain.payment.repository.PaymentMethodRepository;
import idu.sba.backend.domain.payment.repository.PlanRepository;
import idu.sba.backend.domain.payment.repository.SubscriptionHistoryRepository;
import idu.sba.backend.domain.payment.repository.SubscriptionRepository;
import idu.sba.backend.domain.terms.entity.Term;
import idu.sba.backend.domain.terms.entity.TermType;
import idu.sba.backend.domain.terms.entity.UserAgreement;
import idu.sba.backend.domain.terms.repository.TermRepository;
import idu.sba.backend.domain.terms.repository.UserAgreementRepository;
import idu.sba.backend.domain.user.entity.User;
import idu.sba.backend.domain.user.repository.UserRepository;
import idu.sba.backend.global.exception.BusinessException;
import idu.sba.backend.global.exception.ErrorCode;
import idu.sba.backend.global.mail.HtmlMailSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SubscriptionServiceImpl implements SubscriptionService {

    private final PlanRepository planRepository;
    private final PaymentMethodRepository paymentMethodRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionHistoryRepository subscriptionHistoryRepository;
    private final UserRepository userRepository;
    private final TossPaymentClient tossPaymentClient;
    private final TermRepository termRepository;
    private final UserAgreementRepository userAgreementRepository;

    //이메일 알림 연동
    private final HtmlMailSender htmlMailSender;


    private static final String FREE_PLAN_NAME = "FREE"; // FREE 플랜 시드의 이름



    //결제수단 등록 (SDK 결제창 방식)
    @Override
    public Long registerPaymentMethod(Long userId, PaymentMethodRequestDTO dto) {
        // customerKey는 프론트가 결제창에 넘긴 값 그대로 사용(서버 재생성 금지 — 발급 요청과 불일치하면 실패)
        var response = tossPaymentClient.issueBillingKey(dto); // authKey → 빌링키 발급

        PaymentMethod pm = new PaymentMethod(
                userId, dto.customerKey(), response.billingKey(), response.card().number());
        return paymentMethodRepository.save(pm).getId();
    }


    //요금제 목록
    @Override
    public List<PlanResponseDTO> getPlans() {
        return planRepository.findAll().stream()
                .map(p -> new PlanResponseDTO(
                        p.getId(), p.getName(), p.getDailyCreditLimit(),
                        p.getAllowedModels(), p.getPrice()))
                .toList();
    }

    //내 플랜 확인 — ACTIVE 구독 없으면 FREE로 간주(users.plan_id "null=FREE" 규칙과 동일). 500 대신 정상 응답.
    @Override
    public MyPlanResponseDTO getMyPlan(Long userId) {
        return subscriptionRepository.findByUserIdAndStatus(userId, SubscriptionStatus.ACTIVE)
                .map(sub -> {
                    Plan plan = planRepository.findById(sub.getPlanId())
                            .orElseThrow(() -> new IllegalStateException("구독중인 플랜을 찾을 수 없습니다."));
                    return new MyPlanResponseDTO(sub.getId(), plan.getId(), plan.getName(),
                            sub.getStartedAt(), sub.getExpiresAt().toLocalDate(), sub.getCancelledAt());
                })
                .orElseGet(() -> {
                    Plan free = planRepository.findByName(FREE_PLAN_NAME)
                            .orElseThrow(() -> new IllegalStateException("FREE 플랜 시드 데이터가 없습니다."));
                    return new MyPlanResponseDTO(null, free.getId(), free.getName(), null, null, null); // FREE: 구독 행 없음
                });
    }

    //현재 적용 중인 플랜 엔티티(ACTIVE 구독 없으면 FREE로 간주 — users.plan_id의 "null=FREE" 규칙과 동일)
    @Override
    public Plan getCurrentPlanEntity(Long userId) {
        return subscriptionRepository.findByUserIdAndStatus(userId, SubscriptionStatus.ACTIVE)
                .map(sub -> planRepository.findById(sub.getPlanId())
                        .orElseThrow(() -> new IllegalStateException("구독중인 플랜을 찾을 수 없습니다.")))
                .orElseGet(() -> planRepository.findByName(FREE_PLAN_NAME)
                        .orElseThrow(() -> new IllegalStateException("FREE 플랜 시드 데이터가 없습니다.")));
    }


    /**
     * [트레이드오프] 결제 없이 구독만 생성되는 상태 방지
     * 상황 : 구독 행을 먼저 만들고 결제를 따로 처리하면, 결제가 실패해도 구독만 덩그러니 남을 수 있다.
     * 선택 : 구독 저장과 최초 빌링키 즉시 청구를 같은 @Transactional 안에 묶는다.
     *        confirmBilling이 실패하면 예외가 올라가 save(sub)까지 전부 롤백된다(원자성).
     * 결과 : 결제 성공 없이는 구독이 생성되지 않는다. 청구 메일 실패는 삼켜서 구독 확정을 막지 않는다.
     */
    @Override
    @Transactional
    public Long createSubscription(Long userId, SubscriptionCreateDTO dto) {


        if (dto.agreeTerms() == null || dto.agreeTerms().isEmpty())
            throw new BusinessException(ErrorCode.REQUIRED_TERMS_NOT_AGREED);

        // #2 중복 ACTIVE 구독 방지 — 이미 구독 중이면 거절
        subscriptionRepository.findByUserIdAndStatus(userId, SubscriptionStatus.ACTIVE)
                .ifPresent(s -> { throw new BusinessException(ErrorCode.ALREADY_SUBSCRIBED); });

        // 약관 검증:  존재하는 약관 id인지 ,  결제 필수 약관에 동의했는지
        List<Term> allTerms = termRepository.findAll();
        Set<Long> validTermIds = allTerms.stream().map(Term::getId).collect(Collectors.toSet());
        if (!validTermIds.containsAll(dto.agreeTerms())) {          // 존재하지 않는 약관 거절
            throw new BusinessException(ErrorCode.INVALID_TERMS);
        }
        for (Term t : allTerms) {
            if (t.getType() == TermType.PAYMENT && !dto.agreeTerms().contains(t.getId())) {
                throw new BusinessException(ErrorCode.REQUIRED_TERMS_NOT_AGREED);
            }
        }

        //동의 약관 DB에 저장 (동의 당시 버전 함께 기록 — 재동의 판정용)
        Map<Long, String> versionById = allTerms.stream()
                .collect(Collectors.toMap(Term::getId, Term::getVersion));
        dto.agreeTerms().forEach(termId ->
                userAgreementRepository.save(UserAgreement.of(userId, termId, versionById.get(termId))));

        // 새로 구독할 플랜 조회
        Plan newPlan = planRepository.findById(dto.planId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PLAN_NOT_FOUND));

        // 결제수단 존재 + 소유 검증 (남의 결제수단으로 구독 못 하게)
        PaymentMethod pm = paymentMethodRepository.findById(dto.paymentMethodId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_METHOD_NOT_FOUND));
        if (!pm.getUserId().equals(userId))
            throw new BusinessException(ErrorCode.PAYMENT_METHOD_FORBIDDEN);

        // 구독 생성 (status=ACTIVE, startedAt=now) 후 저장
        Subscription sub = new Subscription();
        sub.start(userId, newPlan.getId(), pm.getId()); // status=ACTIVE, startedAt=now
        Subscription saved = subscriptionRepository.save(sub);

        // 최초 결제 — 빌링키로 즉시 1회 청구 (실패 시 @Transactional 롤백)
        String orderId = "SUB-" + saved.getId() + "-" + UUID.randomUUID();
        tossPaymentClient.confirmBilling(
                pm.getBillingKey(), pm.getCustomerKey(), newPlan.getPrice(),
                orderId, newPlan.getName() + " 구독 결제");

        //history 기록 (최초구독 previous=null, UPGRADE, prorated=가격 그대로)
        SubscriptionHistory history = new SubscriptionHistory();
        history.record(
                saved.getId(),
                null,
                newPlan.getId(),         // new_plan_id
                ChangeType.UPGRADE,
                newPlan.getPrice()       // 최초 구독은 일할계산 X 가격은 그대로
        );
        subscriptionHistoryRepository.save(history);

        //user 테이블 plan_id 캐시 갱신
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        user.updatePlanId(newPlan.getId());

        try {
            sendBillingMail(user.getEmail(), newPlan.getName(), newPlan.getPrice(), pm.getCardMaskedNumber());
        } catch (Exception e) {
            log.warn("결제 청구 메일 발송 실패 userId={}: {}", userId, e.getMessage());
        }


        return saved.getId();
    }

    // 플랜 변경
    @Override
    @Transactional
    public SubscriptionHistoryResponseDTO changeSubscription(Long userId, Long subId, SubscriptionChangeDTO dto) {
        Subscription sub = subscriptionRepository.findById(subId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SUBSCRIPTION_NOT_FOUND));

        // 소유자 검증 — 남의 구독은 변경 불가
        if (!sub.getUserId().equals(userId))
            throw new BusinessException(ErrorCode.SUBSCRIPTION_FORBIDDEN);

        Long previousPlanId = sub.getPlanId();
        Plan oldPlan = planRepository.findById(previousPlanId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLAN_NOT_FOUND));
        Plan newPlan = planRepository.findById(dto.newPlanId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PLAN_NOT_FOUND));

        // 업그레이드만 지원 — 다운그레이드/동일가 전환은 막는다(정책: 다운그레이드 없음, 해지만).
        if (newPlan.getPrice() <= oldPlan.getPrice()) {
            throw new BusinessException(ErrorCode.DOWNGRADE_NOT_ALLOWED);
        }

        //일할계산 (남은일수 기준 차액) — 업그레이드라 항상 양수
        int prorated = calculateProratedAmount(
                oldPlan.getPrice(), newPlan.getPrice(), sub);

        // 일할 차액을 빌링키로 즉시 청구. @Transactional 안이라 청구 실패 시 plan 전환·history까지 전부 롤백.
        PaymentMethod pm = paymentMethodRepository.findById(sub.getPaymentMethodId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_METHOD_NOT_FOUND));
        String orderId = "SUB-CHG-" + subId + "-" + UUID.randomUUID();
        tossPaymentClient.confirmBilling(
                pm.getBillingKey(), pm.getCustomerKey(), prorated,
                orderId, newPlan.getName() + " 전환 차액 결제");

        // 기존 구독 행 유지 + plan_id만 전환
        sub.changePlan(newPlan.getId());

        //history 기록
        SubscriptionHistory history = new SubscriptionHistory();
        history.record(subId, previousPlanId, newPlan.getId(), ChangeType.UPGRADE, prorated);
        SubscriptionHistory savedHistory = subscriptionHistoryRepository.save(history);

        User user = userRepository.findById(sub.getUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        user.updatePlanId(newPlan.getId());

        // 전환 차액 결제 완료 메일 — 신규 구독과 동일하게 알림(청구액 = prorated)
        try {
            sendBillingMail(user.getEmail(), newPlan.getName(), prorated, pm.getCardMaskedNumber());
        } catch (Exception e) {
            log.warn("전환 결제 메일 발송 실패 userId={}: {}", user.getId(), e.getMessage());
        }

        return new SubscriptionHistoryResponseDTO(
                savedHistory.getPreviousPlanId(), savedHistory.getNewPlanId(),
                savedHistory.getChangeType().name(), savedHistory.getProratedAmount(),
                savedHistory.getChangedAt());
    }


    @Override
    @Transactional
    public void cancelSubscription(Long userId, Long subId) {
        Subscription sub = subscriptionRepository.findById(subId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SUBSCRIPTION_NOT_FOUND));

        //  소유자 검증 — 남의 구독은 해지 불가
        if (!sub.getUserId().equals(userId))
            throw new BusinessException(ErrorCode.SUBSCRIPTION_FORBIDDEN);

        // 해지 예약 — cancelledAt만 기록, status는 ACTIVE 유지. 만료일에 스케줄러가 FREE 강등
        sub.reserveCancel();
    }

    //해지 예약 취소 — cancelledAt 초기화. 구독은 계속 유지된다.
    @Override
    @Transactional
    public void resumeSubscription(Long userId, Long subId) {
        Subscription sub = subscriptionRepository.findById(subId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SUBSCRIPTION_NOT_FOUND));
        if (!sub.getUserId().equals(userId))
            throw new BusinessException(ErrorCode.SUBSCRIPTION_FORBIDDEN);
        sub.undoCancel();
    }

    //내 구독 변경 이력 — 사용자의 모든 구독 건의 이력을 최신순으로, planId는 이름으로 변환해 반환
    @Override
    public List<SubHistoryItemDTO> getSubHistory(Long userId) {
        List<Long> subIds = subscriptionRepository.findByUserId(userId).stream()
                .map(Subscription::getId)
                .toList();
        if (subIds.isEmpty()) return List.of();

        Map<Long, String> planNames = planRepository.findAll().stream()
                .collect(Collectors.toMap(Plan::getId, Plan::getName));

        return subscriptionHistoryRepository.findBySubscriptionIdInOrderByChangedAtDesc(subIds).stream()
                .map(h -> new SubHistoryItemDTO(
                        h.getId(),
                        h.getPreviousPlanId() == null ? FREE_PLAN_NAME : planNames.getOrDefault(h.getPreviousPlanId(), "?"),
                        planNames.getOrDefault(h.getNewPlanId(), "?"),
                        h.getChangeType().name(),
                        h.getProratedAmount(),
                        h.getChangedAt()))
                .toList();
    }

    // 일할계산: (새가격 - 기존가격) × 남은일수 ÷ 구독주기일수
    // 주기는 달력월이 아니라 구독 주기(startedAt~expiresAt) 기준 — 가입일 앵커와 맞춤
    private int calculateProratedAmount(int oldPrice, int newPrice, Subscription sub) {
        LocalDate today = LocalDate.now();
        LocalDate start = sub.getStartedAt().toLocalDate();
        LocalDate expires = sub.getExpiresAt().toLocalDate();
        long cycleDays = ChronoUnit.DAYS.between(start, expires);
        if (cycleDays <= 0) return newPrice - oldPrice; // 방어: 비정상 주기면 차액 전액
        long remainingDays = ChronoUnit.DAYS.between(today, expires);
        remainingDays = Math.max(0, Math.min(remainingDays, cycleDays)); // [0, cycle]로 클램프
        int diff = newPrice - oldPrice;
        return (int) ((long) diff * remainingDays / cycleDays); // long 승격으로 오버플로 방지
    }

    //결제시 이메일 알림
    private void sendBillingMail(String to, String planName, long amount, String cardMasked) {
        String inner = """
        <p style="margin:0 0 8px;color:#1b2a4a;font-size:17px;font-weight:bold;">결제 완료</p>
        <p style="margin:0 0 20px;color:#40507a;font-size:13px;">구독 결제가 정상 처리되었습니다.</p>
        <div style="background:#fdfcf7;border:3px solid #1b2a4a;padding:16px;color:#1b2a4a;font-size:14px;">
          <p style="margin:0 0 6px;">플랜: <b>%s</b></p>
          <p style="margin:0 0 6px;">금액: <b>%,d원</b></p>
          <p style="margin:0;">결제수단: <b>%s</b></p>
        </div>
        """.formatted(planName, amount, cardMasked);
        htmlMailSender.send(to, "[COGI] 결제가 완료되었습니다", inner);

    }
}
