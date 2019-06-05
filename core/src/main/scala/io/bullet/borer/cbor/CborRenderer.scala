/*
 * Copyright (c) 2019 Mathias Doenitz
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package io.bullet.borer.cbor

import java.nio.charset.StandardCharsets.UTF_8

import io.bullet.borer._
import io.bullet.borer.internal.Util

/**
  * Encapsulates basic CBOR encoding logic.
  * Has no internal state and can therefore be a singleton object.
  */
final private[borer] class CborRenderer(var out: Output) extends Receiver.Renderer {

  def onNull(): Unit =
    out = out.writeAsByte(0xF6)

  def onUndefined(): Unit =
    out = out.writeAsByte(0xF7)

  def onBoolean(value: Boolean): Unit =
    out = out.writeAsByte(if (value) 0xF5 else 0xF4)

  def onInt(value: Int): Unit =
    onLong(value.toLong)

  def onLong(value: Long): Unit =
    out = if (value < 0) writeInteger(~value, 0x20) else writeInteger(value, 0x00)

  def onOverLong(negative: Boolean, value: Long): Unit =
    out = out.writeAsByte(if (negative) 0x3B else 0x1B).writeLong(value)

  def onFloat16(value: Float): Unit =
    out = out.writeAsByte(0xF9).writeShort(Float16.floatToShort(value).toShort)

  def onFloat(value: Float): Unit =
    out = out.writeAsByte(0xFA).writeInt(java.lang.Float.floatToIntBits(value))

  def onDouble(value: Double): Unit =
    out = out.writeAsByte(0xFB).writeLong(java.lang.Double.doubleToLongBits(value))

  def onNumberString(value: String): Unit =
    failUnsupported(out, "Number Strings")

  def onBytes[Bytes](value: Bytes)(implicit byteAccess: ByteAccess[Bytes]): Unit =
    out = writeInteger(byteAccess.sizeOf(value), 0x40).writeBytes(value)

  def onBytesStart(): Unit =
    out = out.writeAsByte(0x5F)

  def onString(value: String): Unit =
    onText(value getBytes UTF_8)

  def onString(value: Array[Byte], start: Int, end: Int, utf8: Boolean): Unit =
    failUnsupported(out, "raw string bytes")

  def onText[Bytes](value: Bytes)(implicit byteAccess: ByteAccess[Bytes]): Unit =
    out = writeInteger(byteAccess.sizeOf(value), 0x60).writeBytes(value)

  def onTextStart(): Unit =
    out = out.writeAsByte(0x7F)

  def onArrayHeader(length: Long): Unit =
    out = writeInteger(Util.requireNonNegative(length, "length"), 0x80)

  def onArrayStart(): Unit =
    out = out.writeAsByte(0x9F)

  def onMapHeader(length: Long): Unit =
    out = writeInteger(Util.requireNonNegative(length, "length"), 0xA0)

  def onMapStart(): Unit =
    out = out.writeAsByte(0xBF)

  def onBreak(): Unit =
    out = out.writeAsByte(0xFF)

  def onTag(value: Tag): Unit =
    out = writeInteger(value.code, 0xC0)

  def onSimpleValue(value: Int): Unit =
    out = if (!SimpleValue.isLegal(value)) {
      val msg = s"$value must be in the range ${SimpleValue.legalRange}, but was $value"
      throw new Borer.Error.InvalidInputData(out, msg)
    } else writeInteger(value.toLong, 0xE0)

  def onEndOfInput(): Unit = () // no actual action here

  private def writeInteger(value: Long, majorType: Int): Output = {
    var v = value
    (if (v > 23) {
       if (v >> 8 != 0) {
         (if (v >> 16 != 0) {
            (if (v >> 32 != 0) {
               out
                 .writeAsByte(0x1B + majorType)
                 .writeInt((v >> 32).toInt)
             } else out.writeAsByte(0x1A + majorType))
              .writeShort((v >> 16).toShort)
          } else out.writeAsByte(0x19 + majorType))
           .writeByte((v >> 8).toByte)
       } else out.writeAsByte(0x18 + majorType)
     } else {
       v += majorType; out
     })
      .writeByte(v.toByte)
  }

  private def failUnsupported(out: Output, what: String) =
    throw new Borer.Error.Unsupported(out, s"The CBOR renderer doesn't support $what")
}

object CborRenderer extends (Output => CborRenderer) {
  def apply(out: Output) = new CborRenderer(out)
}
