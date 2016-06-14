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
package io.reactivesocket.local;

import io.reactivesocket.DuplexConnection;
import io.reactivesocket.Frame;
import io.reactivesocket.rx.Completable;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.publisher.DirectProcessor;

import java.io.IOException;

class LocalClientDuplexConnection implements DuplexConnection {
    private final String name;

    private final DirectProcessor<Frame> directProcessor;

    public LocalClientDuplexConnection(String name) {
        this.name = name;
        this.directProcessor = DirectProcessor.create();
    }

    @Override
    public Publisher<Frame> getInput() {
        return directProcessor;
    }

    @Override
    public void addOutput(Publisher<Frame> o, Completable callback) {

        o
            .subscribe(new Subscriber<Frame>() {

                @Override
                public void onSubscribe(Subscription s) {
                    s.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(Frame frame) {
                    try {
                        LocalReactiveSocketManager
                            .getInstance()
                            .getServerConnection(name)
                            .write(frame);
                    } catch (Throwable t) {
                        onError(t);
                    }
                }

                @Override
                public void onError(Throwable t) {
                    callback.error(t);
                }

                @Override
                public void onComplete() {
                    callback.success();
                }
            });
    }

    @Override
    public double availability() {
        return 1.0;
    }

    void write(Frame frame) {
        if (directProcessor.hasDownstreams()) {
            directProcessor
                .onNext(frame);
        }
    }

    @Override
    public void close() throws IOException {
        LocalReactiveSocketManager
            .getInstance()
            .removeClientConnection(name);

        directProcessor.onComplete();
    }
}
