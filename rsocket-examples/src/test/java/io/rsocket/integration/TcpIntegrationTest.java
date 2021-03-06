/*
 * Copyright 2015-2021 the original author or authors.
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

package io.rsocket.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.rsocket.Payload;
import io.rsocket.RSocket;
import io.rsocket.core.RSocketConnector;
import io.rsocket.core.RSocketServer;
import io.rsocket.transport.netty.client.TcpClientTransport;
import io.rsocket.transport.netty.server.CloseableChannel;
import io.rsocket.transport.netty.server.TcpServerTransport;
import io.rsocket.util.DefaultPayload;
import io.rsocket.util.EmptyPayload;
import io.rsocket.util.RSocketProxy;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

public class TcpIntegrationTest {
  private RSocket handler;

  private CloseableChannel server;

  @BeforeEach
  public void startup() {
    server =
        RSocketServer.create((setup, sendingSocket) -> Mono.just(new RSocketProxy(handler)))
            .bind(TcpServerTransport.create("localhost", 0))
            .block();
  }

  private RSocket buildClient() {
    return RSocketConnector.connectWith(TcpClientTransport.create(server.address())).block();
  }

  @AfterEach
  public void cleanup() {
    server.dispose();
  }

  @Test
  @Timeout(15_000L)
  public void testCompleteWithoutNext() {
    handler =
        new RSocket() {
          @Override
          public Flux<Payload> requestStream(Payload payload) {
            return Flux.empty();
          }
        };
    RSocket client = buildClient();
    Boolean hasElements =
        client.requestStream(DefaultPayload.create("REQUEST", "META")).log().hasElements().block();

    assertThat(hasElements).isFalse();
  }

  @Test
  @Timeout(15_000L)
  public void testSingleStream() {
    handler =
        new RSocket() {
          @Override
          public Flux<Payload> requestStream(Payload payload) {
            return Flux.just(DefaultPayload.create("RESPONSE", "METADATA"));
          }
        };

    RSocket client = buildClient();

    Payload result = client.requestStream(DefaultPayload.create("REQUEST", "META")).blockLast();

    assertThat(result.getDataUtf8()).isEqualTo("RESPONSE");
  }

  @Test
  @Timeout(15_000L)
  public void testZeroPayload() {
    handler =
        new RSocket() {
          @Override
          public Flux<Payload> requestStream(Payload payload) {
            return Flux.just(EmptyPayload.INSTANCE);
          }
        };

    RSocket client = buildClient();

    Payload result = client.requestStream(DefaultPayload.create("REQUEST", "META")).blockFirst();

    assertThat(result.getDataUtf8()).isEmpty();
  }

  @Test
  @Timeout(15_000L)
  public void testRequestResponseErrors() {
    handler =
        new RSocket() {
          boolean first = true;

          @Override
          public Mono<Payload> requestResponse(Payload payload) {
            if (first) {
              first = false;
              return Mono.error(new RuntimeException("EX"));
            } else {
              return Mono.just(DefaultPayload.create("SUCCESS"));
            }
          }
        };

    RSocket client = buildClient();

    Payload response1 =
        client
            .requestResponse(DefaultPayload.create("REQUEST", "META"))
            .onErrorReturn(DefaultPayload.create("ERROR"))
            .block();
    Payload response2 =
        client
            .requestResponse(DefaultPayload.create("REQUEST", "META"))
            .onErrorReturn(DefaultPayload.create("ERROR"))
            .block();

    assertThat(response1.getDataUtf8()).isEqualTo("ERROR");
    assertThat(response2.getDataUtf8()).isEqualTo("SUCCESS");
  }

  @Test
  @Timeout(15_000L)
  public void testTwoConcurrentStreams() throws InterruptedException {
    ConcurrentHashMap<String, Sinks.Many<Payload>> map = new ConcurrentHashMap<>();
    Sinks.Many<Payload> processor1 = Sinks.many().unicast().onBackpressureBuffer();
    map.put("REQUEST1", processor1);
    Sinks.Many<Payload> processor2 = Sinks.many().unicast().onBackpressureBuffer();
    map.put("REQUEST2", processor2);

    handler =
        new RSocket() {
          @Override
          public Flux<Payload> requestStream(Payload payload) {
            return map.get(payload.getDataUtf8()).asFlux();
          }
        };

    RSocket client = buildClient();

    Flux<Payload> response1 = client.requestStream(DefaultPayload.create("REQUEST1"));
    Flux<Payload> response2 = client.requestStream(DefaultPayload.create("REQUEST2"));

    CountDownLatch nextCountdown = new CountDownLatch(2);
    CountDownLatch completeCountdown = new CountDownLatch(2);

    response1
        .subscribeOn(Schedulers.newSingle("1"))
        .subscribe(c -> nextCountdown.countDown(), t -> {}, completeCountdown::countDown);

    response2
        .subscribeOn(Schedulers.newSingle("2"))
        .subscribe(c -> nextCountdown.countDown(), t -> {}, completeCountdown::countDown);

    processor1.tryEmitNext(DefaultPayload.create("RESPONSE1A"));
    processor2.tryEmitNext(DefaultPayload.create("RESPONSE2A"));

    nextCountdown.await();

    processor1.tryEmitComplete();
    processor2.tryEmitComplete();

    completeCountdown.await();
  }
}
