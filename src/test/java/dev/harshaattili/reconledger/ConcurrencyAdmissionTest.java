package dev.harshaattili.reconledger;

import static org.assertj.core.api.Assertions.*;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.method.HandlerMethod;

class ConcurrencyAdmissionTest {
    private final HandlerMethod handler = new HandlerMethod(
        new ReconciliationController(null, null, null),
        ReconciliationController.class.getMethod("batch", String.class));

    ConcurrencyAdmissionTest() throws Exception { }

    @Test void completionReleasesExactlyOnceAndUnmappedHandlersDoNotAcquire() {
        var admission = demoAdmission(1);
        var response = new MockHttpServletResponse();
        var first = new MockHttpServletRequest();
        var rejected = new MockHttpServletRequest();
        assertThat(admission.preHandle(first, response, handler)).isTrue();
        assertThat(admission.preHandle(new MockHttpServletRequest(), response, new Object())).isTrue();
        assertThatThrownBy(() -> admission.preHandle(rejected, response, handler))
            .isInstanceOf(ApiConcurrencyLimit.Rejected.class);
        admission.afterCompletion(rejected, response, handler, null);
        assertThatThrownBy(() -> admission.preHandle(rejected, response, handler))
            .isInstanceOf(ApiConcurrencyLimit.Rejected.class);
        admission.afterCompletion(first, response, handler, new RuntimeException("synthetic"));
        admission.afterCompletion(first, response, handler, null);
        assertThat(admission.preHandle(rejected, response, handler)).isTrue();
        assertThatThrownBy(() -> admission.preHandle(first, response, handler))
            .isInstanceOf(ApiConcurrencyLimit.Rejected.class);
        admission.afterCompletion(rejected, response, handler, null);
    }

