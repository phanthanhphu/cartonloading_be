package org.bsl.cartonloading.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.bsl.cartonloading.model.User;
import org.bsl.cartonloading.model.BuyerAccess;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.service.BuyerService;
import org.springframework.http.MediaType;

import org.bsl.cartonloading.service.UserService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtUtil jwtUtil;
    private final UserService userService;
    private final BuyerService buyerService;

    public JwtAuthenticationFilter(JwtUtil jwtUtil, UserService userService, BuyerService buyerService) {
        this.jwtUtil = jwtUtil;
        this.userService = userService;
        this.buyerService = buyerService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String authHeader = request.getHeader("Authorization");

        if (StringUtils.hasText(authHeader) && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7).trim();
            try {
                String email = jwtUtil.getEmailFromToken(token);
                Optional<User> userOpt = StringUtils.hasText(email) ? userService.findByEmail(email) : Optional.empty();

                if (userOpt.isPresent()) {
                    User user = userOpt.get();
                    String sessionBuyer = jwtUtil.getSelectedBuyer(token);
                    String sessionType = jwtUtil.getSelectedType(token);
                    if (user.isEnabled() && jwtUtil.validateToken(token, user.getEmail(), user.getTokenVersion())
                            && !sessionBuyer.isEmpty() && !sessionType.isEmpty()
                            && buyerService.supportsType(sessionBuyer, sessionType)
                            && user.canAccessBuyerType(sessionBuyer, sessionType)) {
                        if (!matchesRequestWorkspace(request, sessionBuyer, sessionType)) {
                            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.setCharacterEncoding("UTF-8");
                            response.getWriter().write("{\"message\":\"Bạn không có quyền truy cập Buyer hoặc Type này\"}");
                            return;
                        }

                        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
                        authorities.add(new SimpleGrantedAuthority("ROLE_" + user.getRole()));
                        for (String permission : user.getAccessPermissions()) {
                            authorities.add(new SimpleGrantedAuthority("PERM_" + permission));
                        }

                        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                                user.getEmail(), null, authorities);
                        authentication.setDetails(new WorkspaceSession(sessionBuyer, sessionType));
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                    } else {
                        SecurityContextHolder.clearContext();
                    }
                }
            } catch (Exception ignored) {
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }

    /** Bind every Buyer-scoped API to the Buyer selected at login, not to browser localStorage. */
    static boolean matchesRequestWorkspace(HttpServletRequest request, String buyer, String type) {
        String path = request.getRequestURI();
        if (path.startsWith("/api/buyers/")) {
            String segment = path.substring("/api/buyers/".length()).split("/", 2)[0];
            if (!segment.equals("login-options") && !segment.equals("accessible")) {
                String pathBuyer = BuyerAccess.normalize(segment);
                if (!pathBuyer.equals(buyer)) return false;
                if (path.contains("/lululemon/") && !(AppConstants.Buyer.LULULEMON.equals(buyer)
                        && org.bsl.cartonloading.model.SystemType.RFID.equals(type))) return false;
                if ((path.contains("/packing/") || path.contains("/factory-barcodes") || path.contains("/scale-stations")
                        || path.contains("/shipment-management") || path.contains("/sales/shipments") || path.contains("/packing-orders")
                        || path.endsWith("/orders")) && AppConstants.Buyer.ENGELBERT_STRAUSS.equals(buyer)
                        && !org.bsl.cartonloading.model.SystemType.FACTORY_USE_ONLY.equals(type)) return false;
            }
        }
        if (path.startsWith("/api/carton-loading")) return AppConstants.Buyer.ENGELBERT_STRAUSS.equals(buyer)
                && org.bsl.cartonloading.model.SystemType.FACTORY_USE_ONLY.equals(type);
        if (path.startsWith("/api/order-management/")) {
            String requestedBuyer = request.getParameter("buyer");
            return requestedBuyer != null && buyer.equals(BuyerAccess.normalize(requestedBuyer));
        }
        return true;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // These admin/docs routes require an authenticated SecurityContext even if
        // their paths end with a static extension such as /swagger-ui/index.html.
        if (path.startsWith("/v3/api-docs") || path.startsWith("/swagger-ui")
                || path.startsWith("/swagger-resources") || path.startsWith("/actuator/")) {
            return path.startsWith("/actuator/health");
        }
        return path.matches("^/error.*|/health$|/actuator/health(?:/.*)?$")
                || path.equals("/ws")
                || path.startsWith("/ws/")
                || path.startsWith("/assets/")
                || path.startsWith("/css/")
                || path.startsWith("/js/")
                || path.startsWith("/images/")
                || path.matches(".*\\.(css|js|png|jpg|jpeg|gif|webp|ico|svg|woff2?|ttf|eot|pdf|zip|gz|json|xml|txt|html)$")
                || "/favicon.ico".equals(path)
                || "/".equals(path)
                || "/index.html".equals(path);
    }
}
