package kr.inuappcenterportal.inuportal.domain.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

public class AgentDto {

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StartRequest {
        private String userPrompt;
    }

    @Getter
    @Builder
    @AllArgsConstructor
    public static class StartResponse {
        private boolean success;
        private String menuId;
        private String pgmId;
        private String menuName;
        private String initJsCode;
        private String statusMessage;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StepRequest {
        private String userPrompt;
        private String menuId;
        private String pgmId;
        private String currentUrl;
        private String htmlContent;
        private String lastAlertMessage;
        private String base64Image; // 추가: 실시간 안드로이드 스크린샷 캡처
    }

    @Getter
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class StepResponse {
        private String jsCode;
        private String statusMessage;
        private boolean completed;

        // 추가: 비전 기반 터치/드래그 액션용 좌표 필드
        private String action;      // "CLICK", "DRAG", "TYPE", "NONE" 등
        private Integer x;          // 정규화 좌표 x (0 ~ 1000)
        private Integer y;          // 정규화 좌표 y (0 ~ 1000)
        private Integer endX;       // 드래그 시 끝 x 좌표 (0 ~ 1000)
        private Integer endY;       // 드래그 시 끝 y 좌표 (0 ~ 1000)
        private String inputText;   // 텍스트 입력 시 기입할 글자
        private String targetText;  // 추가: 자석 스냅핑을 위한 타겟 엘리먼트 텍스트
    }
}
