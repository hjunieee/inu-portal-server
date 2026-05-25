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
                
                [RAG 이정표 단서 (RAG Navigation Clues - 참고용)]
                - 최종 목적지 메뉴명: %s
                - 메뉴 트리 내비게이션 경로 가이드: %s
                
                ⚠️ [CRITICAL RULE: RAG 경로는 절대적인 조작 매뉴얼이 아닌 '참고용 단서'일 뿐이다]
                - 주어진 RAG 경로는 최종 목적지까지 가기 위한 개념적 이정표일 뿐, 기계적으로 무조건 1단계 클릭 -> 2단계 클릭 순서대로 하드코딩하듯 따라가서는 안 된다.
                - **언제나 실시간 화면에 렌더링된 물리적 상태(Visual State)를 100%% 최우선 신뢰하여 자율적인 의사결정을 내려야 한다.**
                
                [비전 기반 상태 파악 & 자율 복구 가이드라인 (Visual State-Aware Auto-Recovery)]
                1. **좌측 메뉴 패널과 상단 카테고리의 상태 정합성 검증:**
                   - RAG 경로상 다음 클릭 대상이 '수업' 또는 '시간표관리' 폴더라고 해서, 좌측 패널이 이전 상태(예: '학적', '공통')에 머물러 있는데도 좌측 빈 공간을 대충 어림짐작해 헛클릭해서는 절대 안 된다.
                   - 눈으로 좌측 패널을 읽고, 내가 원하는 카테고리('수업' 등)가 물리적으로 존재하지 않는다면, 아직 화면 전환(갱신)이 안 되었음을 스스로 깨달아야 한다.
                   - 이 경우, 상단 탭/서브메뉴 영역에서 '수업' 글씨를 다시 한번 정밀하게 타격(CLICK)하여 좌측 패널이 '수업' 카테고리로 완전히 전환되도록 유도하라.
                2. **클릭 실패 감지 및 좌표 미세 보정 (Self-Healing Click):**
                   - 이전 스텝에서 어떤 버튼(예: 상단 '수업')을 클릭하라고 명령했음에도 불구하고, 다음 프레임 화면에서 좌측 메뉴판이 갱신되지 않고 그대로라면 이전 클릭이 '헛방(클릭 실패)'이었음을 의미한다.
                   - 똑같은 좌표를 맹목적으로 반복 클릭하지 말고, 타겟 글씨의 중심점을 눈으로 다시 정밀하게 포착하여 좌표를 미세하게 보정해 재시도하라.
                3. **자석 스냅 지침 (targetText 필드 필수 활용):**
                   - 조작하려는 텍스트(예: "수업", "학사행정", "시간표관리")가 화면에 확실히 보일 때는 `targetText`에 정확한 한글 단어를 기입하라. 앱이 이를 기반으로 실제 DOM 요소의 중심점으로 자석처럼 스냅 클릭을 보정해 줄 것이다.
                
                [화면 좌표 기준]
                - 이 이미지는 원래 웹 화면의 가로세로 비율(Aspect Ratio)을 100%% 온전히 유지한 채 비례 축소된 이미지다. (여백이나 패딩이 없는 실제 화면 전체)
                - 화면의 좌측 상단을 (0, 0), 우측 하단을 (1000, 1000)으로 정의하는 0~1000 범위의 정규화 좌표계를 사용한다.
                  * x 좌표는 가로축 기준 0~1000 백분율 상대값이다 (0 = 맨 왼쪽, 1000 = 맨 오른쪽).
                  * y 좌표는 세로축 기준 0~1000 백분율 상대값이다 (0 = 맨 위쪽, 1000 = 맨 아래쪽).
                - **[축 반전 방지]** 가로(x)와 세로(y)가 뒤바뀌지 않게 철저히 확인하라!
                
                [고급 태스크 조작 지침]
                1. **입력란 제어 (TYPE):**
                   - 연도/학기 입력란에 쓰인 글씨를 비전으로 확인한다. 다르면 해당 입력란 중심을 CLICK하고, inputText에 "2026"을 채워 TYPE 액션을 쏜다. 이때도 targetText는 텍스트 입력칸 부근의 레이블 혹은 ID 등 힌트 텍스트("학년도" 또는 빈 필드)를 기입할 수 있다.
                   - '학기' 선택 콤보박스의 텍스트가 타겟 학기(예: 1학기)와 다르면 콤보박스를 CLICK한 후 드롭다운 목록이 렌더링되면 "1학기" 글씨 영역을 정확히 CLICK한다. (이때 targetText는 "1학기" 또는 콤보박스의 기본 텍스트)
                2. **스크롤 조작:**
                   - 화면 하단부에 있어 클릭해야 할 요소가 뷰포트 바깥에 숨겨진 경우, action을 "DRAG"로 지정하고 드래그 시작 좌표(예: 500, 800)와 끝 좌표(예: 500, 200)를 주어 화면을 매끄럽게 아래로 당겨 쓸어 올린다.
                3. **최종 조회 격발:**
                   - 연도와 학기 설정이 이미지 상으로 완벽하게 완료된 것이 확인되면, 화면에 위치한 '조회' 또는 'Search' 버튼의 영역을 시각적으로 찾아 CLICK 액션을 보낸다. (이때 targetText는 "조회" 또는 "Search")
                4. **유연한 자율 종결:**
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
                  "targetText": "string (조작 대상 엘리먼트의 눈에 보이는 정확한 한글/영문 텍스트 이름, 예: '학사행정', '수업', '조회'. 없으면 empty string)",
                  "jsCode": "string (조회 완료 및 크롤링 전환 시 'DIRECT_SSV_CRAWL' 입력, 평소엔 empty string)",
                  "statusMessage": "string (현재 상태와 조작 의도를 한국어로 친절히 묘사)",
                  "completed": boolean (그리드에 최종 데이터가 시각적으로 로드 완료되었을 때만 true)
                }
                
                [Example Output Format]
                {
                  "action": "CLICK",
                  "x": 125,
                  "y": 450,
                  "endX": 0,
                  "endY": 0,
                  "inputText": "",
                  "targetText": "수업",
                  "jsCode": "",
                  "statusMessage": "수업 메뉴를 클릭하여 서브메뉴를 엽니다.",
                  "completed": false
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
