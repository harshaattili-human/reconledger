package dev.harshaattili.reconledger;

import static org.assertj.core.api.Assertions.*;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

class ConcurrencyAdmissionTest {
    private final HandlerMethod handler = new HandlerMethod(
        new ReconciliationController(null, null, null),
        ReconciliationController.class.getMethod("batch", String.class));

    ConcurrencyAdmissionTest() throws Exception { }

    @Test void completionReleasesExactlyOnceAndUnmappedHandlersDoNotAcquire() {
        var admission = new ApiConcurrencyLimit.Admission(1);
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
        var admission = new ApiConcurrencyLimit.Admission(3);
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
        var context = new ApplicationContextRunner().withUserConfiguration(ApiConcurrencyLimit.class);
        context.run(app -> assertThat(app).doesNotHaveBean(ApiConcurrencyLimit.Admission.class));
        context.withPropertyValues("recon.concurrency-limit.enabled=true")
            .run(app -> assertThat(app).hasSingleBean(ApiConcurrencyLimit.Admission.class));
        for (int invalid : new int[]{-1, 0, 1001}) {
            context.withPropertyValues("recon.concurrency-limit.enabled=true", "recon.concurrency-limit.capacity=" + invalid)
                .run(app -> assertThat(app).hasFailed());
        }
        assertThatCode(() -> new ApiConcurrencyLimit.Admission(1000)).doesNotThrowAnyException();
    }
}
