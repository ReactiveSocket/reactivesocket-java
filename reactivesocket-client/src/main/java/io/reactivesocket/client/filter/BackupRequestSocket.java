/**
 * Copyright 2016 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.reactivesocket.client.filter;

import io.reactivesocket.Payload;
import io.reactivesocket.ReactiveSocket;
import io.reactivesocket.client.util.Clock;
import io.reactivesocket.client.stat.FrugalQuantile;
import io.reactivesocket.client.stat.Quantile;
import io.reactivesocket.rx.Completable;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class BackupRequestSocket implements ReactiveSocket {
    private final ScheduledExecutorService executor;
    private final ReactiveSocket child;
    private final Quantile q;

    public BackupRequestSocket(ReactiveSocket child, double quantile, ScheduledExecutorService executor) {
        this.child = child;
        this.executor = executor;
        q = new FrugalQuantile(quantile);
    }

    public BackupRequestSocket(ReactiveSocket child, double quantile) {
        this(child, quantile, Executors.newScheduledThreadPool(2));
    }

    public BackupRequestSocket(ReactiveSocket child) {
        this(child, 0.99);
    }

    @Override
    public Publisher<Void> fireAndForget(Payload payload) {
        return child.fireAndForget(payload);
    }

    @Override
    public Publisher<Payload> requestResponse(Payload payload) {
        return subscriber -> {
            Subscriber<? super Payload> oneSubscriber = new OneSubscriber<>(subscriber);
            Subscriber<? super Payload> backupRequest =
                new FirstRequestSubscriber(oneSubscriber, () -> child.requestResponse(payload));
            child.requestResponse(payload).subscribe(backupRequest);
        };
    }

    @Override
    public Publisher<Payload> requestStream(Payload payload) {
        return child.requestStream(payload);
    }

    @Override
    public Publisher<Payload> requestSubscription(Payload payload) {
        return child.requestSubscription(payload);
    }

    @Override
    public Publisher<Payload> requestChannel(Publisher<Payload> payloads) {
        return child.requestChannel(payloads);
    }

    @Override
    public Publisher<Void> metadataPush(Payload payload) {
        return child.metadataPush(payload);
    }

    @Override
    public double availability() {
        return child.availability();
    }

    @Override
    public void start(Completable c) {
        child.start(c);
    }

    @Override
    public void onRequestReady(Consumer<Throwable> c) {
        child.onRequestReady(c);
    }

    @Override
    public void onRequestReady(Completable c) {
        child.onRequestReady(c);
    }

    @Override
    public void sendLease(int ttl, int numberOfRequests) {
        child.sendLease(ttl, numberOfRequests);
    }

    @Override
    public Publisher<Void> close() {
        return child.close();
    }

    @Override
    public Publisher<Void> onClose() {
        return child.onClose();
    }

    @Override
    public String toString() {
        return "BackupRequest(q=" + q + ")->" + child;
    }

    private class OneSubscriber<T> implements Subscriber<T> {
        private final Subscriber<T> subscriber;
        private final AtomicBoolean firstEvent;
        private final AtomicBoolean firstTerminal;

        private OneSubscriber(Subscriber<T> subscriber) {
            this.subscriber = subscriber;
            this.firstEvent = new AtomicBoolean(false);
            this.firstTerminal = new AtomicBoolean(false);
        }

        @Override
        public void onSubscribe(Subscription s) {
            subscriber.onSubscribe(s);
        }

        @Override
        public void onNext(T t) {
            if (firstEvent.compareAndSet(false, true)) {
                subscriber.onNext(t);
            }
        }

        @Override
        public void onError(Throwable t) {
            if (firstTerminal.compareAndSet(false, true)) {
                subscriber.onError(t);
            }
        }

        @Override
        public void onComplete() {
            if (firstTerminal.compareAndSet(false, true)) {
                subscriber.onComplete();
            }
        }
    }

    private class FirstRequestSubscriber implements Subscriber<Payload> {
        private final Subscriber<? super Payload> oneSubscriber;
        private Supplier<Publisher<Payload>> action;
        private long start;
        private ScheduledFuture<?> future;

        private FirstRequestSubscriber(Subscriber<? super Payload> oneSubscriber, Supplier<Publisher<Payload>> action) {
            this.oneSubscriber = oneSubscriber;
            this.action = action;
        }

        @Override
        public void onSubscribe(Subscription s) {
            start = Clock.now();
            if (q.estimation() > 0) {
                future = executor.schedule(() -> {
                    action.get().subscribe(new BackupRequestSubscriber<>(oneSubscriber, s));
                }, (long) q.estimation(), TimeUnit.MICROSECONDS);
            }
            oneSubscriber.onSubscribe(s);
        }

        @Override
        public void onNext(Payload t) {
            if (future != null) {
                future.cancel(true);
            }
            oneSubscriber.onNext(t);
            long latency = Clock.now() - start;
            q.insert(latency);
        }

        @Override
        public void onError(Throwable t) {
            oneSubscriber.onError(t);
        }

        @Override
        public void onComplete() {
            oneSubscriber.onComplete();
        }
    }

    private class BackupRequestSubscriber<T> implements Subscriber<T> {
        private final Subscriber<? super T> oneSubscriber;
        private Subscription firstRequestSubscription;
        private long start;

        private BackupRequestSubscriber(Subscriber<? super T> oneSubscriber, Subscription firstRequestSubscription) {
            this.oneSubscriber = oneSubscriber;
            this.firstRequestSubscription = firstRequestSubscription;
        }

        @Override
        public void onSubscribe(Subscription s) {
            start = Clock.now();
            s.request(1);
        }

        @Override
        public void onNext(T t) {
            firstRequestSubscription.cancel();
            oneSubscriber.onNext(t);
            long latency = Clock.now() - start;
            q.insert(latency);
        }

        @Override
        public void onError(Throwable t) {
            oneSubscriber.onError(t);
        }

        @Override
        public void onComplete() {
            oneSubscriber.onComplete();
        }
    }
}
