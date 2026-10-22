package dev.harshaattili.reconledger;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.LongSupplier;

/** Optional per-tenant burst budget layered ahead of the existing process budget. */
final class TenantRequestRateBudget {
    private final int capacity;
    private final long refillMillis;
    private final int maxTrackedTenants;
    private final LongSupplier clock;
    private final TenantAccess tenants;
    private final Map<String, RequestRateBudget> budgets = new HashMap<>();

    TenantRequestRateBudget(int capacity, long refillMillis, int maxTrackedTenants,
            LongSupplier clock, TenantAccess tenants) {
        if (capacity < 1 || capacity > 10_000 || refillMillis < 1 || refillMillis > 60_000) {
            throw new IllegalArgumentException(
                "Per-tenant rate capacity must be 1..10000 and refill-millis 1..60000");
        }
        if (maxTrackedTenants < 1 || maxTrackedTenants > 10_000) {
            throw new IllegalArgumentException("Tracked tenant capacity must be 1..10000");
        }
        this.capacity = capacity;
        this.refillMillis = refillMillis;
        this.maxTrackedTenants = maxTrackedTenants;
        this.clock = clock;
        this.tenants = tenants;
    }

    synchronized long acquire() {
        return acquire(tenants.current());
    }

    synchronized long acquire(String tenant) {
        RequestRateBudget budget = budgets.get(tenant);
        if (budget == null) {
            long retrySeconds = reclaimFullBuckets();
            if (budgets.size() == maxTrackedTenants) return retrySeconds;
            budget = new RequestRateBudget(capacity, refillMillis, clock);
            budgets.put(tenant, budget);
        }
        return budget.acquire();
    }

    synchronized int trackedTenants() { return budgets.size(); }

    /** Remove only buckets whose complete burst has returned; never reset a depleted tenant. */
    private long reclaimFullBuckets() {
        long soonest = Long.MAX_VALUE;
        Iterator<RequestRateBudget> iterator = budgets.values().iterator();
        while (iterator.hasNext()) {
            long delay = iterator.next().secondsUntilFull();
            if (delay == 0) iterator.remove();
            else soonest = Math.min(soonest, delay);
        }
        return soonest == Long.MAX_VALUE ? 1 : soonest;
    }
}
