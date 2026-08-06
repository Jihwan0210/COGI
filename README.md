# COGI — 담당 구현 정리 (결제 · 관리자 콘솔 · 비로그인 리뷰)

COGI는 AI 코드리뷰 결과를 약점 통계로 누적하고 학습으로 이어주는 서비스다.
이 문서는 그중 내가 구현한 영역(결제/구독, 관리자 콘솔, 비로그인 게스트 리뷰, 약관 버전 관리,
강의 추천, 소셜 로그인 계정 병합)의 설계와 실제로 밟았던 문제들을 정리한 것이다.

## 기술 스택

| 영역 | 사용 기술 |
|---|---|
| 백엔드 | Java 25, Spring Boot 4.1, Spring Data JPA, Spring Security, MariaDB |
| 캐시/임시 저장 | Redis (`StringRedisTemplate`) |
| 결제 | 토스페이먼츠 빌링키(자동결제), `@Scheduled` 배치 |
| 외부 연동 | OpenAI / Anthropic Admin Usage API, Gemini · Groq(리뷰 모델), SMTP 메일 |
| 프론트엔드 | React + TypeScript + Vite, `@tosspayments/tosspayments-sdk` |

---

## 1. 결제 · 구독 (토스페이먼츠 빌링키)

카드 정보를 서버가 직접 받지 않는 **SDK 결제창 + 빌링키** 방식이다.

```
[프론트] payment.requestBillingAuth()   ← customerKey를 프론트에서 생성
    ↓ successUrl?planId=..&customerKey=..&authKey=..
[BillingSuccess.tsx] POST /api/payments/methods { authKey, customerKey }
    ↓
[TossPaymentClient] POST /v1/billing/authorizations/issue  → billingKey 발급·저장
    ↓
[SubscriptionServiceImpl] POST /api/subscriptions → 최초 1회 즉시 청구
    ↓
[BillingScheduler] 매일 00:00 → 만료 도래분 빌링키로 자동 청구 (또는 FREE 강등)
```

### 주요 파일

| 파일 | 역할 |
|---|---|
| [TossPaymentClient.java](../backend/src/main/java/idu/sba/backend/domain/payment/client/TossPaymentClient.java) | 빌링키 발급 / 빌링키 청구 2개 API 래핑 |
| [SubscriptionServiceImpl.java](../backend/src/main/java/idu/sba/backend/domain/payment/service/SubscriptionServiceImpl.java) | 구독 생성·플랜 전환(일할계산)·해지 예약·해지 취소·이력 |
| [BillingScheduler.java](../backend/src/main/java/idu/sba/backend/domain/payment/scheduler/BillingScheduler.java) | 매일 자정 만료 도래 구독 순회 |
| [BillingProcessor.java](../backend/src/main/java/idu/sba/backend/domain/payment/scheduler/BillingProcessor.java) | 건별 트랜잭션 — 정기결제 / 실패 시 FREE 강등 |

