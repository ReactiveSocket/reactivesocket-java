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

package io.reactivesocket.client;

import io.reactivesocket.ReactiveSocket;
import io.reactivesocket.transport.TransportClient;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

/**
 * Default implementation of {@link ReactiveSocketClient} providing the functionality to create a {@link ReactiveSocket}
 * from a {@link TransportClient}.
 */
public final class DefaultReactiveSocketClient extends AbstractReactiveSocketClient {

    private final Flux<ReactiveSocket> connectSource;

    public DefaultReactiveSocketClient(TransportClient transportClient, SetupProvider setupProvider,
                                       SocketAcceptor acceptor) {
        super(setupProvider);
        connectSource = transportClient.connect()
            .flatMap(connection -> setupProvider.accept(connection, acceptor));
    }

    @Override
    public Publisher<? extends ReactiveSocket> connect() {
        return connectSource;
    }

    @Override
    public double availability() {
        return 1.0; // Client is always available unless wrapped with filters.
    }
}
