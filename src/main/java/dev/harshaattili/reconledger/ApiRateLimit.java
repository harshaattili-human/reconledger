package dev.harshaattili.reconledger;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "recon.rate-limit.enabled", havingValue = "true")
class ApiRateLimit {
    @Bean
    RequestRateBudget requestRateBudget(
            @Value("${recon.rate-limit.capacity:20}") int capacity,
            @Value("${recon.rate-limit.refill-millis:100}") long refillMillis) {
        return new RequestRateBudget(capacity, refillMillis, System::nanoTime);
    }

    @Bean
    @ConditionalOnProperty(name = "recon.rate-limit.per-tenant-enabled", havingValue = "true")
    TenantRequestRateBudget tenantRequestRateBudget(
            @Value("${recon.rate-limit.capacity:20}") int globalCapacity,
            @Value("${recon.rate-limit.per-tenant-capacity:${recon.rate-limit.capacity:20}}") int capacity,
            @Value("${recon.rate-limit.per-tenant-refill-millis:${recon.rate-limit.refill-millis:100}}") long refillMillis,
            @Value("${recon.rate-limit.max-tracked-tenants:1000}") int maxTrackedTenants,
            TenantAccess tenants) {
        if (capacity > globalCapacity) {
            throw new IllegalArgumentException("Per-tenant rate capacity must not exceed global capacity");
        }
        return new TenantRequestRateBudget(capacity, refillMillis, maxTrackedTenants,
            System::nanoTime, tenants);
    }

    @Bean
    WebMvcConfigurer rateLimitMapping(RequestRateBudget budget,
            ObjectProvider<TenantRequestRateBudget> tenantBudgetProvider) {
        return new WebMvcConfigurer() {
            @Override public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new HandlerInterceptor() {
                    @Override public boolean preHandle(HttpServletRequest request,
                            HttpServletResponse response, Object handler) {
                        // Authentication/scopes remain in Spring Security, before MVC.
                        // Select actual API controllers, excluding health, assets and errors.
                        if (handler instanceof HandlerMethod method
                            && (method.getBeanType() == ReconciliationController.class
                                || method.getBeanType() == ReviewSessionController.class)) {
                            TenantRequestRateBudget tenantBudget = tenantBudgetProvider.getIfAvailable();
                            if (tenantBudget != null) {
                                long retrySeconds = tenantBudget.acquire();
                                if (retrySeconds != 0) throw new Rejected(retrySeconds);
                            }
                            long retrySeconds = budget.acquire();
                            if (retrySeconds != 0) throw new Rejected(retrySeconds);
                        }
                        return true;
                    }
                });
            }
        };
    }

    static final class Rejected extends RuntimeException {
        final long retrySeconds;
        Rejected(long retrySeconds) { this.retrySeconds = retrySeconds; }
    }
}
