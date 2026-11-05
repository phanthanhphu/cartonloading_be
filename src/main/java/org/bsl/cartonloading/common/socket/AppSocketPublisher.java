package org.bsl.cartonloading.common.socket;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.bsl.cartonloading.buyers.lululemon.model.Carton;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.Instant;
import org.bsl.cartonloading.common.constants.AppConstants;

@Component
@RequiredArgsConstructor
public class AppSocketPublisher {
    private final SimpMessagingTemplate messagingTemplate;
    private final MongoTemplate mongoTemplate;

    public void organizationChanged(String module, String action) {
        // Publish only an invalidation flag, never full entities/permissions/passwords.
        String topic = AppConstants.OrganizationRealtime.FACTORY.equals(module)
                ? AppConstants.OrganizationRealtime.FACTORIES_TOPIC
                : AppConstants.OrganizationRealtime.ADMIN_TOPIC;
        messagingTemplate.convertAndSend(topic, new OrganizationChangeEvent(module, action, Instant.now()));
    }

    /** Private, user-destination STOMP event. Never broadcast revocations to organization topics.
     * The previous email is important when the administrator changed the account email.
     */
    public void accountChanged(String principalEmail, String userId, long version, boolean enabled,
                               java.util.List<String> changes) {
        if (principalEmail == null || principalEmail.isBlank() || userId == null || userId.isBlank()) return;
        messagingTemplate.convertAndSendToUser(principalEmail,
                "/queue/account-change", java.util.Map.of(
                    "module", "ACCOUNT", "action", "ACCOUNT_CHANGED", "userId", userId,
                    "version", version, "enabled", enabled, "details", changes));
    }

    public void cartonLoadingChanged(String action, String id) {
        messagingTemplate.convertAndSend(
                "/topic/app-events",
                new AppSocketEvent("CARTON_LOADING", action, id, LocalDateTime.now())
        );
        // Factory History is an OUTCOME ledger: do not perform Mongo lookup or issue
        // History refresh for frequent barcode/SSCC/PDA START events. A saved WEIGH
        // result completes a Carton; a later reopen/identity change can remove it.
        // The general /topic/app-events behavior above remains unchanged.
        boolean changesHistory = action != null && (
                action.startsWith(AppConstants.SocketEvent.WEIGHT_RESULT_PREFIX)
                || AppConstants.SocketEvent.WEIGHT_REOPENED.equals(action)
                || "RFID_PASS".equals(action) || "RFID_FAILED".equals(action)
                || "RFID_REOPENED".equals(action)
                || AppConstants.SocketEvent.SSCC_UNASSIGNED.equals(action)
                || AppConstants.SocketEvent.CARTON_REOPENED.equals(action));
        if (changesHistory && id != null && !id.isBlank()) {
            try {
                org.springframework.data.mongodb.core.query.Query query = org.springframework.data.mongodb.core.query.Query.query(
                        org.springframework.data.mongodb.core.query.Criteria.where("_id").is(id)
                                .and("buyerCode").is(AppConstants.Buyer.LULULEMON));
                query.fields().include("factoryCode").include("buyerCode");
                Carton carton = mongoTemplate.findOne(query, Carton.class);
                if (carton != null && carton.getFactoryCode() != null && !carton.getFactoryCode().isBlank()) {
                    String factory = carton.getFactoryCode().trim().toUpperCase(java.util.Locale.ROOT);
                    messagingTemplate.convertAndSend("/topic/lululemon-history/" + factory,
                            new OrganizationChangeEvent("HISTORY:" + factory, action, Instant.now()));
                }
            } catch (RuntimeException ignored) {
                // Notification is best effort and must never roll back a saved Scan/Weight.
            }
        }
    }
}
