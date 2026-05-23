package kr.inuappcenterportal.inuportal.global.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.FileInputStream;
import java.io.InputStream;

@Configuration
@Slf4j
public class FirebaseConfig {
    @Value("${firebaseKeyPath}")
    private String path;
    @PostConstruct
    public void init(){
        try{
            if (!FirebaseApp.getApps().isEmpty()) {
                return;
            }
            InputStream serviceAccount =
                    new ClassPathResource("firebase_key.json").getInputStream();

            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                    .build();

            FirebaseApp.initializeApp(options);
            log.info("파이어베이스 연결 성공");
        }catch (Exception e){
            log.warn("파이어베이스 연결 실패 (로컬 더미 우회 활성화) : {}",e.getMessage());
            try {
                GoogleCredentials dummyCredentials = new GoogleCredentials() {
                    @Override
                    public com.google.auth.oauth2.AccessToken refreshAccessToken() throws java.io.IOException {
                        return new com.google.auth.oauth2.AccessToken("local-dummy-token", new java.util.Date(System.currentTimeMillis() + 3600000));
                    }
                };
                FirebaseOptions options = FirebaseOptions.builder()
                        .setCredentials(dummyCredentials)
                        .setProjectId("intip-local-dummy")
                        .build();
                FirebaseApp.initializeApp(options);
                log.info("로컬 테스트용 더미 파이어베이스 기동 성공");
            } catch (Exception ex) {
                log.error("더미 파이어베이스 기동 실패: {}", ex.getMessage());
            }
        }
    }

    @Bean
    public FirebaseMessaging firebaseMessaging() {
        return FirebaseApp.getApps().isEmpty() ? null : FirebaseMessaging.getInstance();
    }
}
