package dev.harshaattili.reconledger;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.HashMap;
import java.util.Map;
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
    Admission concurrencyAdmission(@Value("${recon.concurrency-limit.capacity:8}") int capacity,
            @Value("${recon.concurrency-limit.per-tenant-capacity:${recon.concurrency-limit.capacity:8}}") int perTenantCapacity,
            TenantAccess tenants) {
        return new Admission(capacity, perTenantCapacity, tenants);
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
        private final int capacity;
        private final int perTenantCapacity;
        private final TenantAccess tenants;
        private final Map<String, Integer> activeByTenant = new HashMap<>();
        private int active;

        Admission(int capacity, int perTenantCapacity, TenantAccess tenants) {
            if (capacity < 1 || capacity > 1_000) {
                throw new IllegalArgumentException("Concurrency capacity must be 1..1000");
            }
            if (perTenantCapacity < 1 || perTenantCapacity > capacity) {
                throw new IllegalArgumentException("Per-tenant concurrency capacity must be 1..capacity");
            }
            this.capacity = capacity;
            this.perTenantCapacity = perTenantCapacity;
            this.tenants = tenants;
        }

        @Override public synchronized boolean preHandle(HttpServletRequest request,
                HttpServletResponse response, Object handler) {
            if (handler instanceof HandlerMethod method
                && (method.getBeanType() == ReconciliationController.class
                    || method.getBeanType() == ReviewSessionController.class)) {
                String tenant = tenants.current();
                int owned = activeByTenant.getOrDefault(tenant, 0);
                if (active == capacity || owned == perTenantCapacity) throw new Rejected();
                // Both counters change under one lock; a rejected tenant consumes neither.
                request.setAttribute(LEASE, tenant);
                activeByTenant.put(tenant, owned + 1);
                active++;
            }
            return true;
        }

        @Override public synchronized void afterCompletion(HttpServletRequest request,
                HttpServletResponse response, Object handler, Exception failure) {
            if (request.getAttribute(LEASE) instanceof String tenant) {
                request.removeAttribute(LEASE);
                int remaining = activeByTenant.get(tenant) - 1;
                if (remaining == 0) activeByTenant.remove(tenant);
                else activeByTenant.put(tenant, remaining);
                active--;
            }
        }
    }

    static final class Rejected extends RuntimeException { }
}
