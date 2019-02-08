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

package io.rsocket.resume;

import io.netty.buffer.ByteBuf;
import io.rsocket.frame.FrameHeaderFlyweight;
import io.rsocket.frame.FrameType;

public class ResumeUtil {
  public static boolean isTracked(FrameType frameType) {
    switch (frameType) {
      case REQUEST_CHANNEL:
      case REQUEST_STREAM:
      case REQUEST_RESPONSE:
      case REQUEST_FNF:
        // case METADATA_PUSH:
      case REQUEST_N:
      case CANCEL:
      case ERROR:
      case PAYLOAD:
        return true;
      default:
        return false;
    }
  }

  public static boolean isTracked(ByteBuf frame) {
    return isTracked(FrameHeaderFlyweight.frameType(frame));
  }

  public static int offset(ByteBuf frame) {
    return 0;
  }
}
