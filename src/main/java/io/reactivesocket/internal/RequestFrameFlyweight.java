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
package io.reactivesocket.internal;

import io.reactivesocket.FrameType;
import uk.co.real_logic.agrona.BitUtil;
import uk.co.real_logic.agrona.DirectBuffer;
import uk.co.real_logic.agrona.MutableDirectBuffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class RequestFrameFlyweight
{
    public static final int FLAGS_REQUEST_CHANNEL_C = 0b0001_0000_0000_0000;
    public static final int FLAGS_REQUEST_CHANNEL_N = 0b0000_1000_0000_0000;

    // relative to start of passed offset
    private static final int INITIAL_REQUEST_N_FIELD_OFFSET = FrameHeaderFlyweight.FRAME_HEADER_LENGTH;

    public static int computeFrameLength(final FrameType type, final int metadataLength, final int dataLength)
    {
        int length = FrameHeaderFlyweight.computeFrameHeaderLength(type, metadataLength, dataLength);

        if (type.hasInitialRequestN())
        {
            length += BitUtil.SIZE_OF_INT;
        }

        return length;
    }

    public static int encode(
        final MutableDirectBuffer mutableDirectBuffer,
        final int offset,
        final int streamId,
        final FrameType type,
        final int initialRequestN,
        final ByteBuffer metadata,
        final ByteBuffer data)
    {
        final int frameLength = computeFrameLength(type, metadata.capacity(), data.capacity());

        final int flags = FLAGS_REQUEST_CHANNEL_N;
        int length = FrameHeaderFlyweight.encodeFrameHeader(mutableDirectBuffer, offset, frameLength, flags, type, streamId);

        mutableDirectBuffer.putInt(offset + INITIAL_REQUEST_N_FIELD_OFFSET, initialRequestN, ByteOrder.BIG_ENDIAN);
        length += BitUtil.SIZE_OF_INT;

        length += FrameHeaderFlyweight.encodeMetadata(mutableDirectBuffer, offset, offset + length, metadata);
        length += FrameHeaderFlyweight.encodeData(mutableDirectBuffer, offset + length, data);

        return length;
    }

    public static int encode(
        final MutableDirectBuffer mutableDirectBuffer,
        final int offset,
        final int streamId,
        final FrameType type,
        final ByteBuffer metadata,
        final ByteBuffer data)
    {
        final int frameLength = computeFrameLength(type, metadata.capacity(), data.capacity());

        int length = FrameHeaderFlyweight.encodeFrameHeader(mutableDirectBuffer, offset, frameLength, 0, type, streamId);

        length += FrameHeaderFlyweight.encodeMetadata(mutableDirectBuffer, offset, offset + length, metadata);
        length += FrameHeaderFlyweight.encodeData(mutableDirectBuffer, offset + length, data);

        return length;
    }

    public static int encode(
        final MutableDirectBuffer mutableDirectBuffer,
        final int offset,
        final int streamId,
        final FrameType type,
        final int flags)
    {
        final int frameLength = computeFrameLength(type, 0, 0);

        return FrameHeaderFlyweight.encodeFrameHeader(mutableDirectBuffer, offset, frameLength, flags, type, streamId);
    }

    public static int initialRequestN(final DirectBuffer directBuffer, final int offset)
    {
        return directBuffer.getInt(offset + INITIAL_REQUEST_N_FIELD_OFFSET, ByteOrder.BIG_ENDIAN);
    }

    public static int payloadOffset(final FrameType type, final DirectBuffer directBuffer, final int offset)
    {
        int result = offset + FrameHeaderFlyweight.FRAME_HEADER_LENGTH;

        if (type.hasInitialRequestN())
        {
            result += BitUtil.SIZE_OF_INT;
        }

        return result;
    }
}
