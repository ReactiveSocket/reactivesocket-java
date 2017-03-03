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

package io.reactivesocket.internal;

import io.reactivesocket.DuplexConnection;
import io.reactivesocket.Frame;
import org.agrona.BitUtil;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoProcessor;

/**
 * {@link DuplexConnection#receive()} is a single stream on which the following type of frames arrive:
 * <ul>
 <li>Frames for streams initiated by the initiator of the connection (client).</li>
 <li>Frames for streams initiated by the acceptor of the connection (server).</li>
 </ul>
 *
 * The only way to differentiate these two frames is determining whether the stream Id is odd or even. Even IDs are
 * for the streams initiated by server and odds are for streams initiated by the client. <p>
 */
public class ClientServerInputMultiplexer {

    private final InternalDuplexConnection streamZeroConnection;
    private final InternalDuplexConnection serverConnection;
    private final InternalDuplexConnection clientConnection;

    private enum Type { ZERO, CLIENT, SERVER }

    public ClientServerInputMultiplexer(DuplexConnection source) {
        final MonoProcessor<Flux<Frame>> streamZero = MonoProcessor.create();
        final MonoProcessor<Flux<Frame>> server = MonoProcessor.create();
        final MonoProcessor<Flux<Frame>> client = MonoProcessor.create();

        streamZeroConnection = new InternalDuplexConnection(source, streamZero);
        serverConnection = new InternalDuplexConnection(source, server);
        clientConnection = new InternalDuplexConnection(source, client);

        source.receive()
            .groupBy(frame -> {
                int streamId = frame.getStreamId();
                Type type;
                if (streamId == 0) {
                    type = Type.ZERO;
                } else if (BitUtil.isEven(streamId)) {
                    type = Type.SERVER;
                } else {
                    type = Type.CLIENT;
                }
                return type;
            })
            .subscribe(group -> {
                switch (group.key()) {
                    case ZERO:
                        streamZero.onNext(group);
                        break;
                    case SERVER:
                        server.onNext(group);
                        break;
                    case CLIENT:
                        client.onNext(group);
                        break;
                }
            });
    }

    public DuplexConnection asServerConnection() {
        return serverConnection;
    }

    public DuplexConnection asClientConnection() {
        return clientConnection;
    }

    public DuplexConnection asStreamZeroConnection() {
        return streamZeroConnection;
    }

    private static class InternalDuplexConnection implements DuplexConnection {
        private final DuplexConnection source;
        private final MonoProcessor<Flux<Frame>> processor;

        public InternalDuplexConnection(DuplexConnection source, MonoProcessor<Flux<Frame>> processor) {
            this.source = source;
            this.processor = processor;
        }

        @Override
        public Mono<Void> send(Publisher<Frame> frame) {
            return source.send(frame);
        }

        @Override
        public Mono<Void> sendOne(Frame frame) {
            return source.sendOne(frame);
        }

        @Override
        public Flux<Frame> receive() {
            return processor.flatMap(f -> f);
        }

        @Override
        public Mono<Void> close() {
            return source.close();
        }

        @Override
        public Mono<Void> onClose() {
            return source.onClose();
        }

        @Override
        public double availability() {
            return source.availability();
        }
    }

}
