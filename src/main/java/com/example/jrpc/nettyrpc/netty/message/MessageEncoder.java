/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.jrpc.nettyrpc.netty.message;

import com.example.jrpc.nettyrpc.netty.buffer.ManagedBuffer;
import com.example.jrpc.nettyrpc.netty.message.rsp.RpcResponse;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Encoder used by the server side to encode server-to-client responses.
 * This encoder is stateless so it is safe to be shared by multiple threads.
 */
@ChannelHandler.Sharable
public final class MessageEncoder extends MessageToMessageEncoder<IMessage> {

  private static final Logger logger = LoggerFactory.getLogger(MessageEncoder.class);

  public static final MessageEncoder INSTANCE = new MessageEncoder();

  private MessageEncoder() {}

  /***
   * Encodes a Message by invoking its encode() method. For non-data messages, we will add one
   * ByteBuf to 'out' containing the total frame length, the message type, and the message itself.
   * In the case of a ChunkFetchSuccess, we will also add the ManagedBuffer corresponding to the
   * data to 'out', in order to enable zero-copy transfer.
   */
  @Override
  public void encode(ChannelHandlerContext ctx, IMessage in, List<Object> out) throws Exception {
    Object body = null;
    ManagedBuffer bodyBuffer = null;
    long bodyLength = 0;
    boolean isBodyInFrame = false;

    // If the message has a body, take it out to enable zero-copy transfer for the payload.
    if (in.body() != null) {
      try {
        bodyLength = in.body().size();
        bodyBuffer = in.body();
        body = bodyBuffer.convertToNetty();
        isBodyInFrame = in.isBodyInFrame();
      } catch (Exception e) {
        in.body().release();
        if (in instanceof RpcResponse) {
          RpcResponse resp = (RpcResponse) in;
          // Re-encode this message as a failure response.
          String error = e.getMessage() != null ? e.getMessage() : "null";
          logger.error(String.format("Error processing %s for client %s",
            in, ctx.channel().remoteAddress()), e);
          encode(ctx, resp.createFailureResponse(error), out);
        } else {
          throw e;
        }
        return;
      }
    }

    IMessage.MessageType msgType = in.type();

    // All messages have the frame length, message type, and message itself. The frame length
    // may optionally include the length of the body data, depending on what message is being
    // sent.
    int headerLength = in.encodedLengthOnFrameHeader() + in.encodedLength();

    //total length = [header + body] length
    long frameLength = headerLength + (isBodyInFrame ? bodyLength : 0);

    //从 Heap 分配内存
    ByteBuf header = ctx.alloc().heapBuffer(headerLength);
    //[length]
    header.writeLong(frameLength);
    //[type]
    msgType.encode(header);
    //[data]
    in.encode(header);

    assert header.writableBytes() == 0;

    if (body != null) {
      //为了简化，简单使用 ByteBuffer 而不是抽象类型 Object(ByteBuf & FileRegion)
      // ,会增加 byte 拷贝时间，不是完全的 zero-copy
      header.writeBytes(bodyBuffer.nioByteBuffer());
      out.add(header);
      // We transfer ownership of the reference on in.body() to MessageWithHeader.
      // This reference will be freed when MessageWithHeader.deallocate() is called.
      //out.add(new MessageWithHeader(in.body(), header, body, bodyLength));
    } else {
      out.add(header);
    }
  }

}
