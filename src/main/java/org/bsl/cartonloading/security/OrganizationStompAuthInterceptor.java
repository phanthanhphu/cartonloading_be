package org.bsl.cartonloading.security;

import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.model.User;
import org.bsl.cartonloading.service.BuyerService;
import org.bsl.cartonloading.service.UserService;
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

    public OrganizationStompAuthInterceptor(JwtUtil jwt, UserService users, BuyerService buyers) {
        this.jwt = jwt;
        this.users = users;
        this.buyers = buyers;
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
        } else if (StompCommand.SUBSCRIBE.equals(command)) {
            String topic = headers.getDestination();
            boolean organizationTopic = AppConstants.OrganizationRealtime.ADMIN_TOPIC.equals(topic)
                    || AppConstants.OrganizationRealtime.FACTORIES_TOPIC.equals(topic);
            if (!organizationTopic && !organizationNative) return message; // preserve legacy subscriptions
            if (!(headers.getUser() instanceof UsernamePasswordAuthenticationToken auth) || !auth.isAuthenticated()) {
                throw new AccessDeniedException("WebSocket authentication required");
            }
            if (AppConstants.OrganizationRealtime.ADMIN_TOPIC.equals(topic) && auth.getAuthorities().stream()
                    .noneMatch(a -> a.getAuthority().equals("ROLE_" + AppConstants.Role.ADMIN))) {
                throw new AccessDeniedException("Admin-only organization events");
            }
            if (!organizationTopic) throw new AccessDeniedException("Unknown organization subscription");
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
