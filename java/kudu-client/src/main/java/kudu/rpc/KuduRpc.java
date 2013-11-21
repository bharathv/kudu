/*
 * Copyright (C) 2010-2012  The Async HBase Authors.  All rights reserved.
 * Portions copyright (c) 2014 Cloudera, Inc.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *   - Redistributions of source code must retain the above copyright notice,
 *     this list of conditions and the following disclaimer.
 *   - Redistributions in binary form must reproduce the above copyright notice,
 *     this list of conditions and the following disclaimer in the documentation
 *     and/or other materials provided with the distribution.
 *   - Neither the name of the StumbleUpon nor the names of its contributors
 *     may be used to endorse or promote products derived from this software
 *     without specific prior written permission.
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package kudu.rpc;

import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.stumbleupon.async.Deferred;
import kudu.util.Slice;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;

import java.io.IOException;
import java.nio.charset.Charset;

/**
 * Abstract base class for all RPC requests going out to Kudu.
 * <p>
 * Implementations of this class are <b>not</b> expected to be synchronized.
 *
 * <h1>A note on passing {@code byte} arrays in argument</h1>
 * None of the method that receive a {@code byte[]} in argument will copy it.
 * If you change the contents of any byte array you give to an instance of
 * this class, you <em>may</em> affect the behavior of the request in an
 * <strong>unpredictable</strong> way.  If you need to change the byte array,
 * {@link Object#clone() clone} it before giving it to this class.  For those
 * familiar with the term "defensive copy", we don't do it in order to avoid
 * unnecessary memory copies when you know you won't be changing (or event
 * holding a reference to) the byte array, which is frequently the case.
 */
public abstract class KuduRpc {

  /**
   * The Deferred that will be invoked when this RPC completes or fails.
   * In case of a successful completion, this Deferred's first callback
   * will be invoked with an {@link Object} containing the de-serialized
   * RPC response in argument.
   * Once an RPC has been used, we create a new Deferred for it, in case
   * the user wants to re-use it.
   */
  private Deferred<Object> deferred;

  private Slice tablet;

  final KuduTable table;

  /**
   * How many times have we retried this RPC?.
   * Proper synchronization is required, although in practice most of the code
   * that access this attribute will have a happens-before relationship with
   * the rest of the code, due to other existing synchronization.
   */
  byte attempt;  // package-private for TabletClient and KuduClient only.

  KuduRpc(KuduTable table) {
    this.table = table;
  }

  /**
   * To be implemented by the concrete sub-type.
   *
   * Notice that this method is package-private, so only classes within this
   * package can use this as a base class.
   */
  abstract ChannelBuffer serialize(Message header);

  /**
   * Package private way of getting the name of the RPC method.
   */
  abstract String method();

  /**
   * To be implemented by the concrete sub-type.
   * This method is expected to de-serialize a response received for the
   * current RPC.
   *
   * Notice that this method is package-private, so only classes within this
   * package can use this as a base class.
   *
   * @param buf The buffer from which to de-serialize the response.
   * protobuf of the RPC response.  If 0, then there is just the protobuf.
   * The value is guaranteed to be both positive and of a "reasonable" size.
   */
  abstract Object deserialize(ChannelBuffer buf);

  /**
   * Package private way of making an RPC complete by giving it its result.
   * If this RPC has no {@link Deferred} associated to it, nothing will
   * happen.  This may happen if the RPC was already called back.
   * <p>
   * Once this call to this method completes, this object can be re-used to
   * re-send the same RPC, provided that no other thread still believes this
   * RPC to be in-flight (guaranteeing this may be hard in error cases).
   */
  final void callback(final Object result) {
    final Deferred<Object> d = deferred;
    if (d == null) {
      return;
    }
    deferred = null;
    attempt = 0;
    d.callback(result);
  }

  /** Package private way of accessing / creating the Deferred of this RPC.  */
  final Deferred<Object> getDeferred() {
    if (deferred == null) {
      deferred = new Deferred<Object>();
    }
    return deferred;
  }

