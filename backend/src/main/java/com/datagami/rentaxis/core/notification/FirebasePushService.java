package com.datagami.rentaxis.core.notification;

import com.datagami.rentaxis.domain.entity.DeviceToken;
import com.datagami.rentaxis.domain.repository.DeviceTokenRepository;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.UUID;

/** Sends best-effort FCM notifications to registered mobile devices. */
@Service
@Slf4j
public class FirebasePushService {

    private final DeviceTokenRepository deviceTokenRepository;
    private final String projectId;
    private final String serviceAccountJsonBase64;

    private FirebaseApp firebaseApp;
    private FirebaseMessaging firebaseMessaging;

    public FirebasePushService(
            DeviceTokenRepository deviceTokenRepository,
            @Value("${firebase.auth.project-id:}") String projectId,
            @Value("${firebase.auth.service-account-json-base64:}") String serviceAccountJsonBase64) {
        this.deviceTokenRepository = deviceTokenRepository;
        this.projectId = projectId == null ? "" : projectId.trim();
        this.serviceAccountJsonBase64 =
                serviceAccountJsonBase64 == null ? "" : serviceAccountJsonBase64.trim();
    }

    @PostConstruct
    void initialize() {
        if (projectId.isBlank() || serviceAccountJsonBase64.isBlank()) {
            log.warn("Firebase push delivery is disabled: Firebase Admin credentials are not configured");
            return;
        }
        try {
            GoogleCredentials credentials = GoogleCredentials.fromStream(
                    new ByteArrayInputStream(Base64.getDecoder().decode(serviceAccountJsonBase64)));
            firebaseApp = FirebaseApp.initializeApp(
                    FirebaseOptions.builder().setCredentials(credentials).setProjectId(projectId).build(),
                    "rentaxis-push-" + UUID.randomUUID());
            firebaseMessaging = FirebaseMessaging.getInstance(firebaseApp);
        } catch (IllegalArgumentException | IOException exception) {
            log.error("Firebase push delivery is disabled: Firebase credentials could not be loaded", exception);
        }
    }

    /**
     * Delivers one alert per registered device. Delivery is deliberately
     * best-effort: the persisted in-app notification remains the source of
     * truth and a bad token must never alter an access-control decision.
     */
    public void send(PushNotificationEvent event) {
        if (firebaseMessaging == null) return;
        for (DeviceToken device : deviceTokenRepository.findByUserId(event.userId())) {
            try {
                firebaseMessaging.send(Message.builder()
                        .setToken(device.getToken())
                        .setNotification(Notification.builder()
                                .setTitle(event.title())
                                .setBody(event.message())
                                .build())
                        .setAndroidConfig(AndroidConfig.builder()
                                .setPriority(AndroidConfig.Priority.HIGH)
                                .build())
                        .putData("type", event.type())
                        .putData("referenceType", nullable(event.referenceType()))
                        .putData("referenceId", event.referenceId() == null ? "" : event.referenceId().toString())
                        .build());
            } catch (FirebaseMessagingException exception) {
                log.warn("Unable to deliver {} push to device {}: {}", event.type(), device.getId(),
                        exception.getMessagingErrorCode());
            } catch (RuntimeException exception) {
                log.warn("Unable to deliver {} push to device {}", event.type(), device.getId(), exception);
            }
        }
    }

    private static String nullable(String value) {
        return value == null ? "" : value;
    }

    @PreDestroy
    void shutdown() {
        if (firebaseApp != null) firebaseApp.delete();
    }
}
