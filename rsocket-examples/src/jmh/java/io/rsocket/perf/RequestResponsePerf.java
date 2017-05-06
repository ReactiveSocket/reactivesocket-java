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

package io.rsocket.perf;

import io.rsocket.RSocket;
import io.rsocket.perf.util.ClientServerHolder;
import io.rsocket.util.PayloadImpl;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.function.Supplier;

@BenchmarkMode(Mode.Throughput)
@Fork(value = 1, jvmArgsAppend = { "-XX:+UnlockCommercialFeatures", "-XX:+FlightRecorder" })
@Warmup(iterations = 10)
@Measurement(iterations = 10)
@State(Scope.Benchmark)
public class RequestResponsePerf extends AbstractRSocketPerf {

    @Param({ "1", "100", "1000"})
    public int requestCount;

    @Setup(Level.Trial)
    public void setup(Blackhole bh) {
        _setup(bh);
    }

    @Benchmark
    public void requestResponse() throws InterruptedException {
        Supplier<RSocket> socketSupplier = getSocketSupplier();
        requestResponse(socketSupplier, () -> new PayloadImpl(ClientServerHolder.HELLO), requestCount);
    }
}
