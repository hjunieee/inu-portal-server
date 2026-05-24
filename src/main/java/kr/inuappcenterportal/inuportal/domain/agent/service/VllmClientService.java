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
                "1. Framework: Nexacro 14 (Old version).\n" +
                "2. Direct Target Cell Click Navigation Strategy (HIGHLY RECOMMENDED):\n" +
                "   - In Incheon National University ERP system, the left menu dataset 'DS_MENU' contains ALL submenu items under the activated Top Menu, even if their intermediate folders (e.g. '수업', '시간표관리') are COLLAPSED/CLOSED on the screen.\n" +
                "   - Therefore, you do NOT need to expand or click intermediate folder menus one by one to make the target visible.\n" +
                "   - As long as the Top-Level Menu (e.g. 학사행정 M000758) has been activated and 'TARGET_MENU_IN_DS_MENU:true' is present, you can directly trigger the target program screen by finding the row of targetMenuId and triggering 'grd_leftMenu_oncellclick' immediately.\n" +
                "   - This direct click loads the target program immediately without needing to open folders! ALWAYS use this direct target click as your primary action!\n" +
                "   \n" +
                "3. Operational Guidelines (Strict 3-Turn Navigation Sequence):\n" +
                "   - Turn 1 (If Top-Level Menu is NOT active AND 'TARGET_MENU_IN_DS_MENU:false'): Activate the Top Menu first by calling top menu click:\n" +
                "     \"application.gv_topFrame.form.mb_topMenu_onmenuclick(application.gv_topFrame.form.mb_topMenu, {id: '" + topMenuId + "', userdata: '" + topMenuId + "'});\"\n" +
                "   - Turn 2 (Top Menu is already active and 'TARGET_MENU_IN_DS_MENU:true', or you've clicked it in the previous turn): Directly call the 'grd_leftMenu_oncellclick' function on global 'application' object with the row index of target Menu ID '" + request.getMenuId() + "':\n" +
                "     \"var ds = application.gv_leftFrame.form.DS_MENU; if (ds) { var row = ds.findRow('menuId', '" + request.getMenuId() + "'); if (row !== -1) { application.gv_leftFrame.form.grd_leftMenu_oncellclick(application.gv_leftFrame.form.grd_leftMenu, {row: row, cell: 0, clickitem: 'treeitem'}); } }\"\n" +
                "     Do NOT attempt to use 'nexacro.getApplication()' or try to find and click parent folders like 'M001321' or 'M001147'. Just use the above direct row cell click one-liner.\n" +
                "   - Turn 3 (Direct cell click executed and target frame is open): Immediately output the following DIRECT_SSV_CRAWL transition response:\n" +
                "     {\n" +
                "       \"jsCode\": \"DIRECT_SSV_CRAWL\",\n" +
                "       \"statusMessage\": \"넥사크로 세션 연동 완료! 데이터를 고속 추출합니다.\",\n" +
                "       \"completed\": true\n" +
                "     }\n" +
                "\n" +
                "4. Opened Frames Detection Completion Trigger:\n" +
                "   - In the input 'Captured DOM Structure snippet' / HTML content, you will see it in this format: 'OPENED_FRAMES:[List of Opened Frames]||TARGET_MENU_IN_DS_MENU:[true/false]'.\n" +
                "   - If and ONLY if the '[List of Opened Frames]' segment (before the '||') contains or matches the exact targetMenuId '" + request.getMenuId() + "' (which indicates the target program screen is successfully loaded/rendered inside the Nexacro workspace, e.g. Child_" + request.getMenuId() + "),\n" +
                "     you MUST immediately stop sending cellclick script and transition to the crawling phase by returning:\n" +
                "     {\n" +
                "       \"jsCode\": \"DIRECT_SSV_CRAWL\",\n" +
                "       \"statusMessage\": \"넥사크로 세션 연동 완료! 데이터를 고속 추출합니다.\",\n" +
                "       \"completed\": true\n" +
                "     }\n" +
                "     Do NOT run grd_leftMenu_oncellclick script again once the targetMenuId '" + request.getMenuId() + "' is found in the OPENED_FRAMES list segment (e.g. Child_" + request.getMenuId() + ")! If it is not found in that list, you MUST continue executing the cellclick navigation script.\n\n" +
                "5. Intercepted JS Alert Exception Handling & Self-Healing (CRITICAL):\n" +
                "   - In the userMessage, you will see 'Last Intercepted JS Alert Message: [message]'.\n" +
                "   - Case A (Fatal / Terminate): If [message] indicates a permanent block or failure (e.g. '조회 권한이 없습니다', '조회 기간이 아닙니다', '등록된 정보가 없습니다' etc.), you MUST immediately set 'completed': true and output a polite, explanatory Korean statusMessage.\n" +
                "   - Case B (Recoverable / Self-Healing): If [message] indicates a temporary session loss or logout (e.g. '세션이 만료되었습니다', '로그아웃 되었습니다', '로그인이 필요합니다' etc.), you should attempt to recover by outputting a JavaScript code that redirects back to login page (e.g. 'window.location.href = \"https://portal.inu.ac.kr:444/enview/user/login.face\";'), and set 'completed': false with statusMessage explaining that you are recovering the session.";


            // baseUrl의 끝에 /가 있는지 확인하고, uri 호출 시 상대경로를 사용하여 결합 오류 방지
            String normalizedBaseUrl = vllmUrl.endsWith("/") ? vllmUrl : vllmUrl + "/";
            
            WebClient webClient = WebClient.builder()
                    .baseUrl(normalizedBaseUrl)
                    .defaultHeader("Authorization", "Bearer " + apiKey)
                    .build();

            Object userContent;
            if (request.getBase64Image() != null && !request.getBase64Image().isBlank()) {
                userContent = List.of(
                        Map.of("type", "text", "text", userMessage),
                        Map.of("type", "image_url", "image_url", Map.of("url", "data:image/jpeg;base64," + request.getBase64Image().trim().replace("\n", "").replace("\r", "")))
                );
            } else {
                userContent = userMessage;
            }

            // vLLM / OpenAI Chat Completions 요청 페이로드 세팅
            Map<String, Object> requestBody = Map.of(
                    "model", modelName,
                    "messages", List.of(
                            Map.of("role", "system", "content", nexacroSpecificPrompt),
                            Map.of("role", "user", "content", userContent)
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
                    .block(Duration.ofSeconds(180));

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
