package spikes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;

/**
 * S6: method security switched on by a platform auto-configuration (the service adds nothing), a
 * {@code @PreAuthorize} on an implementing method honoured when the bean is called through an interface (JDK)
 * proxy, a stricter method rule winning over a weaker class rule, and enumeration of guarded methods at startup.
 */
@SpringBootTest(classes = S6MethodSecurityTest.App.class, webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.aop.proxy-target-class=false")
class S6MethodSecurityTest {

    /** Stands in for a platform auto-configuration class. */
    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class PlatformMethodSecurity {
    }

    interface Orders {
        String read();

        String delete();
    }

    @PreAuthorize("permitAll()")
    static class OrdersImpl implements Orders {
        @Override
        @PreAuthorize("hasAuthority('orders:read')")
        public String read() {
            return "order";
        }

        @Override
        @PreAuthorize("hasAuthority('orders:delete')")
        public String delete() {
            return "deleted";
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(PlatformMethodSecurity.class)
    static class App {
        @Bean
        Orders orders() {
            return new OrdersImpl();
        }
    }

    @Autowired
    Orders orders;

    @Autowired
    ApplicationContext context;

    @Test
    void beanIsAnInterfaceProxy() {
        assertThat(AopUtils.isJdkDynamicProxy(orders)).isTrue();
    }

    @Test
    @WithMockUser(authorities = "orders:read")
    void implementingMethodRuleIsHonouredThroughTheInterfaceProxy() {
        assertThat(orders.read()).isEqualTo("order");
        assertThatThrownBy(orders::delete).isInstanceOf(AuthorizationDeniedException.class);
    }

    @Test
    @WithMockUser(authorities = "something:else")
    void weakerClassRuleDoesNotOverrideTheMethodRule() {
        assertThatThrownBy(orders::read).isInstanceOf(AuthorizationDeniedException.class);
    }

    @Test
    void guardedMethodsCanBeEnumeratedAtStartup() {
        List<String> guarded = new ArrayList<>();
        Object bean = context.getBean("orders");
        for (Method m : AopUtils.getTargetClass(bean).getDeclaredMethods()) {
            PreAuthorize rule = AnnotatedElementUtils.findMergedAnnotation(m, PreAuthorize.class);
            if (rule != null) {
                guarded.add(m.getName() + "=" + rule.value());
            }
        }
        assertThat(guarded).containsExactlyInAnyOrder("read=hasAuthority('orders:read')",
                "delete=hasAuthority('orders:delete')");
    }
}
