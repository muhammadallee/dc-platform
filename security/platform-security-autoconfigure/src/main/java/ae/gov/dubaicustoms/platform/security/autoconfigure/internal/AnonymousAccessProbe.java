package ae.gov.dubaicustoms.platform.security.autoconfigure.internal;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletMapping;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.MappingMatch;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
 * Asks the built platform chain whether an anonymous caller may reach a path nobody would have named, two segments deep, with each
 * method. If so, {@code permit-paths} or a {@code SecurityCustomizer} has opened everything, for instance with {@code /**} or
 * {@code (POST, "/**")}, and the application does not start. A matcher that cannot answer for the stand-in request fails startup
 * too, since the check would otherwise prove nothing. An application meant to be open says so with its own chain or
 * {@code dc.platform.security.mode=disabled}.
 *
 * <p>A heuristic, not a proof: a rule that opens many paths without opening an unnamed one, such as {@code /api/**}, is not seen.
 */
public final class AnonymousAccessProbe {

    private static final Logger log = LoggerFactory.getLogger(AnonymousAccessProbe.class);
    private static final List<String> METHODS = List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE");

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
     * @param servletContext the application's servlet context, for matchers that need it; may be {@code null}
     * @param skip skip the check, with a warning
     * @throws InvalidSecurityConfigurationException if it does
     */
    public static void check(SecurityFilterChain chain, ServletContext servletContext, boolean skip) {
        AuthorizationFilter filter = authorizationFilter(chain.getFilters());
        if (filter == null) {
            return;
        }
        if (skip) {
            log.warn("dc.platform.security.skip-open-path-check is set: the platform does not check its chain for open paths");
            return;
        }
        // Two depths, both open: a rule such as /*/* opens one depth, and is not everything.
        String id = UUID.randomUUID().toString();
        List<String> paths = List.of("/platform-probe-" + id + "/unnamed", "/platform-probe-" + id + "/a/b/c/unnamed");
        for (String method : METHODS) {
            boolean open = true;
            for (String path : paths) {
                open &= granted(filter, method, path, servletContext);
            }
            if (open) {
                throw new InvalidSecurityConfigurationException("dc.platform.security.permit-paths or a SecurityCustomizer opens every "
                        + "path to anonymous " + method + " requests, which removes authenticated-by-default.",
                        "Permit only specific paths. If this application is meant to be open, define your own SecurityFilterChain.");
            }
        }
    }

    private static boolean granted(AuthorizationFilter filter, String method, String path, ServletContext servletContext) {
        try {
            AuthorizationResult result = filter.getAuthorizationManager().authorize(
                    () -> new AnonymousAuthenticationToken("probe", "anonymous", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))),
                    request(method, path, servletContext));
            return result != null && result.isGranted();
        } catch (RuntimeException e) {
            log.debug("security chain probe failed", e);
            throw new InvalidSecurityConfigurationException("A request matcher in the platform security chain failed on a check "
                    + "request (" + e.getClass().getSimpleName() + "), so it cannot be shown that the chain is not open to "
                    + "anonymous callers.", "Use path or method matchers in SecurityCustomizer rules, define your own "
                    + "SecurityFilterChain, or, having checked the chain yourself, set dc.platform.security.skip-open-path-check=true.");
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

    private static HttpServletRequest request(String httpMethod, String path, ServletContext servletContext) {
        Map<String, Object> attributes = new HashMap<>();
        return (HttpServletRequest) Proxy.newProxyInstance(AnonymousAccessProbe.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getMethod" -> httpMethod;
                    case "getServletContext" -> servletContext;
                    case "getRequestURI", "getServletPath" -> path;
                    case "getRequestURL" -> new StringBuffer("http://localhost" + path);
                    case "getContextPath" -> "";
                    case "getScheme" -> "http";
                    case "getServerName", "getRemoteHost", "getLocalName" -> "localhost";
                    case "getRemoteAddr", "getLocalAddr" -> "127.0.0.1";
                    case "getServerPort", "getLocalPort" -> 80;
                    case "getProtocol" -> "HTTP/1.1";
                    case "getHeaderNames", "getHeaders", "getAttributeNames", "getParameterNames" -> Collections.emptyEnumeration();
                    case "getParameterMap" -> Map.of();
                    case "getCharacterEncoding" -> "UTF-8";
                    case "getLocale" -> Locale.ROOT;
                    case "getLocales" -> Collections.enumeration(List.of(Locale.ROOT));
                    case "getDateHeader", "getContentLengthLong" -> -1L;
                    case "getIntHeader", "getContentLength" -> -1;
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
