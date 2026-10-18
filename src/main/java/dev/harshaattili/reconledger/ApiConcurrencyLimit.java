package dev.harshaattili.reconledger;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Admission for the current synchronous MVC controllers, not a servlet thread limit. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "recon.concurrency-limit.enabled", havingValue = "true")
class ApiConcurrencyLimit {
    @Bean
    Admission concurrencyAdmission(@Value("${recon.concurrency-limit.capacity:8}") int capacity) {
        return new Admission(capacity);
    }

    @Bean
    WebMvcConfigurer concurrencyMapping(Admission admission) {
        return new WebMvcConfigurer() {
            @Override public void addInterceptors(InterceptorRegistry registry) {
                // Before the rate interceptor: saturation does not spend a rate token.
                registry.addInterceptor(admission).order(-10);
            }
        };
    }

    static final class Admission implements HandlerInterceptor {
        private static final String LEASE = Admission.class.getName() + ".lease";
        private final Semaphore slots;

        Admission(int capacity) {
            if (capacity < 1 || capacity > 1_000) {
                throw new IllegalArgumentException("Concurrency capacity must be 1..1000");
            }
            slots = new Semaphore(capacity);
        }

        @Override public boolean preHandle(HttpServletRequest request,
                HttpServletResponse response, Object handler) {
            if (handler instanceof HandlerMethod method
                && (method.getBeanType() == ReconciliationController.class
                    || method.getBeanType() == ReviewSessionController.class)) {
                if (!slots.tryAcquire()) throw new Rejected();
                request.setAttribute(LEASE, Boolean.TRUE);
            }
            return true;
        }

        @Override public void afterCompletion(HttpServletRequest request,
                HttpServletResponse response, Object handler, Exception failure) {
            if (request.getAttribute(LEASE) != null) {
                request.removeAttribute(LEASE);
                slots.release();
            }
        }
    }

    static final class Rejected extends RuntimeException { }
}
