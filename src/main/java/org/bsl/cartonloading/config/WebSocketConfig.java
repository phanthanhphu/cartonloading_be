package org.bsl.cartonloading.config;

import org.springframework.context.annotation.Configuration;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.security.OrganizationStompAuthInterceptor;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import java.util.Map;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    private final OrganizationStompAuthInterceptor authInterceptor;

    public WebSocketConfig(OrganizationStompAuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authInterceptor);
    }


    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .withSockJS();
        // Native STOMP endpoint; original SockJS /ws endpoint remains compatible.
        registry.addEndpoint(AppConstants.OrganizationRealtime.NATIVE_ENDPOINT)
                .addInterceptors(new HandshakeInterceptor() {
                    @Override
                    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                                   WebSocketHandler handler, Map<String, Object> attributes) {
                        attributes.put(AppConstants.OrganizationRealtime.NATIVE_SESSION_MARKER, true);
                        return true;
                    }
                    @Override
                    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                               WebSocketHandler handler, Exception exception) { }
                })
                .setAllowedOriginPatterns("http://localhost:[*]", "http://127.0.0.1:[*]",
                        "http://10.*:[*]", "http://192.168.*:[*]", "http://172.*:[*]",
                        "https://homepage.youngone.com.vn", "https://homepage.youngone.com.vn:[*]");
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }
}