  Slice getTablet() {
    return this.tablet;
  }

  void setTablet(Slice tablet) {
    this.tablet = tablet;
  }

  public KuduTable getTable() {
    return table;
  }

  public String toString() {

    final StringBuilder buf = new StringBuilder();
    buf.append("KuduRpc(method=");
    buf.append(method());
    buf.append(", tablet=");
    if (tablet == null) {
      buf.append("null");
    } else {
      buf.append(tablet.toString(Charset.defaultCharset()));
    }
    buf.append(", attempt=").append(attempt);
    buf.append(')');
    return buf.toString();
  }

  static void readProtobuf(final ChannelBuffer buf, final com.google.protobuf.GeneratedMessage
      .Builder builder) {
    final int length = Bytes.readVarInt32(buf);
    checkArrayLength(buf, length);
    final byte[] payload;
    final int offset;
    if (buf.hasArray()) {  // Zero copy.
      payload = buf.array();
      offset = buf.arrayOffset() + buf.readerIndex();
    } else {  // We have to copy the entire payload out of the buffer :(
      payload = new byte[length];
      buf.readBytes(payload);
      offset = 0;
    }
    try {
      builder.mergeFrom(payload, offset, length);
      if (!builder.isInitialized()) {
        throw new InvalidResponseException("Could not deserialize the response," +
            " incompatible RPC? Error is: " + builder.getInitializationErrorString(), null);
      }
    } catch (InvalidProtocolBufferException e) {
      final String msg = "Invalid RPC response: length=" + length
          + ", payload=" + Bytes.pretty(payload);
      throw new InvalidResponseException(msg, e);
    }
  }

  static final ChannelBuffer toChannelBuffer(Message header, Message pb) {
    int totalSize = IPCUtil.getTotalSizeWhenWrittenDelimited(header, pb);
    byte[] buf = new byte[totalSize+4];
    ChannelBuffer chanBuf = ChannelBuffers.wrappedBuffer(buf);
    chanBuf.clear();
    chanBuf.writeInt(totalSize);
    final CodedOutputStream out = CodedOutputStream.newInstance(buf, 4, totalSize);
    try {
      out.writeRawVarint32(header.getSerializedSize());
      header.writeTo(out);

      out.writeRawVarint32(pb.getSerializedSize());
      pb.writeTo(out);
      out.checkNoSpaceLeft();
    } catch (IOException e) {
      throw new NonRecoverableException("Cannot serialize the following message " + pb, e);
    }
    chanBuf.writerIndex(buf.length);
    return chanBuf;
  }

  /**
   * Upper bound on the size of a byte array we de-serialize.
   * This is to prevent Kudu from OOM'ing us, should there be a bug or
   * undetected corruption of an RPC on the network, which would turn a
   * an innocuous RPC into something allocating a ton of memory.
   * The Hadoop RPC protocol doesn't do any checksumming as they probably
   * assumed that TCP checksums would be sufficient (they're not).
   */
  static final long MAX_BYTE_ARRAY_MASK =
      0xFFFFFFFFF0000000L;  // => max = 256MB

  /**
   * Verifies that the given length looks like a reasonable array length.
   * This method accepts 0 as a valid length.
   * @param buf The buffer from which the length was read.
   * @param length The length to validate.
   * @throws IllegalArgumentException if the length is negative or
   * suspiciously large.
   */
  static void checkArrayLength(final ChannelBuffer buf, final long length) {
    // 2 checks in 1.  If any of the high bits are set, we know the value is
    // either too large, or is negative (if the most-significant bit is set).
    if ((length & MAX_BYTE_ARRAY_MASK) != 0) {
      if (length < 0) {
        throw new IllegalArgumentException("Read negative byte array length: "
            + length + " in buf=" + buf + '=' + Bytes.pretty(buf));
      } else {
        throw new IllegalArgumentException("Read byte array length that's too"
            + " large: " + length + " > " + ~MAX_BYTE_ARRAY_MASK + " in buf="
            + buf + '=' + Bytes.pretty(buf));
      }
    }
  }
}