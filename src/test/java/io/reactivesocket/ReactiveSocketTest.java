/**
 * Copyright 2015 Netflix, Inc.
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

import static io.reactivesocket.TestUtil.*;
import static org.junit.Assert.*;
import static io.reactivex.Observable.*;
import static io.reactivesocket.ConnectionSetupPayload.NO_FLAGS;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.reactivestreams.Publisher;

import io.reactivex.disposables.Disposable;
import io.reactivex.observables.ConnectableObservable;
import io.reactivex.subscribers.TestSubscriber;

public class ReactiveSocketTest {

	private static TestConnection serverConnection;
	private static TestConnection clientConnection;
	private static ReactiveSocket socketServer;
	private static ReactiveSocket socketClient;
	private static AtomicBoolean helloSubscriptionRunning = new AtomicBoolean(false);
	private static AtomicReference<String> lastFireAndForget = new AtomicReference<String>();
	private static AtomicReference<Throwable> lastServerError = new AtomicReference<Throwable>();

	@BeforeClass
	public static void setup() {
		serverConnection = new TestConnection();
		clientConnection = new TestConnection();
		clientConnection.connectToServerConnection(serverConnection);

		socketServer = ReactiveSocket.fromServerConnection(serverConnection, setup -> new RequestHandler() {

			@Override
			public Publisher<Payload> handleRequestResponse(Payload payload) {
				String request = byteToString(payload.getData());
				if ("hello".equals(request)) {
					return just(utf8EncodedPayload("hello world", null));
				} else {
					return error(new RuntimeException("Not Found"));
				}
			}

			@Override
			public Publisher<Payload> handleRequestStream(Payload payload) {
				String request = byteToString(payload.getData());
				if ("hello".equals(request)) {
					return range(0, 100).map(i -> "hello world " + i).map(n -> utf8EncodedPayload(n, null));
				} else {
					return error(new RuntimeException("Not Found"));
				}
			}

			@Override
			public Publisher<Payload> handleSubscription(Payload payload) {
				String request = byteToString(payload.getData());
				if ("hello".equals(request)) {
					return interval(1, TimeUnit.MICROSECONDS)
							.doOnSubscribe(s -> helloSubscriptionRunning.set(true))
							.doOnCancel(() -> helloSubscriptionRunning.set(false))
							.map(i -> "subscription " + i)
							.map(n -> utf8EncodedPayload(n, null));
				} else {
					return error(new RuntimeException("Not Found"));
				}
			}

			@Override
			public Publisher<Void> handleFireAndForget(Payload payload) {
				String request = byteToString(payload.getData());
				lastFireAndForget.set(request);
				if ("log".equals(request)) {
					return empty(); // success
				} else if ("blowup".equals(request)) {
					throw new RuntimeException("forced blowup to simulate handler error");
				} else {
					lastFireAndForget.set("notFound");
					return error(new RuntimeException("Not Found"));
				}
			}

			/**
			 * Use Payload.metadata for routing
			 */
			@Override
			public Publisher<Payload> handleChannel(Payload initialPayload, Publisher<Payload> payloads) {
				String request = byteToString(initialPayload.getMetadata());
				if ("echo".equals(request)) {
					return echoChannel(payloads);
				} else {
					return error(new RuntimeException("Not Found"));
				}
			}

			private Publisher<Payload> echoChannel(Publisher<Payload> echo) {
				return fromPublisher(echo).map(p -> {
					return utf8EncodedPayload(byteToString(p.getData()) + "_echo", null);
				});
			}

		}, t -> lastServerError.set(t));

		socketClient = ReactiveSocket.fromClientConnection(clientConnection, ConnectionSetupPayload.create("UTF-8", "UTF-8", NO_FLAGS), t -> {});

		// start both the server and client and monitor for errors
		socketServer.start();
		socketClient.start();
	}
	
	@AfterClass
	public static void shutdown() {
		socketServer.shutdown();
		socketClient.shutdown();
	}

	@Test
	public void testRequestResponse() throws InterruptedException {
		// perform request/response
		Publisher<Payload> response = socketClient.requestResponse(TestUtil.utf8EncodedPayload("hello", null));
		TestSubscriber<Payload> ts = new TestSubscriber<>();
		fromPublisher(response).subscribe(ts);
		ts.await(500, TimeUnit.MILLISECONDS);
		ts.assertNoError();
		ts.assertValue(TestUtil.utf8EncodedPayload("hello world", null));
	}

	@Test
	public void testRequestStream() throws InterruptedException {
		// perform request/stream
		Publisher<Payload> response = socketClient.requestStream(TestUtil.utf8EncodedPayload("hello", null));
		TestSubscriber<Payload> ts = new TestSubscriber<>();;
		fromPublisher(response).subscribe(ts);
		ts.await(500, TimeUnit.MILLISECONDS);
		ts.assertNoError();
		assertEquals(100, ts.values().size());
		assertEquals("hello world 99", byteToString(ts.values().get(99).getData()));
	}

	@Test
	public void testRequestSubscription() throws InterruptedException {
		// perform request/subscription
		Publisher<Payload> response = socketClient.requestSubscription(TestUtil.utf8EncodedPayload("hello", null));
		TestSubscriber<Payload> ts = new TestSubscriber<>();;
		TestSubscriber<Payload> ts2 = new TestSubscriber<>();;
		ConnectableObservable<Payload> published = fromPublisher(response).publish();
		published.take(10).subscribe(ts);
		published.subscribe(ts2);
		Disposable disposable = published.connect();

		// ts completed due to take
		ts.await(500, TimeUnit.MILLISECONDS);
		ts.assertNoError();
		ts.assertComplete();

		// ts2 should never complete
		ts2.assertNoError();
		ts2.assertNotTerminated();

		// assert it is running still
		assertTrue(helloSubscriptionRunning.get());

		// shut down the work
		disposable.dispose();

		// wait for up to 2 seconds for the async CANCEL to occur (it sends a message up)
		for (int i = 0; i < 20; i++) {
			if (!helloSubscriptionRunning.get()) {
				break;
			}
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
			}
		}
		// and then stopped after unsubscribing
		assertFalse(helloSubscriptionRunning.get());

		assertEquals(10, ts.values().size());
		assertEquals("subscription 9", byteToString(ts.values().get(9).getData()));
	}

	@Test
	public void testFireAndForgetSuccess() throws InterruptedException {
		// perform request/response
		Publisher<Void> response = socketClient.fireAndForget(TestUtil.utf8EncodedPayload("log", null));
		TestSubscriber<Void> ts = new TestSubscriber<>();;
		fromPublisher(response).subscribe(ts);
		ts.await(500, TimeUnit.MILLISECONDS);
		ts.assertNoError();
		ts.assertComplete();
		assertEquals("log", lastFireAndForget.get());
	}

	@Test
	public void testFireAndForgetServerSideErrorNotFound() throws InterruptedException {
		// perform request/response
		Publisher<Void> response = socketClient.fireAndForget(TestUtil.utf8EncodedPayload("unknown", null));
		TestSubscriber<Void> ts = new TestSubscriber<>();;
		fromPublisher(response).subscribe(ts);
		ts.await(500, TimeUnit.MILLISECONDS);
		ts.assertNoError(); // client-side won't see an error
		ts.assertComplete();
		assertEquals("notFound", lastFireAndForget.get());
	}

	@Test
	public void testFireAndForgetServerSideErrorHandlerBlowup() throws InterruptedException {
		// perform request/response
		Publisher<Void> response = socketClient.fireAndForget(TestUtil.utf8EncodedPayload("blowup", null));
		TestSubscriber<Void> ts = new TestSubscriber<>();;
		fromPublisher(response).subscribe(ts);
		ts.await(500, TimeUnit.MILLISECONDS);
		ts.assertNoError(); // client-side won't see an error
		ts.assertComplete();
		assertEquals("blowup", lastFireAndForget.get());
		assertEquals("forced blowup to simulate handler error", lastServerError.get().getCause().getMessage());
	}

	@Test
	public void testRequestChannelEcho() throws InterruptedException {
		Publisher<Payload> requestStream = just(TestUtil.utf8EncodedPayload("1", "echo")).concatWith(just(TestUtil.utf8EncodedPayload("2", null)));
		Publisher<Payload> response = socketClient.requestChannel(requestStream);
		TestSubscriber<Payload> ts = new TestSubscriber<>();;
		fromPublisher(response).subscribe(ts);
		ts.await(500, TimeUnit.MILLISECONDS);
		ts.assertNoError();
		assertEquals(2, ts.values().size());
		assertEquals("1_echo", byteToString(ts.values().get(0).getData()));
		assertEquals("2_echo", byteToString(ts.values().get(1).getData()));
	}

	@Test
	public void testRequestChannelNotFound() throws InterruptedException {
		Publisher<Payload> requestStream = just(TestUtil.utf8EncodedPayload(null, "someChannel"));
		Publisher<Payload> response = socketClient.requestChannel(requestStream);
		TestSubscriber<Payload> ts = new TestSubscriber<>();;
		fromPublisher(response).subscribe(ts);
		ts.await(500, TimeUnit.MILLISECONDS);
		ts.assertTerminated();
		ts.assertNotComplete();
		ts.assertNoValues();
		assertEquals("Not Found", ts.errors().get(0).getMessage());
	}
}
