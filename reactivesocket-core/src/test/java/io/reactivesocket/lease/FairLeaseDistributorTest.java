/*
 * Copyright 2016 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.reactivesocket.lease;

import io.reactivesocket.reactivestreams.extensions.internal.Cancellable;
import io.reactivex.processors.PublishProcessor;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExternalResource;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;

public class FairLeaseDistributorTest {

    @Rule
    public final DistributorRule rule = new DistributorRule();

    @Test
    public void testRegisterCancel() throws Exception {
        Cancellable cancel = rule.distributor.registerSocket(rule);
        rule.ticks.onNext(1L);
        assertThat("Unexpected leases received.", rule.leases, hasSize(1));
        Lease lease = rule.leases.remove(0);
        assertThat("Unexpected permits", lease.getAllowedRequests(), is(rule.permits));
        assertThat("Unexpected ttl", lease.getTtl(), is(rule.ttl));
        cancel.cancel();
        rule.ticks.onNext(1L);
        assertThat("Unexpected leases received post cancellation.", rule.leases, is(empty()));
    }

    @Test
    public void testTwoSockets() throws Exception {
        rule.permits = 2;
        rule.distributor.registerSocket(rule);
        rule.distributor.registerSocket(rule);
        rule.ticks.onNext(1L);
        assertThat("Unexpected leases received.", rule.leases, hasSize(2));
        rule.assertLease(rule.permits/2);
        rule.assertLease(rule.permits/2);
    }

    @Test
    public void testTwoSocketsAndCancel() throws Exception {
        rule.permits = 2;
        Cancellable cancel1 = rule.distributor.registerSocket(rule);
        rule.distributor.registerSocket(rule);
        rule.ticks.onNext(1L);
        assertThat("Unexpected leases received.", rule.leases, hasSize(2));
        rule.assertLease(rule.permits/2);
        rule.assertLease(rule.permits/2);
        cancel1.cancel();
        rule.ticks.onNext(1L);
        assertThat("Unexpected leases received.", rule.leases, hasSize(1));
    }

    public static class DistributorRule extends ExternalResource implements Consumer<Lease> {

        private FairLeaseDistributor distributor;
        private int permits;
        private int ttl;
        private PublishProcessor<Long> ticks;
        private CopyOnWriteArrayList<Lease> leases;

        @Override
        public Statement apply(final Statement base, Description description) {
            return new Statement() {
                @Override
                public void evaluate() throws Throwable {
                    ticks = PublishProcessor.create();
                    if (0 == permits) {
                        permits = 1;
                    }
                    if (0 == ttl) {
                        ttl = 10;
                    }
                    distributor = new FairLeaseDistributor(() -> permits, ttl, ticks);
                    leases = new CopyOnWriteArrayList<>();
                    base.evaluate();
                }
            };
        }

        @Override
        public void accept(Lease lease) {
            leases.add(lease);
        }

        public void assertLease(int expectedPermits) {
            Lease lease = leases.remove(0);
            assertThat("Unexpected permits", lease.getAllowedRequests(), is(expectedPermits));
            assertThat("Unexpected ttl", lease.getTtl(), is(ttl));
        }
    }
}