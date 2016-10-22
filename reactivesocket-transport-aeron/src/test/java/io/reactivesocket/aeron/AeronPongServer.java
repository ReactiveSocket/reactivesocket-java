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
package io.reactivesocket.aeron;

import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.reactivesocket.aeron.internal.AeronWrapper;
import io.reactivesocket.aeron.internal.DefaultAeronWrapper;
import io.reactivesocket.aeron.internal.EventLoop;
import io.reactivesocket.aeron.internal.SingleThreadedEventLoop;
import io.reactivesocket.aeron.internal.reactivestreams.AeronSocketAddress;
import io.reactivesocket.aeron.server.AeronTransportServer;
import io.reactivesocket.server.ReactiveSocketServer;
import io.reactivesocket.test.PingHandler;

public final class AeronPongServer {
    static {
        final io.aeron.driver.MediaDriver.Context ctx = new io.aeron.driver.MediaDriver.Context()
            .threadingMode(ThreadingMode.SHARED_NETWORK)
            .dirsDeleteOnStart(true);
        MediaDriver.launch(ctx);
    }

    public static void main(String... args) throws Exception {
        MediaDriverHolder.getInstance();
        AeronWrapper aeronWrapper = new DefaultAeronWrapper();

        AeronSocketAddress serverManagementSocketAddress = AeronSocketAddress.create("aeron:udp", "127.0.0.1", 39790);
        EventLoop serverEventLoop = new SingleThreadedEventLoop("server");
        AeronTransportServer server = new AeronTransportServer(aeronWrapper, serverManagementSocketAddress, serverEventLoop);

        ReactiveSocketServer.create(server)
                               .start(new PingHandler())
                               .awaitShutdown();
    }
}
