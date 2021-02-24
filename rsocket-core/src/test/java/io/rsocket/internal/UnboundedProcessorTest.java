/*
 * Copyright 2015-2018 the original author or authors.
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

package io.rsocket.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import io.rsocket.buffer.LeaksTrackingByteBufAllocator;
import io.rsocket.internal.subscriber.AssertSubscriber;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.Fuseable;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import reactor.test.util.RaceTestUtils;

public class UnboundedProcessorTest {
  @Test
  public void testOnNextBeforeSubscribe_10() {
    testOnNextBeforeSubscribeN(10);
  }

  @Test
  public void testOnNextBeforeSubscribe_100() {
    testOnNextBeforeSubscribeN(100);
  }

  @Test
  public void testOnNextBeforeSubscribe_10_000() {
    testOnNextBeforeSubscribeN(10_000);
  }

  @Test
  public void testOnNextBeforeSubscribe_100_000() {
    testOnNextBeforeSubscribeN(100_000);
  }

  @Test
  public void testOnNextBeforeSubscribe_1_000_000() {
    testOnNextBeforeSubscribeN(1_000_000);
  }

  @Test
  public void testOnNextBeforeSubscribe_10_000_000() {
    testOnNextBeforeSubscribeN(10_000_000);
  }

  public void testOnNextBeforeSubscribeN(int n) {
    UnboundedProcessor processor = new UnboundedProcessor();

    for (int i = 0; i < n; i++) {
      processor.onNext(Unpooled.EMPTY_BUFFER);
    }

    processor.onComplete();

    StepVerifier.create(processor.count()).expectNext(Long.valueOf(n)).expectComplete().verify();
  }

  @Test
  public void testOnNextAfterSubscribe_10() throws Exception {
    testOnNextAfterSubscribeN(10);
  }

  @Test
  public void testOnNextAfterSubscribe_100() throws Exception {
    testOnNextAfterSubscribeN(100);
  }

  @Test
  public void testOnNextAfterSubscribe_1000() throws Exception {
    testOnNextAfterSubscribeN(1000);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  public void testPrioritizedSending(boolean fusedCase) {
    UnboundedProcessor processor = new UnboundedProcessor();

    for (int i = 0; i < 1000; i++) {
      processor.onNext(Unpooled.EMPTY_BUFFER);
    }

    processor.onNextPrioritized(Unpooled.copiedBuffer("test", CharsetUtil.UTF_8));

    ByteBuf byteBuf = fusedCase ? processor.poll() : processor.next().block();

    assertThat(byteBuf)
        .isNotNull()
        .extracting(bb -> bb.toString(CharsetUtil.UTF_8))
        .isEqualTo("test");
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  public void ensureUnboundedProcessorDisposesQueueProperly(boolean withFusionEnabled) {
    final LeaksTrackingByteBufAllocator allocator =
        LeaksTrackingByteBufAllocator.instrument(ByteBufAllocator.DEFAULT);
    for (int i = 0; i < 10000; i++) {
      final UnboundedProcessor unboundedProcessor = new UnboundedProcessor();

      final ByteBuf buffer1 = allocator.buffer(1);
      final ByteBuf buffer2 = allocator.buffer(2);

      final AssertSubscriber<ByteBuf> assertSubscriber =
          new AssertSubscriber<ByteBuf>(0)
              .requestedFusionMode(withFusionEnabled ? Fuseable.ANY : Fuseable.NONE);

      unboundedProcessor.subscribe(assertSubscriber);

      RaceTestUtils.race(
          () ->
              RaceTestUtils.race(
                  () ->
                      RaceTestUtils.race(
                          () -> {
                            unboundedProcessor.onNext(buffer1);
                            unboundedProcessor.onNext(buffer2);
                          },
                          unboundedProcessor::dispose,
                          Schedulers.elastic()),
                  assertSubscriber::cancel,
                  Schedulers.elastic()),
          () -> {
            assertSubscriber.request(1);
            assertSubscriber.request(1);
          },
          Schedulers.elastic());

      assertSubscriber.values().forEach(ReferenceCountUtil::safeRelease);

      allocator.assertHasNoLeaks();
    }
  }

  public void testOnNextAfterSubscribeN(int n) throws Exception {
    CountDownLatch latch = new CountDownLatch(n);
    UnboundedProcessor processor = new UnboundedProcessor();
    processor.log().doOnNext(integer -> latch.countDown()).subscribe();

    for (int i = 0; i < n; i++) {
      processor.onNext(Unpooled.EMPTY_BUFFER);
    }

    processor.drain();

    latch.await();
  }
}
