package idu.sba.backend.domain.admin.service;

import idu.sba.backend.domain.admin.dto.AdminAiUsageResponseDTO;
import idu.sba.backend.domain.review.entity.AiUsageLog;
import idu.sba.backend.domain.review.repository.AiUsageLogRepository;
import idu.sba.backend.domain.user.entity.User;
import idu.sba.backend.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminUsageServiceImpl implements AdminUsageService{

    private final AiUsageLogRepository aiUsageLogRepository;
    private final UserRepository userRepository;


    /**
     * [트레이드오프] 관리자 사용량 통계의 N+1
     * 상황 : 사용량 로그를 DTO로 변환할 때 로그마다 닉네임을 조회하면, 회원 수만큼 쿼리가 따라붙는다(N+1).
     * 선택 : 로그에서 userId를 distinct로 모아 findAllById(IN 조회) 한 번으로 닉네임 맵을 채우고, 변환은 맵에서 꺼낸다.
     * 결과 : 회원 수와 무관하게 닉네임 조회가 1회로 고정된다.
     */
    @Override
    @Transactional(readOnly = true)
    public List<AdminAiUsageResponseDTO> getAiUsage(LocalDate from, LocalDate to) {
        // from 00:00:00 ~ to 23:59:59.999 (to 당일 끝까지 포함)
        var start = from.atStartOfDay();
        var end = to.atTime(LocalTime.MAX);
        var logs = aiUsageLogRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(start, end);

        // userId → nickname 을 한 번의 IN 조회로 채운다 (로그마다 조회하면 N+1)
        var userIds = logs.stream().map(AiUsageLog::getUserId).filter(Objects::nonNull).distinct().toList();
        Map<Long, String> nickById = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getNickname));

        return logs.stream()
                .map(l -> AdminAiUsageResponseDTO.of(l, nickById.get(l.getUserId())))
                .toList();
    }
}