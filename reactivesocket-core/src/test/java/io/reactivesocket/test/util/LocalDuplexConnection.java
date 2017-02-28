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

package io.reactivesocket.test.util;

import io.reactivesocket.DuplexConnection;
import io.reactivesocket.Frame;
import io.reactivesocket.reactivestreams.extensions.Px;
import io.reactivesocket.reactivestreams.extensions.internal.EmptySubject;
import io.reactivex.Flowable;
import io.reactivex.processors.PublishProcessor;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class LocalDuplexConnection implements DuplexConnection {
    private final PublishProcessor<Frame> send;
    private final PublishProcessor<Frame> receive;
    private final EmptySubject closeNotifier;
    private final String name;

    public LocalDuplexConnection(String name, PublishProcessor<Frame> send, PublishProcessor<Frame> receive) {
        this.name = name;
        this.send = send;
        this.receive = receive;
        closeNotifier = new EmptySubject();
    }

    @Override
    public Mono<Void> send(Publisher<Frame> frame) {
        return Mono
            .from(frame)
            .doOnNext(send::onNext)
            .doOnError(send::onError)
            .then();
    }

    @Override
    public Flux<Frame> receive() {
        return Flux.from(receive);
    }

    @Override
    public double availability() {
        return 1;
    }

    @Override
    public Mono<Void> close() {
        return Mono.defer(() -> {
            closeNotifier.onComplete();
            return Mono.empty();
        });
    }

    @Override
    public Mono<Void> onClose() {
        return Mono.from(closeNotifier);
    }
}
