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
                "   Follow the provided [Target Navigation Path] sequentially from left to right. Do NOT skip any intermediate folders.\n" +
                "   A. If it is the TOP LEVEL (Level 0), use: application.gv_topFrame.form.mb_topMenu_onmenuclick(application.gv_topFrame.form.mb_topMenu, {id: 'ID', userdata: 'ID'});\n" +
                "   B. If it is a SUB-MENU (Level 1+), locate it in 'application.gv_leftFrame.form.DS_MENU' and use: application.gv_leftFrame.form.grd_leftMenu_oncellclick(application.gv_leftFrame.form.grd_leftMenu, {row: row, cell: 0, clickitem: 'treeitem'});\n" +
                "   \n" +
                "   Your 'jsCode' MUST check the loaded 'DS_MENU' sequentially through the path.\n" +
                "   For any intermediate folder menu in the path, if it is NOT yet visible or loaded in 'DS_MENU', you MUST expand its parent menu first.\n" +
                "   Keep in mind that intermediate folder menus have 'menuGbn' as 'M' or 'D' (Directory/Menu), and the final program has 'menuGbn' as 'P' (Program).\n" +
                "   \n" +
                "   [Recursive Logic Pattern]\n" +
                "   Your JS code must look up the path step-by-step. For example, if the path is [0] Top -> [1] FolderA -> [2] FolderB -> [3] ProgramC:\n" +
                "   - Check if FolderA is loaded. If NOT, click Top.\n" +
                "   - If FolderA is loaded but FolderB is NOT, check if FolderA is a directory ('D' or 'M') and click FolderA to expand it.\n" +
                "   - If FolderB is loaded, find ProgramC and click ProgramC.\n" +
                "   \n" +
                "   Example robust code pattern:\n" +
                "   ```javascript\n" +
                "   var ds = application.gv_leftFrame.form.DS_MENU;\n" +
                "   var targetId = '" + request.getMenuId() + "';\n" +
                "   \n" +
                "   if (!ds || ds.findRow('menuId', 'NEXT_LEVEL_FOLDER_ID') === -1) {\n" +
                "       // Click parent folder or Top Menu first to load NEXT_LEVEL_FOLDER_ID\n" +
                "       application.gv_topFrame.form.mb_topMenu_onmenuclick(application.gv_topFrame.form.mb_topMenu, {id: 'PARENT_OR_TOP_ID', userdata: 'PARENT_OR_TOP_ID'});\n" +
                "   } else {\n" +
                "       var row = ds.findRow('menuId', targetId);\n" +
                "       var gbn = row !== -1 ? ds.getColumn(row, 'menuGbn') : '';\n" +
                "       if (row !== -1 && gbn === 'P') {\n" +
                "           // FINAL ACTION: Target program found. Click it.\n" +
                "           application.gv_leftFrame.form.grd_leftMenu_oncellclick(application.gv_leftFrame.form.grd_leftMenu, {row: row, cell: 0, clickitem: 'treeitem'});\n" +
                "       } else {\n" +
                "           // Expand intermediate folder\n" +
                "           var parentRow = ds.findRow('menuId', 'NEXT_LEVEL_FOLDER_ID');\n" +
                "           if (parentRow !== -1 && (ds.getColumn(parentRow, 'menuGbn') === 'D' || ds.getColumn(parentRow, 'menuGbn') === 'M')) {\n" +
                "               application.gv_leftFrame.form.grd_leftMenu_oncellclick(application.gv_leftFrame.form.grd_leftMenu, {row: parentRow, cell: 0, clickitem: 'treeitem'});\n" +
                "           }\n" +
                "       }\n" +
                "   }\n" +
                "   ```\n" +
                "3. Completion Trigger:\n" +
                "   - If your previous 'jsCode' was a click on a Program ('P') (e.g. gfn_openMenu or cell click on P), you MUST transition to direct scraping in the NEXT turn:\n" +
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
