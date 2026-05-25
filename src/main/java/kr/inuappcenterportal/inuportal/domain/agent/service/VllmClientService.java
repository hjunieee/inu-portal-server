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
                "[IMPORTANT: Intercepted Alert Exception Handling & Self-Healing]\n" +
                "1. If 'Last Intercepted JS Alert Message' in the user message indicates a permanent block or failure (e.g. '조회 권한이 없습니다', '조회 기간이 아닙니다', '등록된 정보가 없습니다' 등),\n" +
                "   you MUST immediately set 'completed': true and output a polite, explanatory Korean statusMessage.\n" +
                "2. If it indicates a session timeout or logout (e.g. '세션이 만료되었습니다', '로그아웃 되었습니다', '로그인이 필요합니다' 등),\n" +
                "   attempt to recover the session by returning: jsCode: 'window.location.href = \"https://portal.inu.ac.kr:444/enview/user/login.face\";' and completed: false.";


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
