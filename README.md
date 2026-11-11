# COGI

> 코드를 붙여넣거나 GitHub PR을 연동하면 AI가 리뷰해 주는 코드 리뷰 서비스입니다.

![Java](https://img.shields.io/badge/Java-25-007396?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.1-6DB33F?logo=springboot&logoColor=white)
![Spring Security](https://img.shields.io/badge/Spring_Security-6DB33F?logo=springsecurity&logoColor=white)
![JPA](https://img.shields.io/badge/Spring_Data_JPA-59666C?logo=hibernate&logoColor=white)
![MariaDB](https://img.shields.io/badge/MariaDB-003545?logo=mariadb&logoColor=white)
![Redis](https://img.shields.io/badge/Redis-DC382D?logo=redis&logoColor=white)
![Docker](https://img.shields.io/badge/Docker_Compose-2496ED?logo=docker&logoColor=white)

## 소개

`COGI`는 코드를 붙여넣거나 GitHub 저장소·PR을 연동하면 AI가 코드를 리뷰해 주는 서비스입니다. 가입 전에도 체험할 수 있고, 구독 플랜에 따라 리뷰 한도와 사용할 수 있는 AI 모델이 달라집니다.

팀 프로젝트 저장소를 fork한 저장소이며, 제가 맡은 백엔드 작업을 이어서 정리하고 있습니다.

## 담당

**결제 / 구독(Toss 빌링키 정기결제), 비로그인 체험, 관리자 콘솔(회원 관리·공지·AI 사용량 통계), 소셜 로그인 계정 병합, 강의 추천** 백엔드를 담당했습니다.

## 기술 스택

| 구분 | 사용 기술 |
|------|-----------|
| Language | Java 25 |
| Framework | Spring Boot 4.1 (Web, Validation) |
| 인증 | Spring Security, JWT (jjwt 0.12.6), OAuth2 소셜 로그인(GitHub·카카오), TOTP 2FA |
| 데이터 | Spring Data JPA, MariaDB, Redis |
| 결제 | Toss Payments (빌링키 정기결제) |
| AI 연동 | Gemini · Groq · Claude · OpenAI (플랜별 허용 모델) |
| 코드 분석 | JavaParser |
| 스케줄링 | Spring Scheduling, ShedLock (Redis) |
| 프론트엔드 | React 18, TypeScript, Vite |
| 배포 | Docker Compose, Caddy (HTTPS) |
| 테스트 | JUnit 5, k6 |

## 주요 기능

- **JWT 인증 / 인가** - 요청별 토큰 검증 필터, Stateless 정책, 경로별 접근 제어(`/api/admin/**` ROLE_ADMIN)
- **소셜 로그인 + 계정 병합** - OAuth2(카카오·GitHub), 같은 이메일의 기존 계정과 병합(이메일 인증 시에만 병합)
- **AI 코드 리뷰** - 결과를 요약 / 이슈(카테고리·심각도) 구조로 파싱. 비로그인 체험은 Gemini 우선 호출, 실패 시 Groq로 폴백
- **비로그인 체험** - `guest_token` 쿠키 기준 24시간 3회 제한, 초과 시 403. 회원가입 시 체험 리뷰를 계정으로 이관(claim)
- **구독 / 결제** - Toss 빌링키 기반 정기결제, 매일 자정 만료 구독을 건별로 청구
- **관리자 콘솔** - 회원 관리, 전체 공지 / 인앱 알림, 긴급 공지 메일, AI 사용량 통계
- **약관 관리** - 약관 버전 기록 및 개정 시 재동의
- **GitHub 연동** - 저장소 / PR 리뷰, Webhook 수신(서명 검증)
- **학습 / 강의 추천** - 리뷰에서 드러난 약점 기반 추천

## 설계 판단

제가 맡은 영역에서 무엇을 고르고 무엇을 감수했는지 정리했습니다.

### 1. 정기결제 응답이 유실되면 승인 여부를 알 수 없다

- **문제** - 토스 결제 요청이 타임아웃되면 승인됐는지 알 수 없습니다. 바로 실패로 처리하면 결제된 사용자가 FREE로 내려가고, 새 주문번호로 다시 청구하면 같은 회차가 두 번 결제될 수 있습니다.
- **선택** - 토스를 호출하기 전에 `payment_attempts`에 시도 기록(REQUESTED)을 먼저 커밋하고, 토스 호출은 트랜잭션 밖에서 합니다. 주문번호는 `SUB-{구독ID}-{만료일}`로 만들어 같은 회차는 항상 같은 값이 됩니다. 결과가 확정되지 않은 시도는 10초 주기 복구 스케줄러가 토스 주문 조회로 확정합니다.
- **강등 기준** - 카드 쪽 사유가 분명한 거절 코드 12종만 FREE로 내립니다. 인증 키 오류처럼 서버 쪽 문제일 수 있는 4xx는 강등하지 않고 수동 확인으로 넘깁니다.
- **감수한 것** - 24시간 안에 확정되지 않은 시도는 `MANUAL_REVIEW`로 두고 사람이 확인해야 합니다. 최초 결제와 플랜 변경은 아직 UUID 주문번호를 쓰고 있어 이 방식은 정기결제에만 적용돼 있습니다.
- **코드** - [`BillingProcessor`](backend/src/main/java/idu/sba/backend/domain/payment/scheduler/BillingProcessor.java), [`BillingScheduler`](backend/src/main/java/idu/sba/backend/domain/payment/scheduler/BillingScheduler.java), [`PaymentAttempt`](backend/src/main/java/idu/sba/backend/domain/payment/entity/PaymentAttempt.java)
- **검증** - [`BillingRecoveryTest`](backend/src/test/java/idu/sba/backend/domain/payment/scheduler/BillingRecoveryTest.java)에서 장애 시나리오 8개를 확인하고, 승인 후 응답이 유실되는 상황 100건에서 잘못된 강등·이중 청구·미확정이 0건인지 단언합니다. 이 테스트는 토스와 저장소를 테스트용 구현으로 바꿔 실행합니다.

### 2. 비로그인 체험 횟수가 동시 요청에서 어긋난다

- **문제** - 기존에는 `INCR` 뒤에 `EXPIRE`를 따로 호출했고, 한도를 넘은 요청도 카운터를 올렸습니다. 그 결과 실패한 요청의 횟수를 돌려줘도 체험을 다시 쓸 수 없었습니다. 재현 기록은 [`baseline.md`](backend/experiments/guest-limit/baseline.md)에 있습니다.
- **선택** - 검사·증가·만료 설정을 Lua 스크립트 하나로 처리합니다. 한도를 넘은 요청은 카운터를 올리지 않고, AI 모델이 모두 실패했을 때는 같은 기간(window)일 때만 횟수를 돌려줍니다.
- **저장소 전환** - `guest.quota.store` 값으로 Redis(기본)와 DB 구현을 바꿀 수 있습니다. 저장소에 연결할 수 없으면 횟수를 셀 수 없으므로 체험만 503으로 막습니다.
- **코드** - [`RedisGuestQuotaStore`](backend/src/main/java/idu/sba/backend/domain/guest/service/RedisGuestQuotaStore.java), [`DbGuestQuotaStore`](backend/src/main/java/idu/sba/backend/domain/guest/service/DbGuestQuotaStore.java), [`GuestReviewService`](backend/src/main/java/idu/sba/backend/domain/guest/service/GuestReviewService.java)
- **검증** - [`GuestQuotaStoreTest`](backend/src/test/java/idu/sba/backend/domain/guest/service/GuestQuotaStoreTest.java)에서 동시 요청 40건 중 3건만 통과하는지, 횟수 반환이 기간을 늘리지 않는지를 Redis와 DB 양쪽으로 확인합니다. 부하 측정 스크립트는 [`quota-load.js`](backend/experiments/guest-limit/quota-load.js)입니다.

### 3. 긴급 공지 메일이 서버 재시작에 사라진다

- **문제** - 메모리 안에서 비동기로 발송하면 서버가 재시작될 때 아직 보내지 않은 메일이 사라집니다.
- **선택** - 공지를 저장하는 트랜잭션 안에서 수신자별 작업을 `notice_mail_tasks`에 함께 기록합니다. 워커가 5초마다 대기 작업을 가져오고, 조건부 UPDATE로 5분 동안 작업을 점유한 뒤 발송합니다. 실패하면 최대 3회까지 간격을 늘려 다시 시도합니다.
- **감수한 것** - 메일 서버가 수락한 뒤 발송 완료 기록이 실패하면 같은 메일이 한 번 더 나갈 수 있습니다. 메시지 브로커는 현재 구성에 없어 도입하지 않았습니다.
- **코드** - [`NoticeMailTaskWriter`](backend/src/main/java/idu/sba/backend/domain/admin/service/NoticeMailTaskWriter.java), [`NoticeMailWorker`](backend/src/main/java/idu/sba/backend/domain/admin/service/NoticeMailWorker.java), [`NoticeMailTaskRepository`](backend/src/main/java/idu/sba/backend/domain/admin/repository/NoticeMailTaskRepository.java)
- **검증** - [`NoticeMailWorkerTest`](backend/src/test/java/idu/sba/backend/domain/admin/service/NoticeMailWorkerTest.java), [`NoticeMailTaskRepositoryDbTest`](backend/src/test/java/idu/sba/backend/domain/admin/service/NoticeMailTaskRepositoryDbTest.java)

### 4. 스케줄러 중복 실행

정기결제, 주간 리포트, 알림 정리, 탈퇴 회원 정리 스케줄러에는 Redis 기반 ShedLock을 걸어 인스턴스가 여러 대여도 한 번만 실행되게 했습니다. 이미 쓰고 있는 Redis를 락 저장소로 재사용합니다.

## 디렉토리 구조

```
.
├─ backend                      # Spring Boot API 서버
│  ├─ src/main/java/idu/sba/backend
│  │  ├─ domain
│  │  │  ├─ auth          # 인증 (로그인, JWT 발급, 소셜 로그인)
│  │  │  ├─ user          # 사용자
│  │  │  ├─ review        # AI 코드 리뷰, 사용량 로그
│  │  │  ├─ guest         # 비로그인 체험 (횟수 저장소, claim)
│  │  │  ├─ payment       # 구독 / 결제 (Toss 빌링키, 정기결제, 복구)
│  │  │  ├─ pr            # GitHub PR 리뷰
│  │  │  ├─ repo          # 저장소 연동
│  │  │  ├─ webhook       # GitHub Webhook 수신
│  │  │  ├─ admin         # 관리자 콘솔 (회원·공지·공지 메일·통계)
│  │  │  ├─ notification  # 인앱 알림
│  │  │  ├─ learning      # 학습 / 강의 추천
│  │  │  ├─ terms         # 약관 및 재동의
│  │  │  └─ ...           # inquiry, growth, retention, context
│  │  └─ global           # 공통 (시큐리티, 예외 처리, AI 클라이언트, 메일 등)
│  └─ experiments         # 측정 스크립트와 재현 기록
├─ frontend                     # React + Vite
├─ docker-compose.yml           # MariaDB, Redis, backend, frontend, Caddy
└─ Caddyfile                    # 운영 서버 HTTPS 프록시
```

## 실행 방법

`backend/src/main/resources/application.properties`와 `.env`는 저장소에 포함하지 않았습니다. DB·Redis 연결 정보와 JWT, OAuth2, Toss, AI, 메일 키를 직접 넣어야 합니다.

```bash
git clone https://github.com/Jihwan0210/COGI.git
cd COGI

# .env 에 DB_PASSWORD 와 각종 키 작성 후
docker compose up -d --build
# http://localhost:8088
```

백엔드만 실행할 때는 MariaDB와 Redis를 띄운 뒤 아래 명령을 사용합니다.

```bash
cd backend
./gradlew bootRun
```

## 테스트

```bash
cd backend
./gradlew test
```

- 서비스 단위 테스트는 외부 의존 없이 실행됩니다.
- `GuestQuotaStoreTest`, `GuestReviewHttpTest`는 Redis(`127.0.0.1:16379`)와 MariaDB(`127.0.0.1:13307`, `guest_quota_test`)가 필요합니다.
- `BillingRecoveryDbTest`는 `COGI_TEST_DB_USERNAME` 환경변수가 없으면 건너뜁니다.
- `NoticeMailTaskRepositoryDbTest`, `NoticeMailTradeoffDbTest`는 각각 `COGI_NOTICE_DB_TEST`, `COGI_NOTICE_STAGE` 환경변수가 있을 때만 실행됩니다.
- 스프링 컨텍스트 전체를 띄우는 테스트는 `application.properties`가 있어야 합니다.
