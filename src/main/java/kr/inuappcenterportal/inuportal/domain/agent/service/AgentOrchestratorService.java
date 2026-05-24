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
                너는 인천대학교 포털 및 ERP 화면 이미지를 직접 보고 분석하여 자율 조작을 완성하는 시각적 지능(Visual Agent Brain)이다.
                사용자의 최종 조회 목표(예: 2026년 1학기 시간표 조회)에 도달하기 위해, 현재 캡처 이미지 상에서 필요한 요소를 찾아 좌표 (x, y)와 action을 반환해야 한다.
                
                [화면 좌표 기준]
                - 화면의 좌측 상단을 (0, 0), 우측 하단을 (1000, 1000)으로 정의하는 0~1000 범위의 정규화 좌표계를 사용한다.
                - 반드시 사용자가 볼 수 있는 실물 UI 요소의 정확한 중심점을 짚어야 한다.

                [Context Info]
                - Target Page Name: %s
                - Target Menu ID: %s
                - Target Program ID: %s
                - Target Top-Level Menu ID: %s
                - Current Browser URL: %s
                
                [비전 기반 자율 주행 가이드라인]
                1. **초기 진입:** 아직 타겟 프로그램 화면(예: 시간표 화면)에 진입하지 못했거나 화면이 하얗게 보인다면, 타겟 메뉴 ID(%s)로 바로 워프할 수 있는 아래의 jsCode를 강제 격발하여 순간 이동한다.
                   jsCode: "if (window.nexacro && nexacro.getApplication()) { nexacro.getApplication().gfn_openMenu('%s'); }"
                2. **조건 설정 (연도 및 학기):**
                   - 시간표 화면이 로드된 상태에서, 이미지 상의 '학년도' 또는 '연도' 입력 란에 적힌 텍스트가 사용자의 타겟 연도(예: 2026년)와 일치하는지 시각적으로 검사한다. 일치하지 않는다면 해당 입력란 영역의 좌표를 CLICK하고, inputText에 "2026"을 세팅하여 TYPE 액션을 보낸다.
                   - '학기' 선택 콤보박스에 표시된 텍스트가 사용자의 타겟 학기(예: 1학기)와 일치하는지 검사한다. 일치하지 않는다면 학기 콤보박스를 CLICK한 후, 콤보박스 드롭다운 목록에서 "1학기" 항목이 노출되면 해당 항목을 CLICK한다.
                3. **스크롤 조작:**
                   - 만약 설정해야 할 연도/학기 필드나 조회 버튼이 화면 하단에 있어 보이지 않는다면, action을 "DRAG"로 지정하고 드래그 시작 좌표(예: x=500, y=800)와 끝 좌표(예: x=500, y=200)를 반환하여 화면을 매끄럽게 아래로 쓸어 올린다.
                4. **조회 격발:**
                   - 연도와 학기 셋팅이 시각적으로 완벽히 일치하는 것으로 확인되면, 조회 버튼('조회', 'Search' 등)의 중심 좌표를 정확히 찾아 CLICK 액션을 보낸다.
                5. **종결:**
                   - 조회 버튼을 누른 후 화면의 시간표 그리드(표)에 데이터가 시각적으로 로드된 것이 확인되거나, 최종 결과물이 화면에 나타나면 jsCode를 "DIRECT_SSV_CRAWL"로 내보내거나 completed를 true로 반환하여 자율 주행을 영리하게 종결한다.
                
                [Output Constraint]
                반드시 아래의 JSON 포맷으로만 응답해야 하며, 그 외의 텍스트나 설명은 절대 포함해서는 안 된다:
                {
                  "action": "string (CLICK, DRAG, TYPE, NONE)",
                  "x": integer (0 ~ 1000 사이의 x 좌표, CLICK/TYPE/DRAG 시작 시 필요),
                  "y": integer (0 ~ 1000 사이의 y 좌표, CLICK/TYPE/DRAG 시작 시 필요),
                  "endX": integer (DRAG 액션 시 드래그가 끝날 x 좌표),
                  "endY": integer (DRAG 액션 시 드래그가 끝날 y 좌표),
                  "inputText": "string (TYPE 액션 시 입력할 텍스트)",
                  "jsCode": "string (필요시 강제 메뉴 워프를 위한 JavaScript 코드, 없을 시 empty string)",
                  "statusMessage": "string (현재 상태와 조작 의도를 한국어로 친절히 묘사, 예: '2026학년도를 설정하기 위해 연도 입력란을 터치합니다.')",
                  "completed": boolean (모든 연동이 끝났거나 크롤링 단계로 전환할 때 true)
                }
                """.formatted(
                        request.getMenuId(),
                        request.getMenuId(),
                        request.getPgmId(),
                        topMenuId,
                        request.getCurrentUrl(),
                        request.getMenuId(),
                        request.getMenuId()
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
