# COGI · Backend

> 코드를 붙여넣거나 GitHub PR을 연동하면 AI가 자동으로 리뷰해 주는 AI 코드 리뷰 서비스의 백엔드 API 서버입니다.

![Java](https://img.shields.io/badge/Java-25-007396?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.1-6DB33F?logo=springboot&logoColor=white)
![Spring Security](https://img.shields.io/badge/Spring_Security-6DB33F?logo=springsecurity&logoColor=white)
![JPA](https://img.shields.io/badge/Spring_Data_JPA-59666C?logo=hibernate&logoColor=white)
![JWT](https://img.shields.io/badge/JWT-000000?logo=jsonwebtokens&logoColor=white)
![MariaDB](https://img.shields.io/badge/MariaDB-003545?logo=mariadb&logoColor=white)
![Redis](https://img.shields.io/badge/Redis-DC382D?logo=redis&logoColor=white)
![OAuth2](https://img.shields.io/badge/OAuth2-EB5424?logo=auth0&logoColor=white)

## 소개

`COGI`는 코드를 붙여넣거나 GitHub 저장소·PR을 연동하면 AI가 자동으로 코드를 리뷰해 주는 서비스입니다. 가입 전에도 체험할 수 있고, 구독 플랜에 따라 리뷰 한도와 사용 가능한 AI 모델이 달라집니다. 이 저장소는 그중 **백엔드 API 서버**입니다.

## 기술 스택

| 구분 | 사용 기술 |
|------|-----------|
| Language | Java 25 |
| Framework | Spring Boot 4.1 (Web, Validation) |
| 인증 | Spring Security, JWT (jjwt 0.12.6), OAuth2 소셜 로그인, TOTP 2FA |
| 데이터 | Spring Data JPA, MariaDB, Redis |
| 결제 | Toss Payments (빌링키 정기결제) |
| AI 연동 | Gemini · Groq (폴백 이중화) |
| 코드 분석 | JavaParser |
| 메일 | Spring Boot Mail (@Async 발송) |
| 테스트 | JUnit |

## 주요 기능

- **JWT 인증 / 인가** - 요청별 토큰 검증 필터, Stateless 정책, 경로별 접근 제어(`/api/admin/**` ROLE_ADMIN)
- **소셜 로그인 + 계정 병합** - OAuth2(카카오·GitHub), 같은 이메일의 기존 계정과 안전하게 병합(이메일 인증 시에만 병합해 계정 탈취 방지)
- **AI 코드 리뷰** - Gemini 우선 호출, 실패 시 Groq로 폴백. 결과를 요약 / 이슈(카테고리·심각도) 구조로 파싱
- **비로그인 체험** - `guest_token` 쿠키 + Redis 카운터(TTL 24h)로 게스트당 24시간 3회 제한, 초과 시 403. 회원가입 시 체험 리뷰를 계정으로 이관(claim)
- **구독 / 결제** - Toss 빌링키 기반 정기결제, 자정 배치가 만료 구독을 건별 트랜잭션으로 격리 청구(실패 건만 FREE 강등)
- **관리자 콘솔** - 회원 관리(페이지네이션·탈퇴 회원 삭제), 전체 공지 / 인앱 알림, AI 사용량 통계(N+1 제거)
- **약관 관리** - 약관 버전 기록 및 개정 시 재동의 게이트
- **GitHub 연동** - 저장소 / PR 리뷰, Webhook 수신(서명 검증)
- **학습 / 강의 추천** - 리뷰에서 드러난 약점 기반 큐레이션 추천
- **공통 계층** - 전역 예외 처리, 요청 레이트 리밋, 공통 응답 포맷

## 디렉토리 구조

```
src/main/java/idu/sba/backend
├─ domain
│  ├─ auth          # 인증 (로그인, JWT 발급, 소셜 로그인)
│  ├─ user          # 사용자
│  ├─ review        # AI 코드 리뷰, 사용량 로그
│  ├─ guest         # 비로그인 체험 (Redis 카운터, claim)
│  ├─ payment       # 구독 / 결제 (Toss 빌링키, 정기결제 배치)
│  ├─ pr            # GitHub PR 리뷰
│  ├─ repo          # 저장소 연동
│  ├─ webhook       # GitHub Webhook 수신
│  ├─ admin         # 관리자 콘솔 (회원·공지·통계)
│  ├─ notification  # 인앱 / 메일 알림
│  ├─ learning      # 학습 / 강의 추천
│  ├─ terms         # 약관 및 재동의
│  └─ ...           # inquiry, growth, retention, context
└─ global           # 공통 (시큐리티, 예외 처리, AI 클라이언트, 메일 등)
```

## 실행 방법

```bash
# 1. 저장소 클론
git clone https://github.com/Jihwan0210/COGI.git

# 2. application.properties 에 MariaDB / Redis 연결 정보와
#    JWT · OAuth2 · Toss · AI(Gemini·Groq) 키 설정

# 3. 실행
./gradlew bootRun
```

## 담당

팀 프로젝트에서 **결제 / 구독(Toss 빌링키 정기결제), 비로그인 체험(Redis 비용 통제), 관리자 콘솔(회원 관리·공지·AI 사용량 통계), 소셜 로그인 계정 병합, 강의 추천** 백엔드를 담당했습니다.
