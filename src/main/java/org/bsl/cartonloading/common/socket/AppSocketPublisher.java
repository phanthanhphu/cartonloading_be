package org.bsl.cartonloading.common.socket;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class AppSocketPublisher {
    private final SimpMessagingTemplate messagingTemplate;

    public void cartonLoadingChanged(String action, String id) {
        messagingTemplate.convertAndSend(
                "/topic/app-events",
                new AppSocketEvent("CARTON_LOADING", action, id, LocalDateTime.now())
        );
    }
}
