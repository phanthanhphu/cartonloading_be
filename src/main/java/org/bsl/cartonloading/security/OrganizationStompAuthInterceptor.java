package org.bsl.cartonloading.security;

import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.model.User;
import org.bsl.cartonloading.service.BuyerService;
import org.bsl.cartonloading.service.UserService;
import org.bsl.cartonloading.repository.FactoryRepository;
import org.bsl.cartonloading.common.factory.FactoryCodes;
import org.bsl.cartonloading.model.Factory;
import org.springframework.lang.NonNull;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.List;

/** Authenticate STOMP CONNECT (native WebSocket cannot send HTTP Authorization headers).
 * Never accept anonymous subscriptions. Admin-only topic carries admin invalidation flags.
 */
@Component
public class OrganizationStompAuthInterceptor implements ChannelInterceptor {
    private final JwtUtil jwt;
    private final UserService users;
    private final BuyerService buyers;
    private final FactoryRepository factoryRepository;

    public OrganizationStompAuthInterceptor(JwtUtil jwt, UserService users, BuyerService buyers,
                                             FactoryRepository factoryRepository) {
        this.jwt = jwt;
        this.users = users;
        this.buyers = buyers;
        this.factoryRepository = factoryRepository;
    }

    @Override
    public Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
        StompHeaderAccessor headers = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (headers == null || headers.getCommand() == null) return message;
        StompCommand command = headers.getCommand();
        // Preserve the existing SockJS /ws contract for unrelated integrations.
        boolean organizationNative = headers.getSessionAttributes() != null && Boolean.TRUE.equals(
                headers.getSessionAttributes().get(AppConstants.OrganizationRealtime.NATIVE_SESSION_MARKER));
        if (StompCommand.CONNECT.equals(command)) {
            if (!organizationNative) return message;
            String authorization = headers.getFirstNativeHeader("authorization");
            if (authorization == null || !authorization.startsWith("Bearer ")) {
                throw new AccessDeniedException("WebSocket token required");
            }
            String token = authorization.substring("Bearer ".length()).trim();
            String email = jwt.getEmailFromToken(token);
            User user = email == null ? null : users.findByEmail(email).orElse(null);
            if (user == null || !user.isEnabled() || !jwt.validateToken(token, user.getEmail(), user.getTokenVersion())) {
                throw new AccessDeniedException("Invalid WebSocket token");
            }
            String selectedBuyer = jwt.getSelectedBuyer(token);
            String selectedType = jwt.getSelectedType(token);
            if (!user.canAccessBuyerType(selectedBuyer, selectedType) || !buyers.supportsType(selectedBuyer, selectedType))
                throw new AccessDeniedException("WebSocket Buyer/Type access denied");
            var auth = new UsernamePasswordAuthenticationToken(user.getEmail(), null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole())));
            headers.setUser(auth);
            if (headers.getSessionAttributes() != null) {
                headers.getSessionAttributes().put("selectedBuyer", selectedBuyer);
                headers.getSessionAttributes().put("authenticatedEmail", user.getEmail());
                headers.getSessionAttributes().put("authenticatedTokenVersion", user.getTokenVersion());
            }
        } else if (StompCommand.SUBSCRIBE.equals(command)) {
            String topic = headers.getDestination();
            boolean accountTopic = "/user/queue/account-change".equals(topic);
            boolean historyTopic = topic != null && topic.startsWith("/topic/lululemon-history/");
            boolean organizationTopic = AppConstants.OrganizationRealtime.ADMIN_TOPIC.equals(topic)
                    || AppConstants.OrganizationRealtime.FACTORIES_TOPIC.equals(topic);
            if (!organizationTopic && !organizationNative) return message; // preserve legacy subscriptions
            if (!(headers.getUser() instanceof UsernamePasswordAuthenticationToken auth) || !auth.isAuthenticated()) {
                throw new AccessDeniedException("WebSocket authentication required");
            }
            // The socket may have been established before an Admin revoked this account.
            // Keep only its private revoke queue available; never allow new data subscriptions.
            if (organizationNative && !accountTopic) {
                User current = users.findByEmail(auth.getName()).orElse(null);
                Object oldVersion = headers.getSessionAttributes() == null ? null
                        : headers.getSessionAttributes().get("authenticatedTokenVersion");
                if (current == null || !current.isEnabled() || !(oldVersion instanceof Number version)
                        || current.getTokenVersion() != version.longValue()) {
                    throw new AccessDeniedException("WebSocket session revoked");
                }
            }
            if (AppConstants.OrganizationRealtime.ADMIN_TOPIC.equals(topic) && auth.getAuthorities().stream()
                    .noneMatch(a -> a.getAuthority().equals("ROLE_" + AppConstants.Role.ADMIN))) {
                throw new AccessDeniedException("Admin-only organization events");
            }
            if (historyTopic) {
                String factory = topic.substring("/topic/lululemon-history/".length());
                if (!factory.matches("[A-Z0-9_-]{1,30}") || headers.getSessionAttributes() == null
                        || !AppConstants.Buyer.LULULEMON.equalsIgnoreCase(String.valueOf(
                                headers.getSessionAttributes().get("selectedBuyer")))) {
                    throw new AccessDeniedException("History Buyer/Factory access denied");
                }
                User requestingUser = users.findByEmail(auth.getName()).orElse(null);
                if (requestingUser == null || !requestingUser.isEnabled()
                        || (!requestingUser.isAdminRole() && !requestingUser.canAccessFactory(factory))) {
                    throw new AccessDeniedException("History Factory access denied");
                }
                // Check ACTIVE status directly from the repository. Do not inject
                // FactoryCatalogService here: it publishes WebSocket events and would
                // create a Spring Bean cycle through WebSocketConfig.
                String factoryCode;
                try {
                    factoryCode = FactoryCodes.requireValid(factory);
                } catch (IllegalArgumentException invalidCode) {
                    throw new AccessDeniedException("Invalid History Factory", invalidCode);
                }
                boolean factoryActive = factoryRepository
                        .findByBuyerKeyAndCode(AppConstants.Buyer.LULULEMON, factoryCode)
                        .filter(Factory::isActive)
                        .isPresent();
                if (!factoryActive) {
                    throw new AccessDeniedException("History Factory is unavailable");
                }
            }
            if (!organizationTopic && !accountTopic && !historyTopic)
                throw new AccessDeniedException("Unknown organization subscription");
        } else if (StompCommand.SEND.equals(command)) {
            String destination = headers.getDestination();
            if (organizationNative || (destination != null && destination.startsWith("/topic/organization-"))) {
                // No client-to-server writes to organization topics, including via legacy endpoint.
                throw new AccessDeniedException("Organization WebSocket is read-only");
            }
        }
        return message;
    }
}
