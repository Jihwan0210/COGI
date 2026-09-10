package idu.sba.backend.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

//    공통
    INVALID_INPUT(HttpStatus.BAD_REQUEST,"입력값이 올바르지 않습니다."),

//    회원가입/인증
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT,"이미 가입된 이메일 입니다."), //409
    PASSWORD_MISMATCH(HttpStatus.BAD_REQUEST,"비밀번호가 일치하지 않습니다."),
    EMAIL_NOT_VERIFIED(HttpStatus.BAD_REQUEST,"이메일 인증이 완료되지 않았습니다."),
    CODE_MISMATCH(HttpStatus.BAD_REQUEST,"인증 코드가 일치하지 않습니다."),
    CODE_EXPIRED(HttpStatus.GONE,"인증 코드가 만료되었습니다."),
    CODE_LOCKED(HttpStatus.LOCKED,"인증 시도가 일시 정지 되었습니다. 잠시 후 다시 시도해주세요."),// 423
    CODE_SEND_COOLDOWN(HttpStatus.TOO_MANY_REQUESTS,"잠시 후 다시 시도해주세요.(60초)"), // 429
    REQUIRED_TERMS_NOT_AGREED(HttpStatus.BAD_REQUEST,"필수 약관에 모두 동의해야 합니다."),
    CODE_LOCKED_30M(HttpStatus.LOCKED, "인증 3회 실패로 30분간 정지됐어요. 30분 뒤 다시 시도해주세요."),
    CODE_LOCKED_24H(HttpStatus.LOCKED, "반복 실패로 24시간 정지됐어요. 내일 다시 시도해주세요."),


    //로그인
    LOGIN_FAILED(HttpStatus.UNAUTHORIZED,"이메일 또는 비밀번호가 일치하지 않습니다."), //401
    ACCOUNT_LOCKED(HttpStatus.LOCKED,"계정이 잠겼습니다. 비밀번호를 재설정해주세요"), //423
    ACCOUNT_SUSPENDED(HttpStatus.FORBIDDEN,"정지된 계정입니다."), //403


    //비밀번호 재설정
    RESET_TOKEN_INVALID(HttpStatus.BAD_REQUEST, "유효하지 않은 재설정 토큰입니다."),        // 400
    RESET_TOKEN_EXPIRED(HttpStatus.GONE, "재설정 토큰이 만료되었습니다."),                  // 410
    RESET_TOKEN_USED(HttpStatus.BAD_REQUEST, "이미 사용된 재설정 토큰입니다."),             // 400
    SAME_AS_OLD_PASSWORD(HttpStatus.BAD_REQUEST, "기존 비밀번호와 다른 비밀번호를 사용해주세요."), // 400
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 계정입니다."), // 404

    //2단계 인증
    TOTP_ALREADY_ENABLED(HttpStatus.CONFLICT, "이미 2차 인증이 켜져 있습니다."), // 켜진 상태에서 재설정 시도
    TOTP_NOT_INITIATED(HttpStatus.BAD_REQUEST, "먼저 OTP 설정을 진행해주세요."), // setup 안 하고 enable 시도
    TOTP_CODE_INVALID(HttpStatus.BAD_REQUEST, "인증 코드가 올바르지 않습니다."),   // 코드 불일치
    TOTP_QR_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "QR 생성에 실패했습니다."),


    //깃허브 연동
    GITHUB_LINK_FAILED(HttpStatus.BAD_REQUEST, "GitHub 연동에 실패했습니다."),
    GITHUB_ALREADY_LINKED(HttpStatus.CONFLICT, "이미 다른 계정에 연결된 GitHub입니다."),

    //AST 컨텍스트 추출
    AST_PARSE_ERROR(HttpStatus.BAD_REQUEST,"코드를 파싱할 수 없습니다."),

    //결제/구독
    SUBSCRIPTION_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 구독입니다."),              // 404
    PLAN_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 플랜입니다."),                     // 404
    PAYMENT_METHOD_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 결제수단입니다."),        // 404
    ALREADY_SUBSCRIBED(HttpStatus.CONFLICT, "이미 구독 중입니다."),                       // 409
    SUBSCRIPTION_FORBIDDEN(HttpStatus.FORBIDDEN, "본인의 구독만 접근할 수 있습니다."),      // 403
    PAYMENT_METHOD_FORBIDDEN(HttpStatus.FORBIDDEN, "본인의 결제수단만 사용할 수 있습니다."), // 403
    DOWNGRADE_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "다운그레이드는 지원하지 않습니다. 해지 후 다시 구독해주세요."), // 400
    INVALID_TERMS(HttpStatus.BAD_REQUEST, "유효하지 않은 약관이 포함되어 있습니다."),
    TERM_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 약관입니다."),

    //레포 팀원 초대(REV-006)
    REPO_NOT_FOUND(HttpStatus.NOT_FOUND,"레포지토리를 찾을 수 없습니다."),
    NOT_REPO_MEMBER(HttpStatus.FORBIDDEN,"레포 팀원이 아닙니다."),
    INSUFFICIENT_REPO_PERMISSION(HttpStatus.FORBIDDEN,"해당 작업을 수행할 권한이 없습니다."),
    INVITATION_NOT_FOUND(HttpStatus.NOT_FOUND,"초대를 찾을 수 없습니다."),
    INVITATION_ALREADY_RESPONDED(HttpStatus.CONFLICT,"이미 응답한 초대입니다."),
    ALREADY_REPO_MEMBER(HttpStatus.CONFLICT,"이미 레포에 소속된 사용자입니다."),
    INVITATION_ALREADY_SENT(HttpStatus.CONFLICT,"이미 초대를 보냈어요. 수락을 기다려주세요."),
    OWNER_CANNOT_LEAVE(HttpStatus.CONFLICT, "팀장은 바로 나갈 수 없어요. 먼저 다른 팀원에게 팀장을 위임해주세요."),
    CANNOT_ACT_ON_SELF(HttpStatus.BAD_REQUEST, "본인에게는 사용할 수 없는 기능이에요."),
    INVITATION_GITHUB_USERNAME_MISMATCH(HttpStatus.FORBIDDEN,"초대받은 GitHub 계정으로만 응답할 수 있습니다."),
    GITHUB_USER_NOT_FOUND(HttpStatus.BAD_REQUEST,"GitHub를 연동하지 않았거나 사이트에 가입하지 않은 사용자입니다."),
    INVITATION_EXPIRED(HttpStatus.GONE,"초대 링크가 만료되었습니다. 팀장에게 재초대를 요청해주세요."),

    //GitHub 레포 연동(API-022/023)
    GITHUB_NOT_LINKED(HttpStatus.BAD_REQUEST,"GitHub 계정이 연동되어 있지 않습니다."),
    GITHUB_API_ERROR(HttpStatus.BAD_GATEWAY,"GitHub API 호출에 실패했습니다."),
    GITHUB_REPO_NOT_FOUND(HttpStatus.NOT_FOUND,"GitHub에서 해당 레포지토리를 찾을 수 없습니다."),
    REPO_ALREADY_LINKED(HttpStatus.CONFLICT,"이미 연동된 레포지토리입니다."),

    //AI 코드 리뷰 생성(REV-003)
    CREDIT_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS,"오늘의 리뷰 요청 한도를 모두 사용했습니다."), //429
    MODEL_NOT_ALLOWED_FOR_PLAN(HttpStatus.FORBIDDEN,"현재 요금제에서 선택할 수 없는 모델입니다."),
    AI_MODEL_CALL_FAILED(HttpStatus.BAD_GATEWAY,"AI 모델 호출에 실패했습니다."),
    GUEST_TRIAL_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE,"일시적으로 체험을 이용할 수 없습니다. 잠시 후 다시 시도해주세요."), // 503 (Redis 장애 시 fail-closed)

    //PR 리뷰(API-024/025)
    PR_NOT_FOUND(HttpStatus.NOT_FOUND,"존재하지 않는 PR입니다."),

    //이슈 확정(API-031 [설계 추론])
    ISSUE_NOT_FOUND(HttpStatus.NOT_FOUND,"존재하지 않는 이슈입니다."),
    ISSUE_ACCESS_DENIED(HttpStatus.FORBIDDEN,"본인의 리뷰 이슈만 처리할 수 있습니다."),

    //이슈 승인 흐름(RDB-003 [설계 추론])
    NOT_REPO_OWNER(HttpStatus.FORBIDDEN,"팀장만 처리할 수 있습니다."),

    //리뷰 히스토리 [설계 추론]
    REVIEW_NOT_FOUND(HttpStatus.NOT_FOUND,"존재하지 않는 리뷰입니다."),
    REVIEW_ACCESS_DENIED(HttpStatus.FORBIDDEN,"본인의 리뷰만 조회할 수 있습니다."),

    //학습카드(LRN-002)
    LEARNING_CARD_NOT_FOUND(HttpStatus.NOT_FOUND,"존재하지 않는 학습카드입니다."),
    LEARNING_CARD_ACCESS_DENIED(HttpStatus.FORBIDDEN,"본인의 학습카드만 접근할 수 있습니다."),
    LEARNING_QUIZ_NOT_FOUND(HttpStatus.NOT_FOUND,"존재하지 않는 퀴즈입니다."),
    WEAKNESS_STATS_EMPTY(HttpStatus.BAD_REQUEST,"아직 약점 데이터가 없어요. 코드 리뷰를 먼저 받아보세요."),

    //관리자 본인 권한수정
    CANNOT_CHANGE_OWN_ROLE(HttpStatus.FORBIDDEN, "본인 권한은 변경할 수 없습니다."),
    //관리자 본인 상태수정
    CANNOT_STATUS_OWN_ROLE(HttpStatus.FORBIDDEN, "본인 상태는 수정할 수 없습니다."),
    //관리자 회원 삭제 — 정지 상태를 거쳐야만 삭제 가능(오조작 방지)
    MEMBER_NOT_DELETABLE(HttpStatus.CONFLICT, "정지되거나 탈퇴한 계정만 삭제할 수 있습니다."),

    //회원탈퇴
    ACCOUNT_WITHDRAWN(HttpStatus.FORBIDDEN, "탈퇴한 계정입니다."),
    INVALID_WITHDRAW_CONFIRM(HttpStatus.BAD_REQUEST, "탈퇴 확인 문구가 올바르지 않습니다.");

    private final HttpStatus status; // 이 에러가 나갈 때의 HTTP 상태코드
    private final String message; // 사용자에게 보여줄 메시지

}
