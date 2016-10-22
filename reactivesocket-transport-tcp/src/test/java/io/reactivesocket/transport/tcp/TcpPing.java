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
package io.reactivesocket.transport.tcp;

import io.reactivesocket.client.KeepAliveProvider;
import io.reactivesocket.client.ReactiveSocketClient;
import io.reactivesocket.client.SetupProvider;
import io.reactivesocket.test.PingClient;
import io.reactivesocket.transport.tcp.client.TcpTransportClient;
import org.HdrHistogram.Recorder;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

public final class TcpPing {

    public static void main(String... args) throws Exception {
        SetupProvider setup = SetupProvider.keepAlive(KeepAliveProvider.never()).disableLease();
        ReactiveSocketClient client =
                ReactiveSocketClient.create(TcpTransportClient.create(new InetSocketAddress("localhost", 7878)), setup);
        PingClient pingClient = new PingClient(client);
        Recorder recorder = pingClient.startTracker(1, TimeUnit.SECONDS);
        final int count = 1_000_000_000;
        pingClient.connect()
                  .startPingPong(count, recorder)
                  .doOnTerminate(() -> {
                      System.out.println("Sent " + count + " messages.");
                  })
                  .last(null)
                  .blockingGet();
    }
}
