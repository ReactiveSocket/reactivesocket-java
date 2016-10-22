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

package io.reactivesocket.lease;

import io.reactivesocket.Frame;

import java.nio.ByteBuffer;

public final class LeaseImpl implements Lease {

    private final int allowedRequests;
    private final int ttl;
    private final long expiry;
    private final ByteBuffer metadata;

    public LeaseImpl(int allowedRequests, int ttl, ByteBuffer metadata) {
        this.allowedRequests = allowedRequests;
        this.ttl = ttl;
        expiry = System.currentTimeMillis() + ttl;
        this.metadata = metadata;
    }

    public LeaseImpl(Frame leaseFrame) {
        this(Frame.Lease.numberOfRequests(leaseFrame), Frame.Lease.ttl(leaseFrame), leaseFrame.getMetadata());
    }

    @Override
    public int getAllowedRequests() {
        return allowedRequests;
    }

    @Override
    public int getTtl() {
        return ttl;
    }

    @Override
    public long expiry() {
        return expiry;
    }

    @Override
    public ByteBuffer metadata() {
        return metadata;
    }
}