    @Test void simultaneousAdmissionsCannotExceedCapacityAndAllSlotsRecover() throws Exception {
        var admission = demoAdmission(3);
        var ready = new CountDownLatch(12);
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(12);
        var requests = new ArrayList<MockHttpServletRequest>();
        try {
            var results = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 12; i++) {
                var request = new MockHttpServletRequest();
                requests.add(request);
                results.add(pool.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    try { return admission.preHandle(request, new MockHttpServletResponse(), handler); }
                    catch (ApiConcurrencyLimit.Rejected rejected) { return false; }
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int admitted = 0;
            for (var result : results) if (result.get(5, TimeUnit.SECONDS)) admitted++;
            assertThat(admitted).isEqualTo(3);
            for (var request : requests) admission.afterCompletion(request, new MockHttpServletResponse(), handler, null);
            for (int i = 0; i < 3; i++) assertThat(admission.preHandle(requests.get(i), new MockHttpServletResponse(), handler)).isTrue();
            assertThatThrownBy(() -> admission.preHandle(requests.get(3), new MockHttpServletResponse(), handler))
                .isInstanceOf(ApiConcurrencyLimit.Rejected.class);
        } finally { start.countDown(); pool.shutdownNow(); }
    }

    @Test void configurationIsOptInAndValidatesEnabledCapacity() {
        var context = new ApplicationContextRunner().withUserConfiguration(ApiConcurrencyLimit.class)
            .withBean(TenantAccess.class, () -> new TenantAccess(new MockEnvironment()));
        context.run(app -> assertThat(app).doesNotHaveBean(ApiConcurrencyLimit.Admission.class));
        context.withPropertyValues("recon.concurrency-limit.enabled=true")
            .run(app -> assertThat(app).hasSingleBean(ApiConcurrencyLimit.Admission.class));
        for (int invalid : new int[]{-1, 0, 1001}) {
            context.withPropertyValues("recon.concurrency-limit.enabled=true", "recon.concurrency-limit.capacity=" + invalid)
                .run(app -> assertThat(app).hasFailed());
        }
        assertThatCode(() -> demoAdmission(1000)).doesNotThrowAnyException();
        for (int invalid : new int[]{-1, 0, 4}) {
            context.withPropertyValues("recon.concurrency-limit.enabled=true", "recon.concurrency-limit.capacity=3",
                    "recon.concurrency-limit.per-tenant-capacity=" + invalid)
                .run(app -> assertThat(app).hasFailed());
        }
        context.withPropertyValues("recon.concurrency-limit.enabled=true", "recon.concurrency-limit.capacity=3",
                "recon.concurrency-limit.per-tenant-capacity=2")
            .run(app -> assertThat(app).hasSingleBean(ApiConcurrencyLimit.Admission.class));
        // Omission inherits the configured global capacity, not the default eight.
        context.withPropertyValues("recon.concurrency-limit.enabled=true", "recon.concurrency-limit.capacity=1")
            .run(app -> assertThat(app).hasSingleBean(ApiConcurrencyLimit.Admission.class));
    }

    @Test void tenantCountsDisappearOnCompletionAndReleaseUsesTheAcquiredIdentity() {
        var admission = securedAdmission(2, 1);
        var response = new MockHttpServletResponse();
        try {
            for (int i = 0; i < 100; i++) {
                identify("tenant-" + i);
                var request = new MockHttpServletRequest();
                admission.preHandle(request, response, handler);
                assertThat(activeTenants(admission)).hasSize(1);
                identify("different-context");
                admission.afterCompletion(request, response, handler, null);
                admission.afterCompletion(request, response, handler, null);
                assertThat(activeTenants(admission)).isEmpty();
            }
        } finally { SecurityContextHolder.clearContext(); }
    }

    @Test void simultaneousTenantsRespectBothCeilingsWithoutRetainingRejectedIdentities() throws Exception {
        var admission = securedAdmission(3, 2);
        var ready = new CountDownLatch(12);
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(12);
        var requests = new ArrayList<MockHttpServletRequest>();
        try {
            var results = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 12; i++) {
                String tenant = i < 6 ? "tenant-a" : "tenant-b";
                var request = new MockHttpServletRequest();
                requests.add(request);
                results.add(pool.submit(() -> {
                    identify(tenant);
                    try {
                        ready.countDown();
                        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                        try { return admission.preHandle(request, new MockHttpServletResponse(), handler); }
                        catch (ApiConcurrencyLimit.Rejected rejected) { return false; }
                    } finally { SecurityContextHolder.clearContext(); }
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int admitted = 0;
            for (var result : results) if (result.get(5, TimeUnit.SECONDS)) admitted++;
            assertThat(admitted).isEqualTo(3);
            assertThat(activeTenants(admission)).hasSize(2);
            assertThat(activeTenants(admission).values()).allMatch(count -> count >= 1 && count <= 2);
            identify("rejected-new-tenant");
            assertThatThrownBy(() -> admission.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), handler))
                .isInstanceOf(ApiConcurrencyLimit.Rejected.class);
            assertThat(activeTenants(admission)).doesNotContainKey("rejected-new-tenant");
            for (var request : requests) admission.afterCompletion(request, new MockHttpServletResponse(), handler, null);
            assertThat(activeTenants(admission)).isEmpty();
            identify("tenant-a");
            admission.preHandle(requests.get(0), new MockHttpServletResponse(), handler);
            admission.preHandle(requests.get(1), new MockHttpServletResponse(), handler);
            identify("tenant-b");
            admission.preHandle(requests.get(2), new MockHttpServletResponse(), handler);
            assertThat(activeTenants(admission)).containsEntry("tenant-a", 2).containsEntry("tenant-b", 1);
        } finally { start.countDown(); pool.shutdownNow(); SecurityContextHolder.clearContext(); }
    }

    @Test void missingVerifiedContextCannotAcquireOrCreateTenantState() {
        SecurityContextHolder.clearContext();
        var admission = securedAdmission(2, 1);
        assertThatThrownBy(() -> admission.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), handler))
            .isInstanceOf(ApiException.class);
        assertThat(activeTenants(admission)).isEmpty();
    }

    private static ApiConcurrencyLimit.Admission demoAdmission(int capacity) {
        return new ApiConcurrencyLimit.Admission(capacity, capacity, new TenantAccess(new MockEnvironment()));
    }
    private static ApiConcurrencyLimit.Admission securedAdmission(int capacity, int perTenant) {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("secured");
        return new ApiConcurrencyLimit.Admission(capacity, perTenant, new TenantAccess(environment));
    }
    private static void identify(String tenant) {
        // Trusted unit context only; HTTP cases below use actual signature verification.
        var jwt = Jwt.withTokenValue("synthetic").header("alg", "RS256").subject("reviewer")
            .claim("tenant_id", tenant).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, java.util.List.of()));
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Integer> activeTenants(ApiConcurrencyLimit.Admission admission) {
        return (Map<String, Integer>) ReflectionTestUtils.getField(admission, "activeByTenant");
    }
}