### API

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/plans` | 요금제 목록 |
| GET | `/api/users/me/plan` | 내 플랜 (ACTIVE 구독 없으면 FREE) |
| GET | `/api/users/me/credit-usage` | 크레딧 사용량 |
| POST | `/api/payments/methods` | authKey → 빌링키 발급·등록 |
| POST | `/api/subscriptions` | 구독 생성 + 최초 결제 |
| PATCH | `/api/subscriptions/{subId}` | 플랜 전환(업그레이드, 일할 차액 즉시 청구) |
| DELETE | `/api/subscriptions/{subId}` | 해지 예약 |
| POST | `/api/subscriptions/{subId}/resume` | 해지 예약 취소 |
| GET | `/api/users/me/subscription-history` | 구독 변경 이력 |

### 설계 결정

- **해지는 즉시 종료가 아니라 예약**이다. `cancelledAt`만 기록하고 `status`는 ACTIVE로 두어
  남은 기간은 그대로 쓰게 하고, 만료일 배치가 FREE로 강등한다. 해지 취소는 `cancelledAt`을 비우면 끝난다.
- **다운그레이드는 막았다.** 정책상 상향 전환 + 해지만 있으므로 `newPrice <= oldPrice`면 `DOWNGRADE_NOT_ALLOWED`.
- **일할계산은 달력월이 아니라 구독 주기 기준**이다. `(새가격 − 기존가격) × 남은일수 ÷ (startedAt~expiresAt 일수)`.
  가입일 앵커(예: 매월 17일 결제)와 어긋나지 않게 하려면 달력월을 쓰면 안 된다.
- **소유자 검증**을 전환·해지·재개 전부에 넣었다. 남의 `subId`로 요청하면 `SUBSCRIPTION_FORBIDDEN`.
- 결제 성공 시 HTML 메일을 보내지만, **메일 실패는 결제 결과에 영향 없게** try/catch로 분리했다.

---

## 2. 관리자 콘솔

`/api/admin/**` + 프론트 `pages/admin/tabs/*`.

| 기능 | 백엔드 | 화면 |
|---|---|---|
| 회원관리 (상태/권한 변경, 삭제, 페이지네이션 15건) | `AdminMemberServiceImpl` | `MembersTab.tsx` |
| AI 사용량 (자체 집계, 일/주/월) | `AdminUsageServiceImpl` | `UsageTab.tsx` |
| 벤더 실사용량 (OpenAI·Anthropic Admin API) | `ProviderUsageClient` | `UsageTab.tsx` |
| 전체공지 (긴급=메일+인앱 / 일반=인앱, 삭제, 이력) | `AdminNoticeServiceImpl`, `NoticeMailDispatcher` | `NoticeTab.tsx` |
| 약관 본문 편집 | `AdminTermController` | `TermsTab.tsx` |
| 리뷰 지침(레벨별 프롬프트) 편집 | `AdminGuidelineServiceImpl` | `GuidesTab.tsx` |

### 자체 집계 vs 벤더 실사용량

두 숫자는 목적이 다르다.

- **자체 집계**: 리뷰 1건마다 `ai_usage_logs`에 토큰·비용·용도를 적재. 사용자별·용도별로 쪼개볼 수 있다.
- **벤더 실사용량**: OpenAI `/organization/usage/completions` + `/organization/costs`,
  Anthropic `usage_report/messages` + `cost_report`를 1일 버킷으로 조회. 실제 청구액과 맞춰보는 용도다.
  Gemini는 전용 usage API가 없어 제외했다.

`GET /api/admin/ai-usage/provider` 는 Admin 키가 없으면 **빈 배열**을 준다. 키 설정 전에도 화면이 깨지지 않아야 해서다.

### 안전장치

- 본인 계정의 상태·권한 변경과 삭제를 금지했다(관리자 락아웃 방지).
- 회원 영구 삭제는 `SUSPENDED`/`WITHDRAWN`만 허용, 그 외는 409. 프론트 버튼 비활성 + 서버 검증 이중 방어.
- 탈퇴 후 30일 경과 계정은 매일 05:00 배치로 영구 삭제한다.
- AI 누적 사용량이 예산의 90%를 넘으면 상단 경고 배너 + 토스트.

---

## 3. 비로그인 게스트 리뷰 (Redis)

회원가입 없이 코드리뷰를 3회까지 체험하고, 가입하면 그 결과를 자기 계정으로 이어받는다.

```
POST /api/guest/local-review        쿠키 없으면 guestToken 발급(HttpOnly 쿠키)
  ├ INCR guest:count:{token}        1회일 때만 TTL 24h → 첫 리뷰 시점 기준 리셋
  ├ 3회 초과면 403
  ├ Gemini 호출 → 실패 시 Groq 폴백 (성공한 모델을 기록)
  ├ ai_usage_logs 적재 (userId=null, GUEST_REVIEW)
  └ SET guest:review:{token}:{reviewId} = 원본 code + model + 결과 (TTL 24h)

POST /api/guest/local-review/{reviewId}/claim   ← 회원가입·로그인 직후
  └ Redis 레코드 → reviews / review_issues 테이블로 이관 후 키 삭제
```

- 파일: [GuestReviewService.java](../backend/src/main/java/idu/sba/backend/domain/guest/service/GuestReviewService.java),
  [GuestReview.tsx](../frontend/src/pages/GuestReview.tsx)
- 체험 결과를 DB에 넣지 않고 Redis에 둔 이유: 주인 없는 행(`user_id = null`)을 `reviews`에 만들면
  통계·정리 로직이 전부 null을 신경 써야 한다. TTL 24시간이면 자동으로 사라지므로 정리 배치도 필요 없다.
- 프론트에 주는 응답과 Redis 레코드가 다르다. claim 때 `reviews` 행을 복원해야 하므로
  Redis에는 **원본 code와 실제 사용 모델까지** 담는다.
- claim은 best-effort다. 쿠키·식별자가 없거나 이미 이관됐으면 조용히 넘어간다. 로그인 흐름을 막으면 안 된다.

---

## 4. 약관 버전 재동의

- `user_agreements.agreed_version`에 **동의 당시 버전**을 기록한다.
- 필수 약관(결제 약관 제외)의 현재 버전과 내 동의 버전이 다르면 재동의 대상.
- 로그인(소셜 포함) 후 대시보드 진입 전에 `ReagreeGate`가 막는다. 관리자 계정은 제외(약관 관리 주체).
- 선택 약관은 마이페이지 약관 탭에서 동의/해제 토글, 개정되면 재동의.
- 판정: [UserServiceImpl.checkReagreement](../backend/src/main/java/idu/sba/backend/domain/user/service/UserServiceImpl.java)

## 5. 약점 기반 강의 추천

처음엔 AI에게 강의를 물어봤는데 존재하지 않는 강의·죽은 링크를 만들어냈다.
그래서 **큐레이션 마스터 테이블(`courses`)** 로 교체했다.
약점 통계의 category(BUG/PERFORMANCE/CODE_SMELL/CONVENTION/SECURITY)와 언어로 매칭해
인프런·Udemy 링크를 준다. `language = null`이면 언어무관 강의로 모든 약점에 노출된다.

- [Course.java](../backend/src/main/java/idu/sba/backend/domain/learning/entity/Course.java), `LearningServiceImpl.getCourseRecommendations`

## 6. 소셜 로그인 중복계정 병합

같은 이메일로 이미 가입돼 있으면 새 계정을 만들지 않고 소셜 계정을 **연동(병합)** 한다.

- 카카오는 `is_email_verified = true`일 때만 병합한다. 미인증 이메일로 병합을 허용하면 계정 탈취가 된다.
- GitHub는 verified 이메일만 노출되므로 병합 키로 신뢰한다.
- 이미 같은 소셜이 연결된 계정이면 `email_exists`로 차단.
- 신규·병합 양쪽 모두 `autoMatchPendingInvitations`를 호출해 대기 중 레포 초대 자동 수락이 누락되지 않게 했다.
- [CustomOAuth2UserService.java](../backend/src/main/java/idu/sba/backend/global/security/CustomOAuth2UserService.java)

---

# 트러블슈팅

실제로 막혔던 것만 적었다. 원인 → 해결 순.

## 결제

### 1. 스케줄러에서 `@Transactional`이 통째로 무시됐다

정기결제 배치를 한 클래스 안에 `runBilling()` → `processOne()`으로 두고 `processOne`에 `@Transactional`을 붙였는데
더티체킹이 반영되지 않았다. `@Transactional`은 스프링 **프록시**가 걸어주는데, 같은 클래스 내부 호출(self-invocation)은
프록시를 거치지 않아 어노테이션이 죽는다.

→ 결제 처리부를 `BillingProcessor` 별도 빈으로 분리하고 스케줄러가 주입받아 호출하게 했다.
   그 뒤로는 트랜잭션 안에서 재조회한 엔티티의 `extend()`/`expire()`가 정상 반영된다.

### 2. 결제 실패했는데 강등 이력이 사라졌다

결제 실패 시 예외 → 트랜잭션 롤백이 맞는데, 같은 트랜잭션 안에서 FREE 강등과 CANCEL 이력을 남기면
그 기록까지 함께 롤백돼 "왜 FREE가 됐는지" 추적이 불가능해진다.

→ `handlePaymentFailure()`를 **별도 트랜잭션**으로 분리해 스케줄러의 catch 블록에서 호출한다.
   결제 시도는 롤백되고 강등·이력은 남는다.

### 3. 구독 한 건 실패가 배치 전체를 죽였다

만료 도래 구독을 순회하다 중간에 예외가 나면 뒤 사람들은 결제되지 않았다.

→ 순회 안에서 건별 try/catch. 실패 건은 강등 처리하고 다음 건으로 넘어간다.

### 4. 정기결제 이력이 `UPGRADE`로 기록됐다

매달 자동결제를 최초 구독과 같은 코드로 처리해 `ChangeType.UPGRADE`가 쌓였다.
구독 이력 화면에서 매달 업그레이드한 사람처럼 보였다.

→ 정기결제는 `RENEWAL`, 최초/전환은 `UPGRADE`, 강등·해지는 `CANCEL`로 분리했다.

### 5. 빌링키 발급이 계속 실패했다 (`customerKey` 불일치)

`customerKey`를 서버에서 새로 만들어 발급 요청에 실었다. 토스는 결제창에 넘긴 `customerKey`와
빌링키 발급 요청의 `customerKey`가 같아야 하는데 서버가 재생성하니 매번 불일치였다.

→ 프론트가 결제창에 넘긴 값을 successUrl 쿼리로 그대로 받아 서버로 전달한다. **서버 재생성 금지.**

### 6. 카드 정보를 서버가 받는 구조였다

처음엔 키인 방식(`/authorizations/card`)으로 카드번호·유효기간을 직접 받았다. 서버가 카드 원문을 만지는 건
보안·컴플라이언스 모두 불리하다.

→ SDK 결제창(`requestBillingAuth`) 방식으로 교체. 서버는 `authKey`만 받아 빌링키로 교환하고,
   카드번호는 마스킹된 표시용 문자열만 저장한다.

### 7. `/api/users/me/plan`이 500을 뱉었다

구독 행이 없는 무료 사용자에서 `orElseThrow()`가 터졌다. FREE는 애초에 구독 행이 없다
(`users.plan_id`도 null=FREE 규칙).

→ ACTIVE 구독이 없으면 FREE 플랜 시드를 반환한다. `getCurrentPlanEntity()`도 같은 규칙으로 통일해
   플랜 판정이 한 곳에서만 나오게 했다.

### 8. 일할계산이 음수·오버플로가 될 수 있었다

`diff * remainingDays`가 int 연산이었고, 만료일이 지난 비정상 구독에서는 `remainingDays`가 음수였다.

→ `(long) diff * remainingDays / cycleDays`로 승격, `remainingDays`는 `[0, cycleDays]`로 클램프,
   주기가 0 이하인 비정상 데이터는 차액 전액 청구로 방어.

### 9. 요금제 전환·중복 구독에서 결제가 두 번 잡혔다

ACTIVE 구독이 겹칠 수 있는 구조라 중복 결제가 발생했다.

→ 전환은 구독 행을 유지하고 `plan_id`만 바꾸는 방식으로 정리했고, 신규 구독 진입점에는
   `findByUserIdAndStatus(ACTIVE)` 검사로 `ALREADY_SUBSCRIBED`를 던진다.
   차액 청구는 `@Transactional` 안에서 하므로 청구 실패 시 전환·이력이 전부 롤백된다.

## 게스트 리뷰

### 10. AI 호출이 실패하면 체험 횟수만 깎였다

Redis `INCR` 후 AI 호출이 터지면 카운트는 이미 올라간 상태였다. 게스트는 실패 3번으로 체험이 끝났고,
응답도 500 `RuntimeException`으로 뭉개졌다.

→ 모든 모델이 실패한 경우 `DECR`로 카운트를 롤백하고, 마지막 에러를 재던져 **502로 통일**했다.

### 11. Gemini rate limit에 체험이 그대로 막혔다

무료 티어라 분당 한도에 자주 걸렸다.

→ `GEMINI_FLASH → GROQ_LLAMA_70B` 폴백. 실제로 성공한 모델을 usage 로그와 claim 레코드에 남겨
   나중에 "어떤 모델로 만든 리뷰인지"를 추적할 수 있게 했다.

### 12. Groq(llama)가 한국어에 다른 언어를 섞었다

같은 프롬프트인데 Gemini는 정상이고 Groq만 언어가 섞였다.

→ 한국어 강제 규칙을 `prompt_groq_language.txt`로 분리하고 **Groq 호출에만** 프롬프트 끝에 덧붙였다.
   Gemini와 로그인 리뷰 품질은 건드리지 않는다.

### 13. 사용량 로그 저장 실패가 리뷰 응답을 죽였다

통계용 `ai_usage_logs` 저장이 실패하면 리뷰 결과 자체가 에러로 돌아갔다.

→ 회계 적재는 try/catch로 삼킨다. 통계는 부차적이고 리뷰가 본질이다.
   AI가 스키마 밖 category/severity를 준 이슈도 그 건만 건너뛰고 나머지는 저장한다.

### 14. 체험 횟수가 리셋되지 않고 계속 밀렸다

리뷰마다 TTL을 갱신하니 3회를 쓴 뒤에도 만료가 계속 미뤄졌다.

→ 카운터가 1일 때만 TTL 24시간을 설정한다. 첫 리뷰 시점부터 정확히 24시간 뒤 리셋된다.

## 약관 · 회원

### 15. 재동의 조회에서 `NonUniqueResultException`

`user_agreements`에 같은 (userId, termId) 행이 중복으로 들어간 데이터가 있어
단건 조회가 예외를 던졌다.

→ 동의/재동의/철회 조회를 `findFirst...`로 바꿨다. 중복 행이 있어도 동작하고,
   판정에 쓰이는 값은 같으므로 결과도 달라지지 않는다.

### 16. `Collectors.toMap`이 NPE를 냈다

`agreedVersion`이 null인 레거시 동의 행이 있었고, `Collectors.toMap`은 **null 값을 담지 못한다**.

→ `HashMap`에 수동 put하며 null은 스킵. 키가 없으면 `get()`이 null이라 재동의 대상 판정 결과는 동일하다.

### 17. 탈퇴 시점 컬럼이 없어 30일 배치를 못 만들 것 같았다

`withdrawnAt` 컬럼이 없었다. 컬럼 추가 + 기존 탈퇴자 backfill을 하려다가,
`withdraw()`가 `@PreUpdate`로 `updatedAt`을 찍고 `WITHDRAWN` 이후에는 수정 경로가 없다는 걸 확인했다.

→ `updatedAt`을 탈퇴 시점으로 사용. 스키마 변경도 backfill도 없이 기존 탈퇴자까지 대상에 들어간다.

### 18. 회원 하드삭제가 다른 테이블을 깨뜨릴까 걱정됐다

→ 다른 테이블의 `user_id`가 FK 제약 없는 단순 `Long` 컬럼임을 먼저 확인했다. 그래서 하드삭제가 안전하다.
   대신 되돌릴 수 없으므로 `SUSPENDED`/`WITHDRAWN`만 허용하고 본인 삭제를 막았다.

## 관리자 · 외부 API

### 19. 벤더 Admin API에서 429가 떴다

AI 사용량 화면 한 번 열면 (토큰+비용) × 2벤더 × 페이지네이션으로 최대 ~96콜이 나간다.
관리자가 새로고침 몇 번 하면 rate limit에 걸렸다.

→ 같은 `(from, to)` 결과를 10분간 인메모리 캐시. 단, **빈 결과는 캐시하지 않는다**.
   일시적 429가 10분간 고정되면 안 되기 때문이다. 엔트리 상한 64개.
   (단일 컨테이너 전제. 다중 인스턴스로 가면 Redis로 승격이 필요하다.)

### 20. Anthropic 토큰 합계가 0으로 나왔다

`input_tokens` 필드만 읽고 있었는데, Anthropic은 캐시 관련 토큰을
`cache_creation_input_tokens`, `cache_read_input_tokens` 등으로 쪼개 보고한다.

→ `*input_tokens` / `*output_tokens`로 끝나는 숫자 리프를 전부 합산하는 방식으로 바꿨다.
   벤더가 필드를 더 쪼개도 집계가 0이 되지 않는다.

### 21. 비용 숫자가 100배 차이났다

OpenAI는 달러, Anthropic은 **센트**("lowest units")로 보고한다.

→ 벤더별 divisor(1 / 100)를 두고 USD로 정규화했다.

### 22. api_key 필터가 통째로 무시됐다

리스트 쿼리 파라미터를 그냥 넘기니 `api_key_ids[]=[a, b]` 한 덩어리로 인코딩됐다.

→ 같은 이름으로 여러 번 붙도록(`api_key_ids[]=a&api_key_ids[]=b`) 컬렉션을 분해해서 넘긴다.
   Anthropic `cost_report`는 api_key 필터를 아예 안 받아서(비용은 워크스페이스 단위로만 갈라짐),
   숫자를 대시보드와 정확히 맞추려면 워크스페이스를 분리하고 `group_by=workspace_id`로 쪼개 합산해야 했다.

### 23. 한 벤더가 죽으면 관리자 화면 전체가 죽었다

키 오류(401)·권한 부족(403)·벤더 장애가 그대로 500으로 올라왔다.

→ 벤더별로 `safeFetch`로 감싸 실패를 격리하고 빈 리스트를 반환한다. 나머지 벤더 결과는 그대로 나온다.
   페이지네이션도 `MAX_PAGES`(24)로 상한을 둬서 커서가 끝나지 않는 상황에 무한 루프가 되지 않게 했다.

### 24. 회원 목록·사용량 조회에서 N+1

회원마다 플랜명을 조회하고, 사용량 로그마다 닉네임을 조회하고 있었다.

→ 플랜은 한 번 전량 로드해 `Map<Long, String>`으로, 닉네임은 `findAllById`(IN) 한 번으로 해결.

### 25. 공지·회원 목록이 계속 길어졌다

`notices`는 자동 삭제가 없어 계속 쌓이고, 회원 목록은 전량을 한 번에 렌더했다.

→ 공용 `Pager` 컴포넌트를 만들어 재사용(공지 10건, 회원 15건). 한 페이지면 자동 숨김,
   목록이 줄면 마지막 페이지로 접힌다. 클라이언트 페이징이라 백엔드 변경은 없다.

---

## 남은 것

- 벤더 사용량 캐시는 인메모리다. 인스턴스를 늘리면 Redis로 옮겨야 한다.
- 결제 실패 시 즉시 FREE 강등이다. 실무라면 재시도(dunning) 단계가 필요하다.
- 벤더 실사용량은 Admin 키를 넣기 전까지 빈 배열을 반환한다(화면은 정상 동작).
