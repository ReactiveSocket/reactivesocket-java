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

package io.reactivesocket.integration;

import io.reactivesocket.AbstractReactiveSocket;
import io.reactivesocket.Payload;
import io.reactivesocket.ReactiveSocket;
import io.reactivesocket.client.KeepAliveProvider;
import io.reactivesocket.client.ReactiveSocketClient;
import io.reactivesocket.client.SetupProvider;
import io.reactivesocket.lease.DisabledLeaseAcceptingSocket;
import io.reactivesocket.server.ReactiveSocketServer;
import io.reactivesocket.transport.TransportServer.StartedServer;
import io.reactivesocket.transport.netty.client.TcpTransportClient;
import io.reactivesocket.transport.netty.server.TcpTransportServer;
import io.reactivesocket.util.PayloadImpl;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExternalResource;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.tcp.TcpClient;
import reactor.ipc.netty.tcp.TcpServer;

import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

public class IntegrationTest {

    @Rule
    public final ClientServerRule rule = new ClientServerRule();

    @Test(timeout = 2_000L)
    public void testRequest() {
        rule.client.requestResponse(new PayloadImpl("REQUEST", "META")).block();
        assertThat("Server did not see the request.", rule.requestCount.get(), is(1));
    }

    @Test//(timeout = 2_000L)
    public void testClose() throws ExecutionException, InterruptedException, TimeoutException {

        rule.client.close().block();
        rule.disconnectionCounter.await();
    }

    public static class ClientServerRule extends ExternalResource {

        private StartedServer server;
        private ReactiveSocket client;
        private AtomicInteger requestCount;
        private CountDownLatch disconnectionCounter;

        @Override
        public Statement apply(final Statement base, Description description) {
            return new Statement() {
                @Override
                public void evaluate() throws Throwable {
                    requestCount = new AtomicInteger();
                    disconnectionCounter = new CountDownLatch(1);
                    server = ReactiveSocketServer.create(TcpTransportServer.create(TcpServer.create()))
                                        .start((setup, sendingSocket) -> {
                                            sendingSocket.onClose()
                                                .doFinally(signalType -> disconnectionCounter.countDown())
                                                .subscribe();

                                            return new DisabledLeaseAcceptingSocket(new AbstractReactiveSocket() {
                                                @Override
                                                public Mono<Payload> requestResponse(Payload payload) {
                                                    return Mono.<Payload>just(new PayloadImpl("RESPONSE", "METADATA"))
                                                            .doOnSubscribe(s -> requestCount.incrementAndGet());
                                                }
                                            });
                                        });
                    client = ReactiveSocketClient.create(TcpTransportClient.create(TcpClient.create(options ->
                                    options.connect((InetSocketAddress)server.getServerAddress()))),
                                                                     SetupProvider.keepAlive(KeepAliveProvider.never())
                                                                                .disableLease())
                                                             .connect()
                                   .block();
                    base.evaluate();
                }
            };
        }
    }

}
