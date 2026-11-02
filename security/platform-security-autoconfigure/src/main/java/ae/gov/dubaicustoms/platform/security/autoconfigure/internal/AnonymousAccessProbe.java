package ae.gov.dubaicustoms.platform.security.autoconfigure.internal;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletMapping;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.MappingMatch;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * Asks the built platform chain whether an anonymous caller may reach a path nobody would have named. If so, {@code permit-paths}
 * or a {@code SecurityCustomizer} has opened everything, for instance with {@code /**}, and the application does not start.
 * An application meant to be open says so with its own chain or {@code dc.platform.security.mode=disabled}.
 */
public final class AnonymousAccessProbe {

    private static final Logger log = LoggerFactory.getLogger(AnonymousAccessProbe.class);

    private static final HttpServletMapping MAPPING = new HttpServletMapping() {
        @Override
        public String getMatchValue() {
            return "";
        }

        @Override
        public String getPattern() {
            return "/";
        }

        @Override
        public String getServletName() {
            return "dispatcherServlet";
        }

        @Override
        public MappingMatch getMappingMatch() {
            return MappingMatch.DEFAULT;
        }
    };

    private AnonymousAccessProbe() {
    }

    /**
     * Fails if the chain lets an anonymous caller reach an unnamed path.
     *
     * @param chain the platform's built chain
     * @throws InvalidSecurityConfigurationException if it does
     */
    public static void check(SecurityFilterChain chain) {
        String path = "/platform-probe-" + UUID.randomUUID();
        AuthorizationResult result;
        try {
            AuthorizationFilter filter = authorizationFilter(chain.getFilters());
            result = filter == null ? null : filter.getAuthorizationManager().authorize(
                    () -> new AnonymousAuthenticationToken("probe", "anonymous", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))),
                    request(path));
        } catch (RuntimeException e) {
            // A custom matcher that cannot cope with this stand-in request. Not evidence of anything, so startup goes on.
            log.warn("could not check the security chain for open paths: {}", e.getClass().getSimpleName());
            return;
        }
        if (result != null && result.isGranted()) {
            throw new InvalidSecurityConfigurationException("dc.platform.security.permit-paths or a SecurityCustomizer opens every "
                    + "path to anonymous callers, which removes authenticated-by-default.",
                    "Permit only specific paths. If this application is meant to be open, define your own SecurityFilterChain.");
        }
    }

    private static AuthorizationFilter authorizationFilter(List<Filter> filters) {
        for (Filter filter : filters) {
            if (filter instanceof AuthorizationFilter found) {
                return found;
            }
        }
        return null;
    }

    private static HttpServletRequest request(String path) {
        Map<String, Object> attributes = new HashMap<>();
        return (HttpServletRequest) Proxy.newProxyInstance(AnonymousAccessProbe.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getMethod" -> "GET";
                    case "getRequestURI", "getServletPath" -> path;
                    case "getRequestURL" -> new StringBuffer("http://localhost" + path);
                    case "getContextPath" -> "";
                    case "getDispatcherType" -> DispatcherType.REQUEST;
                    case "getHttpServletMapping" -> MAPPING;
                    case "getAttribute" -> attributes.get(args[0]);
                    case "setAttribute" -> {
                        attributes.put((String) args[0], args[1]);
                        yield null;
                    }
                    case "removeAttribute" -> {
                        attributes.remove(args[0]);
                        yield null;
                    }
                    case "toString" -> "platform-probe";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        return null;
    }
}
