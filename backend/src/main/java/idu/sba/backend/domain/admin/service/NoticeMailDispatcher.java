package idu.sba.backend.domain.admin.service;

import idu.sba.backend.domain.admin.entity.Notice;
import idu.sba.backend.domain.admin.repository.NoticeRepository;
import idu.sba.backend.global.mail.HtmlMailSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * [트레이드오프] 전체 공지 메일이 응답을 붙잡는 문제
 * 상황 : 전체 공지 메일을 요청 스레드에서 동기 발송하면, 수신자 수만큼 응답이 지연된다.
 * 선택 : @Async(mailExecutor)로 백그라운드에 넘기고, 건별 실패는 개별 try/catch로 로깅한 뒤 성공/실패 수를 Notice에 기록한다.
 * 결과 : 메일 발송이 응답을 지연시키지 않고, 한 건 실패가 전체 발송을 멈추지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NoticeMailDispatcher {

    private final NoticeRepository noticeRepository;
    private final HtmlMailSender htmlMailSender;

    @Async("mailExecutor")
    public void dispatch(Long noticeId, String subject, String innerHtml, List<String> emails) {
        int success = 0, fail = 0;
        for (String email : emails) {
            try {
                htmlMailSender.send(email, "[COGI 공지] " + subject, innerHtml);
                success++;
            } catch (Exception e) {
                fail++;
                log.warn("공지 발송 실패: {} - {}", email, e.getMessage());
            }
        }
        Notice notice = noticeRepository.findById(noticeId).orElse(null);
        if (notice == null) {
            log.warn("발송 결과 기록 실패 - notice가 없음 id={}", noticeId);
            return;
        }
        notice.markSent(success, fail);
        noticeRepository.save(notice);
        log.info("공지 발송 완료 - id={}, 성공={}, 실패={}", noticeId, success, fail);
    }
}
