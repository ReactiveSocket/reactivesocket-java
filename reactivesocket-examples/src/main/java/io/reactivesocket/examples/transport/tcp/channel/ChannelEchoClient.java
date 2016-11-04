/*
 * Copyright 2016 Netflix, Inc.
 * <p>
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 *  the License. You may obtain a copy of the License at
 *  <p>
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  <p>
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 *  specific language governing permissions and limitations under the License.
 */

package io.reactivesocket.examples.transport.tcp.channel;

import io.reactivesocket.AbstractReactiveSocket;
import io.reactivesocket.ConnectionSetupPayload;
import io.reactivesocket.Payload;
import io.reactivesocket.ReactiveSocket;
import io.reactivesocket.client.ReactiveSocketClient;
import io.reactivesocket.frame.ByteBufferUtil;
import io.reactivesocket.lease.DisabledLeaseAcceptingSocket;
import io.reactivesocket.lease.LeaseEnforcingSocket;
import io.reactivesocket.server.ReactiveSocketServer;
import io.reactivesocket.server.ReactiveSocketServer.SocketAcceptor;
import io.reactivesocket.transport.TransportServer.StartedServer;
import io.reactivesocket.transport.tcp.client.TcpTransportClient;
import io.reactivesocket.transport.tcp.server.TcpTransportServer;
import io.reactivesocket.util.PayloadImpl;
import io.reactivesocket.util.ReactiveSocketDecorator;
import io.reactivex.Flowable;
import org.reactivestreams.Publisher;

import java.net.SocketAddress;
import java.util.concurrent.TimeUnit;

import static io.reactivesocket.client.KeepAliveProvider.*;
import static io.reactivesocket.client.SetupProvider.*;

public final class ChannelEchoClient {

    public static void main(String[] args) {
        StartedServer server = ReactiveSocketServer.create(TcpTransportServer.create())
                                                   .start(new SocketAcceptorImpl());

        SocketAddress address = server.getServerAddress();
        ReactiveSocket socket = Flowable.fromPublisher(ReactiveSocketClient.create(TcpTransportClient.create(address),
                                                                                   keepAlive(never()).disableLease())
                                                                           .connect())
                                        .blockingFirst();

        Flowable.fromPublisher(socket.requestChannel(Flowable.interval(0, 100, TimeUnit.MILLISECONDS)
                                                             .map(i -> "Hello - " + i)
                                                             .<Payload>map(PayloadImpl::new)
                                                             .repeat()))
                .map(payload -> payload.getData())
                .map(ByteBufferUtil::toUtf8String)
                .doOnNext(System.out::println)
                .take(10)
                .concatWith(Flowable.fromPublisher(socket.close()).cast(String.class))
                .blockingLast();
    }

    private static class SocketAcceptorImpl implements SocketAcceptor {
        @Override
        public LeaseEnforcingSocket accept(ConnectionSetupPayload setupPayload, ReactiveSocket reactiveSocket) {
            return new DisabledLeaseAcceptingSocket(new AbstractReactiveSocket() {
                @Override
                public Publisher<Payload> requestChannel(Publisher<Payload> payloads) {
                    return Flowable.fromPublisher(payloads)
                                   .map(Payload::getData)
                                   .map(ByteBufferUtil::toUtf8String)
                                   .map(s -> "Echo: " + s)
                                   .map(PayloadImpl::new);
                }
            });
        }
    }
}
