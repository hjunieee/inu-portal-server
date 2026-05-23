package kr.inuappcenterportal.inuportal.domain.agent.controller;

import kr.inuappcenterportal.inuportal.domain.agent.dto.AgentDto;
import kr.inuappcenterportal.inuportal.domain.agent.service.AgentOrchestratorService;
import kr.inuappcenterportal.inuportal.domain.agent.service.PortalMenuSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/agent")
@RequiredArgsConstructor
@Slf4j
public class AgentRestController {

    private final AgentOrchestratorService orchestratorService;
    private final PortalMenuSyncService menuSyncService;

    /**
     * 사용자의 프롬프트를 수령하여 로컬 RAG MySQL 지식 지도 데이터베이스와 매칭하고 
     * 첫 번째 자동 조작 스크립트를 기동합니다.
     */
    @PostMapping("/start")
    public ResponseEntity<AgentDto.StartResponse> startAgentTask(@RequestBody AgentDto.StartRequest request) {
        log.info("API 수신 - 에이전트 태스크 시작: {}", request.getUserPrompt());
        AgentDto.StartResponse response = orchestratorService.initiateTask(request.getUserPrompt());
        return ResponseEntity.ok(response);
    }

    /**
     * 안드로이드의 실시간 WebView DOM 구조와 최종 상태를 받아 
     * H100 vLLM 서버의 추론 결과를 기반으로 다음 단계의 자바스크립트 조작 코드를 산출합니다.
     */
    @PostMapping("/step")
    public ResponseEntity<AgentDto.StepResponse> processAgentStep(@RequestBody AgentDto.StepRequest request) {
        log.info("API 수신 - 에이전트 자율 조작 스텝 평가 요청");
        AgentDto.StepResponse response = orchestratorService.evaluateNextStep(request);
        return ResponseEntity.ok(response);
    }

    /**
     * 넥사크로에서 콘솔 카피로 추출한 DS_MENU XML 데이터를 수령하여
     * 실시간으로 MySQL 데이터베이스에 적재/동기화합니다.
     * (Content-Type: text/plain 으로 XML 원문을 그대로 body에 넣으면 됩니다)
     */
    @PostMapping(value = "/sync-menu", consumes = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<Map<String, Object>> synchronizeMenuXml(@RequestBody String xmlData) {
        log.info("API 수신 - 포털 메뉴 XML 동기화 요청");

        int syncedCount = menuSyncService.syncMenuXml(xmlData);

        return ResponseEntity.ok(Map.of(
                "success", true,
                "syncedCount", syncedCount,
                "message", String.format("총 %d개의 인천대 포털 메뉴가 MySQL 데이터베이스에 성공적으로 적재/갱신되었습니다.", syncedCount)
        ));
    }
}
