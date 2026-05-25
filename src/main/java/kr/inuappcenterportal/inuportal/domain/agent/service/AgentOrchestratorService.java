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

            return AgentDto.StartResponse.builder()
                    .success(true)
                    .menuId(targetMenu.getMenuId())
                    .pgmId(targetMenu.getPgmId())
                    .menuName(targetMenu.getMenuNm())
                    .initJsCode("") // 순수 시각 자율 탐색: 순간이동 JS 100% 소거
                    .statusMessage(String.format("인천대 포털 RAG 단서를 획득했습니다. 목적지인 '%s'를 향해 시각 자율 탐색을 개시합니다.", targetMenu.getMenuNm()))
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
                너는 인천대학교 포털 및 ERP 화면 이미지를 직접 보고 분석하여 자율 조작을 완성하는 시각적 지능(Visual Agent Brain)이자 내비게이터다.
                사용자의 최종 조회 목표(예: 시간표 조회, 성적 조회 등)에 도달하기 위해, 현재 캡처 이미지 상에서 필요한 요소를 찾아 좌표 (x, y)와 action을 반환해야 한다.
                
                [목적지 찾아가기용 RAG 이정표 단서 (RAG Navigation Clues)]
                - 최종 목적지 메뉴명: %s
                - 메뉴 트리 내비게이션 경로: %s
                
                [화면 좌표 기준]
                - 화면의 좌측 상단을 (0, 0), 우측 하단을 (1000, 1000)으로 정의하는 0~1000 범위의 정규화 좌표계를 사용한다.
                - 반드시 사용자가 볼 수 있는 실물 UI 요소(글씨, 입력 박스, 버튼 등)의 정확한 중심점을 짚어야 한다.
                
                [비전 기반 자율 주행 가이드라인]
                1. **메뉴 내비게이션 탐색 (순수 비전 클릭):**
                   - 현재 화면 이미지 상에서 위 RAG 이정표 경로에 해당하는 메뉴 글씨들을 순차적으로 찾아 터치(CLICK)해야 한다.
                   - 예를 들어, 아직 포털 메인 화면이고 넥사크로 트리도 활성화되지 않았다면, 먼저 포털 홈의 '학사행정' 탭 메뉴(탭 글씨 영역)를 찾아 클릭한다.
                   - '학사행정'이 활성화되어 좌측 트리 메뉴판이 뜨면, 이정표 경로상 다음 순서인 '수업' ➔ '시간표관리' 폴더 순서로 글씨 영역의 중심을 이미지 상에서 찾아 정밀하게 클릭해 열어 나간다.
                   - 최종 타겟인 '학생별시간표' 글씨가 이미지 상에 노출되면 해당 글씨 영역을 정확하게 클릭해 최종 시간표 화면으로 접속한다.
                2. **조건 설정 (연도 및 학기):**
                   - 시간표 화면이 완전히 로드되어 이미지 상에 노출되면, 사용자의 타겟 연도(예: 2026년)와 '학년도' 입력란에 쓰인 글씨를 비전으로 확인한다. 다르면 해당 입력란 중심을 CLICK하고, inputText에 "2026"을 채워 TYPE 액션을 쏜다.
                   - '학기' 선택 콤보박스의 텍스트가 타겟 학기(예: 1학기)와 다르면 콤보박스를 CLICK한 후 드롭다운 목록이 렌더링되면 "1학기" 글씨 영역을 정확히 CLICK한다.
                3. **스크롤 조작:**
                   - 화면 하단부에 있어 클릭해야 할 요소가 뷰포트 바깥에 숨겨진 경우, action을 "DRAG"로 지정하고 드래그 시작 좌표(예: 500, 800)와 끝 좌표(예: 500, 200)를 주어 화면을 매끄럽게 아래로 당겨 쓸어 올린다.
                4. **최종 조회 격발:**
                   - 연도와 학기 설정이 이미지 상으로 완벽하게 완료된 것이 확인되면, 화면에 위치한 '조회' 또는 'Search' 버튼의 영역을 시각적으로 찾아 CLICK 액션을 보낸다.
                5. **유연한 자율 종결:**
                   - 최종 조회를 클릭하여 시간표 그리드(표)에 사용자의 시간표 텍스트들이 시각적으로 정상 렌더링되어 로딩된 것이 눈으로 확실히 판별되면, `jsCode`를 "DIRECT_SSV_CRAWL"로 뱉고 `completed`를 true로 반환하여 연동을 영리하게 완결 짓는다.
                
                [Output Constraint]
                반드시 아래의 JSON 포맷으로만 응답해야 하며, 그 외의 텍스트나 설명은 절대 포함해서는 안 된다:
                {
                  "action": "string (CLICK, DRAG, TYPE, NONE)",
                  "x": integer (0 ~ 1000 사이의 x 좌표, CLICK/TYPE/DRAG 시작 시 필요),
                  "y": integer (0 ~ 1000 사이의 y 좌표, CLICK/TYPE/DRAG 시작 시 필요),
                  "endX": integer (DRAG 액션 시 드래그가 끝날 x 좌표),
                  "endY": integer (DRAG 액션 시 드래그가 끝날 y 좌표),
                  "inputText": "string (TYPE 액션 시 입력할 텍스트)",
                  "jsCode": "string (조회 완료 및 크롤링 전환 시 'DIRECT_SSV_CRAWL' 입력, 평소엔 empty string)",
                  "statusMessage": "string (현재 상태와 조작 의도를 한국어로 친절히 묘사, 예: '수업 메뉴를 열기 위해 수업 텍스트 영역을 터치합니다.')",
                  "completed": boolean (그리드에 최종 시간표 데이터가 시각적으로 로드 완료되었을 때만 true)
                }
                """.formatted(
                        menuPath.isEmpty() ? request.getMenuId() : menuPath.get(menuPath.size() - 1).getMenuNm(),
                        pathSummary.toString()
                );

        String userMessage = String.format(
                "Original User Intent: %s\nCaptured DOM Structure snippet: %s\nLast Intercepted JS Alert Message: %s",
                request.getUserPrompt(),
                request.getHtmlContent() != null && request.getHtmlContent().length() > 30000 
                        ? request.getHtmlContent().substring(0, 30000) // 컨텍스트 윈도우 보장용 자르기
                        : request.getHtmlContent(),
                request.getLastAlertMessage() != null ? request.getLastAlertMessage() : "None"
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
