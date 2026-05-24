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
    }

    @Getter
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class StepResponse {
        private String jsCode;
        private String statusMessage;
        private boolean completed;
    }
}
