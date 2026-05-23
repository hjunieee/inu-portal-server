package kr.inuappcenterportal.inuportal.domain.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class VllmClientService {

    private final ObjectMapper objectMapper;

    @Value("${vllm.server.url:https://ai-server.inuappcenter.kr/v1}")
    private String vllmUrl;

    @Value("${vllm.api.key}")
    private String apiKey;

    @Value("${vllm.model.name:Qwen/Qwen2.5-Coder-32B-Instruct-AWQ}")
    private String modelName;

    /**
     * H100 vLLM 서버에 OpenAI Chat Completions 규격으로 비동기/동기 요청을 전달하여 
     * 다음 에이전트 조작 단계의 자바스크립트 코드와 상태 메시지를 추론해 냅니다.
     */
    public String requestNextStep(String systemPrompt, String userMessage, String topMenuId, kr.inuappcenterportal.inuportal.domain.agent.dto.AgentDto.StepRequest request) {
        try {
            // Nexacro 14 ERP 특화 지침 추가 (AI가 시스템 구조를 오해하지 않도록 보강)
            String nexacroSpecificPrompt = systemPrompt + "\n\n" +
                "[IMPORTANT: ERP System Context]\n" +
                "1. Framework: Nexacro 14 (Old version). Use 'application' directly (NOT nexacro.getApplication()).\n" +
                "2. Multi-Depth Navigation Strategy:\n" +
                "   Follow the provided [Target Navigation Path] sequentially. For each step in the path:\n" +
                "   A. If it is the TOP LEVEL (Level 0), use: application.gv_topFrame.form.mb_topMenu_onmenuclick(application.gv_topFrame.form.mb_topMenu, {id: 'ID', userdata: 'ID'});\n" +
                "   B. If it is a SUB-MENU (Level 1+), locate it in 'application.gv_leftFrame.form.DS_MENU' and use: application.gv_leftFrame.form.grd_leftMenu_oncellclick(application.gv_leftFrame.form.grd_leftMenu, {row: row, cell: 0, clickitem: 'treeitem'});\n" +
                "   \n" +
                "   Your 'jsCode' MUST ALWAYS check the current loaded menus and click the first item in the path that is NOT yet active or visible.\n" +
                "   \n" +
                "   [Recursive Logic Pattern]\n" +
                "   ```javascript\n" +
                "   var ds = application.gv_leftFrame.form.DS_MENU;\n" +
                "   var targetId = '" + request.getMenuId() + "';\n" +
                "   \n" +
                "   if (!ds || ds.findRow('menuId', 'NEXT_LEVEL_ID_IN_PATH') === -1) {\n" +
                "       application.gv_topFrame.form.mb_topMenu_onmenuclick(application.gv_topFrame.form.mb_topMenu, {id: 'TOP_ID', userdata: 'TOP_ID'});\n" +
                "   } else {\n" +
                "       var row = ds.findRow('menuId', targetId);\n" +
                "       if (row !== -1 && ds.getColumn(row, 'menuGbn') === 'P') {\n" +
                "           // FINAL ACTION: Target program found. Click it and signal transition.\n" +
                "           application.gv_leftFrame.form.grd_leftMenu_oncellclick(application.gv_leftFrame.form.grd_leftMenu, {row: row, cell: 0, clickitem: 'treeitem'});\n" +
                "       }\n" +
                "   }\n" +
                "   ```\n" +
                "3. Completion Trigger:\n" +
                "   - If your previous 'jsCode' was a click on a Program ('P'), you MUST transition to direct scraping in the NEXT turn:\n" +
                "     {\n" +
                "       \"jsCode\": \"DIRECT_SSV_CRAWL\",\n" +
                "       \"statusMessage\": \"넥사크로 세션 연동 완료! 데이터를 추출합니다.\",\n" +
                "       \"completed\": true\n" +
                "     }";


            // baseUrl의 끝에 /가 있는지 확인하고, uri 호출 시 상대경로를 사용하여 결합 오류 방지
            String normalizedBaseUrl = vllmUrl.endsWith("/") ? vllmUrl : vllmUrl + "/";
            
            WebClient webClient = WebClient.builder()
                    .baseUrl(normalizedBaseUrl)
                    .defaultHeader("Authorization", "Bearer " + apiKey)
                    .build();

            // vLLM / OpenAI Chat Completions 요청 페이로드 세팅
            Map<String, Object> requestBody = Map.of(
                    "model", modelName,
                    "messages", List.of(
                            Map.of("role", "system", "content", nexacroSpecificPrompt),
                            Map.of("role", "user", "content", userMessage)
                    ),
                    "temperature", 0.1,
                    "response_format", Map.of("type", "json_object")
            );

            log.info("vLLM API 호출 시도 - URL: {}chat/completions, Model: {}, API Key(prefix): {}...", 
                    normalizedBaseUrl, modelName, apiKey.substring(0, Math.min(apiKey.length(), 5)));

            String rawResponse = webClient.post()
                    .uri("chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(30));

            if (rawResponse == null || rawResponse.isBlank()) {
                throw new RuntimeException("vLLM 서버로부터 빈 응답을 수령했습니다.");
            }

            // OpenAI 응답 트리 파싱 -> choices[0].message.content 획득
            JsonNode rootNode = objectMapper.readTree(rawResponse);
            JsonNode contentNode = rootNode.path("choices").path(0).path("message").path("content");
            
            String inferredContent = contentNode.asText();
            log.info("vLLM 추론 수령 완료: {}", inferredContent);

            return inferredContent;

        } catch (Exception e) {
            log.error("vLLM 추론 호출 중 에러 발생: {}", e.getMessage(), e);
            // 에러 발생 시 단말기가 먹통이 되지 않고 정지하도록 안전용 폴백 JSON을 반환합니다.
            return "{\"jsCode\": \"\", \"statusMessage\": \"⚠️ AI 모델 서버 통신 장애가 발생했습니다.\", \"completed\": true}";
        }
    }
}
