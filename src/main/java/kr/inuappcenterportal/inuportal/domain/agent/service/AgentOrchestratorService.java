package kr.inuappcenterportal.inuportal.domain.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.inuappcenterportal.inuportal.domain.agent.dto.AgentDto;
import kr.inuappcenterportal.inuportal.domain.agent.model.PortalMenuMap;
import kr.inuappcenterportal.inuportal.domain.agent.repository.PortalMenuMapRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class AgentOrchestratorService {

    private final PortalMenuMapRepository menuMapRepository;
    private final VllmClientService vllmClientService;
    private final ObjectMapper objectMapper;

    /**
     * 사용자의 프롬프트를 수령하여, 로컬 RAG MySQL 데이터베이스로부터 
     * 가장 연관성 높은 넥사크로 타겟 메뉴 정보를 매칭하고 첫 내비게이션 조작 계획을 반환합니다.
     */
    public AgentDto.StartResponse initiateTask(String userPrompt) {
        log.info("에이전트 태스크 개시 요청 수령: {}", userPrompt);

        // 1. 핵심 키워드 매칭을 통한 RAG 메뉴 맵 탐색 (프로그램 'P'만 검색하여 폴더 중복 방지)
        List<PortalMenuMap> matchedMenus = new ArrayList<>();
        String[] coreKeywords = {"성적", "장학", "등록금", "납부", "학적", "신상", "시간표", "강의"};
        
        for (String keyword : coreKeywords) {
            if (userPrompt.contains(keyword)) {
                matchedMenus.addAll(menuMapRepository.searchProgramsByKeyword(keyword));
            }
        }

        // 키워드 분절 매칭 실패 시 전체 문장으로 매치 시도
        if (matchedMenus.isEmpty()) {
            matchedMenus = menuMapRepository.searchProgramsByKeyword(userPrompt);
        }

        // 2. 검색 매칭 결과 분석 및 첫 응답 조립
        if (!matchedMenus.isEmpty()) {
            PortalMenuMap targetMenu = matchedMenus.get(0);
            log.info("RAG 메뉴 매칭 성공 (프로그램 전용): {} (ID: {})", targetMenu.getMenuNm(), targetMenu.getMenuId());

            // 넥사크로 14 전용 메뉴 강제 이동 스크립트 빌드 (Track A)
            String initJs = String.format("if (window.nexacro && nexacro.getApplication()) { nexacro.getApplication().gfn_openMenu('%s'); }", targetMenu.getMenuId());

            return AgentDto.StartResponse.builder()
                    .success(true)
                    .menuId(targetMenu.getMenuId())
                    .pgmId(targetMenu.getPgmId())
                    .menuName(targetMenu.getMenuNm())
                    .initJsCode(initJs)
                    .statusMessage(String.format("인천대 포털 RAG 지도를 대입하여 '%s' 화면으로 실시간 연동을 시작합니다.", targetMenu.getMenuNm()))
                    .build();
        }

        log.warn("사용자 프롬프트에 부합하는 RAG 메뉴를 찾을 수 없습니다: {}", userPrompt);
        return AgentDto.StartResponse.builder()
                .success(false)
                .statusMessage("죄송합니다. 요청하신 행정 서비스의 포털 경로를 RAG 지식 DB에서 탐색하는 데 실패했습니다.")
                .build();
    }

    /**
     * 안드로이드의 실시간 WebView DOM 구조와 최종 상태를 받아 
     * H100 vLLM 서버의 추론 결과를 기반으로 다음 단계의 자바스크립트 조작 코드를 산출합니다.
     */
    public AgentDto.StepResponse evaluateNextStep(AgentDto.StepRequest request) {
        log.info("에이전트 자율 조작 스텝 평가 요청 수령 - URL: {}", request.getCurrentUrl());

        // 타겟 메뉴부터 최상위 부모까지의 전체 경로(Path)를 역추적합니다.
        List<PortalMenuMap> menuPath = new ArrayList<>();
        String topMenuId = "M000758";
        try {
            PortalMenuMap currentMenu = menuMapRepository.findByMenuId(request.getMenuId()).orElse(null);
            while (currentMenu != null) {
                menuPath.add(0, currentMenu); // 리스트의 앞에 추가하여 순서대로 정렬
                if (currentMenu.getUpMenuId() == null || currentMenu.getUpMenuId().equals("M") || (currentMenu.getMenuLvl() != null && currentMenu.getMenuLvl() == 0)) {
                    topMenuId = currentMenu.getMenuId();
                    break;
                }
                currentMenu = menuMapRepository.findByMenuId(currentMenu.getUpMenuId()).orElse(null);
            }
        } catch (Exception e) {
            log.warn("메뉴 경로 역추적 실패: {}", e.getMessage());
        }

        // 경로 정보를 문자열로 요약하여 AI에게 전달
        StringBuilder pathSummary = new StringBuilder();
        for (int i = 0; i < menuPath.size(); i++) {
            PortalMenuMap m = menuPath.get(i);
            pathSummary.append(String.format("[%d] %s (%s)", i, m.getMenuNm(), m.getMenuId()));
            if (i < menuPath.size() - 1) pathSummary.append(" -> ");
        }
        log.info("탐색된 내비게이션 경로: {}", pathSummary);

        // 1. Qwen 2.5 Coder용 정교한 넥사크로 전문 시스템 프롬프트 엔지니어링 수행
        String systemPrompt = """
                You are the intelligent central brain of a web automation agent operating the Incheon National University Portal (built on the TOBESOFT Nexacro 14 platform).
                Your ultimate goal is to fulfill the user's request.
                
                [Target Navigation Path]
                %s
                
                [Context Info]
                - Target Page Name: %s
                - Target Menu ID: %s
                - Target Program ID: %s
                - Target Top-Level Menu ID: %s
                - Current Browser URL: %s
                
                [Nexacro 14 Operational Guidelines]
                - Nexacro is a Single Page Application (SPA) where the URL never changes.
                - To navigate or force open a menu frame, you can run the following global JS:
                  "if (window.nexacro && nexacro.getApplication()) { nexacro.getApplication().gfn_openMenu('%s'); }"
                
                - Once you have successfully navigated to the target screen (the Nexacro view frame has loaded the target program), instead of trying to click complex absolute-positioned UI grids, we can bypass the UI entirely and trigger a high-speed background direct SSV crawling connection from the Kotlin client.
                - Therefore, if the target screen has opened or is loading, you must immediately signal a transition to direct scraping by outputting the following exact JSON response:
                  {
                    "jsCode": "DIRECT_SSV_CRAWL",
                    "statusMessage": "넥사크로 세션 연동 완료! 백그라운드 API 고속 데이터 추출로 전환합니다.",
                    "completed": true
                  }
                
                [Output Constraint]
                You must respond in JSON format ONLY, matching this schema:
                {
                  "jsCode": "string (the JavaScript snippet to execute on the WebView, or 'DIRECT_SSV_CRAWL')",
                  "statusMessage": "string (explanatory progress message in Korean for the user, e.g., '성적 정보 탭을 활성화하는 중입니다...')",
                  "completed": boolean (true if the task is done or direct scraping is triggered)
                }
                """.formatted(
                        pathSummary.toString(),
                        request.getMenuId(), 
                        request.getMenuId(),
                        request.getPgmId(),
                        topMenuId,
                        request.getCurrentUrl(),
                        request.getMenuId()
                );

        String userMessage = String.format(
                "Original User Intent: %s\nCaptured DOM Structure snippet: %s",
                request.getUserPrompt(),
                request.getHtmlContent() != null && request.getHtmlContent().length() > 30000 
                        ? request.getHtmlContent().substring(0, 30000) // 컨텍스트 윈도우 보장용 자르기
                        : request.getHtmlContent()
        );

        // 2. H100 vLLM 추론 서비스 호출
        String inferenceResult = vllmClientService.requestNextStep(systemPrompt, userMessage, topMenuId, request);

        try {
            // 3. 수령한 추론 JSON 결과를 역직렬화하여 반환 DTO로 가공
            return objectMapper.readValue(inferenceResult, AgentDto.StepResponse.class);
        } catch (Exception e) {
            log.error("vLLM 추론 결과 파싱 실패 (JSON 규격 어긋남): {}", e.getMessage());
            return AgentDto.StepResponse.builder()
                    .jsCode("")
                    .statusMessage("⚠️ AI 추론 결과를 디코딩하는 데 실패했습니다.")
                    .completed(true)
                    .build();
        }
    }
}
