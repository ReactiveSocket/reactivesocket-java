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

package io.reactivesocket;

import io.reactivesocket.client.KeepAliveProvider;
import io.reactivesocket.exceptions.InvalidRequestException;
import io.reactivesocket.reactivestreams.extensions.Px;
import io.reactivesocket.test.util.LocalDuplexConnection;
import io.reactivesocket.util.PayloadImpl;
import io.reactivex.Flowable;
import io.reactivex.processors.PublishProcessor;
import org.hamcrest.MatcherAssert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExternalResource;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.reactivestreams.Publisher;
import io.reactivex.subscribers.TestSubscriber;

import java.util.ArrayList;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.fail;

public class ReactiveSocketTest {

    @Rule
    public final SocketRule rule = new SocketRule();

    @Test(timeout = 2_000)
    public void testRequestReplyNoError() {
        TestSubscriber<Payload> subscriber = TestSubscriber.create();
        Flowable.fromPublisher(rule.crs.requestResponse(new PayloadImpl("hello")))
                .subscribe(subscriber);
        await(subscriber).assertNoErrors().assertComplete().assertValueCount(1);
        rule.assertNoErrors();
    }

    @Test(timeout = 2000)
    public void testHandlerEmitsError() {
        rule.setRequestAcceptor(new AbstractReactiveSocket() {
            @Override
            public Publisher<Payload> requestResponse(Payload payload) {
                return Flowable.error(new NullPointerException("Deliberate exception."));
            }
        });
        TestSubscriber<Payload> subscriber = TestSubscriber.create();
        Flowable.fromPublisher(rule.crs.requestResponse(PayloadImpl.EMPTY))
            .subscribe(subscriber);
        await(subscriber).assertNotComplete().assertNoValues()
                         .assertError(InvalidRequestException.class);
        rule.assertNoErrors();
    }

    private static TestSubscriber<Payload> await(TestSubscriber<Payload> subscriber) {
        try {
            return subscriber.await();
        } catch (InterruptedException e) {
            fail("Interrupted while waiting for completion.");
            return null;
        }
    }

    public static class SocketRule extends ExternalResource {

        private ClientReactiveSocket crs;
        private ServerReactiveSocket srs;
        private ReactiveSocket requestAcceptor;
        PublishProcessor<Frame> serverProcessor;
        PublishProcessor<Frame> clientProcessor;
        private ArrayList<Throwable> clientErrors = new ArrayList<>();
        private ArrayList<Throwable> serverErrors = new ArrayList<>();

        @Override
        public Statement apply(Statement base, Description description) {
            return new Statement() {
                @Override
                public void evaluate() throws Throwable {
                    init();
                    base.evaluate();
                }
            };
        }

        protected void init() {
            serverProcessor = PublishProcessor.create();
            clientProcessor = PublishProcessor.create();

            LocalDuplexConnection serverConnection = new LocalDuplexConnection("server", clientProcessor, serverProcessor);
            LocalDuplexConnection clientConnection = new LocalDuplexConnection("client", serverProcessor, clientProcessor);

            requestAcceptor = null != requestAcceptor? requestAcceptor : new AbstractReactiveSocket() {
                @Override
                public Publisher<Payload> requestResponse(Payload payload) {
                    return Px.just(payload);
                }
            };

            srs = new ServerReactiveSocket(serverConnection, requestAcceptor,
                throwable -> serverErrors.add(throwable));
            srs.start();

            crs = new ClientReactiveSocket(clientConnection,
                                           throwable -> clientErrors.add(throwable), StreamIdSupplier.clientSupplier(),
                                           KeepAliveProvider.never());
            crs.start(lease -> {});
        }

        public void setRequestAcceptor(ReactiveSocket requestAcceptor) {
            this.requestAcceptor = requestAcceptor;
            init();
        }

        public void assertNoErrors() {
            MatcherAssert.assertThat("Unexpected error on the client connection.", clientErrors, is(empty()));
            MatcherAssert.assertThat("Unexpected error on the server connection.", serverErrors, is(empty()));
        }
    }

}