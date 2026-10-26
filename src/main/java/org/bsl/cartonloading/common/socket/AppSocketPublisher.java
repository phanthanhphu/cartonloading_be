package org.bsl.cartonloading.common.socket;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.Instant;
import org.bsl.cartonloading.common.constants.AppConstants;

@Component
@RequiredArgsConstructor
public class AppSocketPublisher {
    private final SimpMessagingTemplate messagingTemplate;

    public void organizationChanged(String module, String action) {
        // Publish only an invalidation flag, never full entities/permissions/passwords.
        String topic = AppConstants.OrganizationRealtime.FACTORY.equals(module)
                ? AppConstants.OrganizationRealtime.FACTORIES_TOPIC
                : AppConstants.OrganizationRealtime.ADMIN_TOPIC;
        messagingTemplate.convertAndSend(topic, new OrganizationChangeEvent(module, action, Instant.now()));
    }

    public void cartonLoadingChanged(String action, String id) {
        messagingTemplate.convertAndSend(
                "/topic/app-events",
                new AppSocketEvent("CARTON_LOADING", action, id, LocalDateTime.now())
        );
    }
}
