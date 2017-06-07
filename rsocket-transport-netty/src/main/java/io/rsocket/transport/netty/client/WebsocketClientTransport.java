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

package io.rsocket.transport.netty.client;

import io.rsocket.DuplexConnection;
import io.rsocket.transport.ClientTransport;
import io.rsocket.transport.netty.WebsocketDuplexConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.http.client.HttpClient;

public class WebsocketClientTransport implements ClientTransport {
  private final Logger logger = LoggerFactory.getLogger(WebsocketClientTransport.class);
  private final HttpClient client;

  private WebsocketClientTransport(HttpClient client) {
    this.client = client;
  }

  public static WebsocketClientTransport create(int port) {
    HttpClient httpClient = HttpClient.create(port);
    return create(httpClient);
  }

  public static WebsocketClientTransport create(String bindAddress, int port) {
    HttpClient httpClient = HttpClient.create(bindAddress, port);
    return create(httpClient);
  }

  public static WebsocketClientTransport create(HttpClient client) {
    return new WebsocketClientTransport(client);
  }

  @Override
  public Mono<DuplexConnection> connect() {
    return Mono.create(
        sink ->
            client
                .ws("/")
                .then(
                    response ->
                        response.receiveWebsocket(
                            (in, out) -> {
                              WebsocketDuplexConnection connection =
                                  new WebsocketDuplexConnection(in, out, in.context());
                              sink.success(connection);
                              return connection.onClose();
                            }))
                .doOnError(sink::error)
                .subscribe());
  }
}
