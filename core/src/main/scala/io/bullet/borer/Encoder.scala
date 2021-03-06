/*
 * Copyright (c) 2019 Mathias Doenitz
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package io.bullet.borer

import java.lang.{
  Boolean => JBoolean,
  Byte => JByte,
  Double => JDouble,
  Float => JFloat,
  Long => JLong,
  Short => JShort
}
import java.math.{BigDecimal => JBigDecimal, BigInteger => JBigInteger}

import io.bullet.borer.encodings.BaseEncoding
import io.bullet.borer.internal.{Util, XIterableOnce, XIterableOnceBound}

import scala.annotation.tailrec
import scala.collection.LinearSeq

/**
  * Type class responsible for writing an instance of type [[T]] to a [[Writer]].
  */
trait Encoder[T] {
  def write(w: Writer, value: T): Writer
}

object Encoder extends LowPrioEncoders {

  /**
    * An [[Encoder]] that might change its encoding strategy if [[T]] has a default value.
    */
  trait DefaultValueAware[T] extends Encoder[T] {
    def withDefaultValue(defaultValue: T): Encoder[T]
  }

  /**
    * An [[Encoder]] that might not actually produce any output for certain values of [[T]]
    * (e.g. because "not-present" already carries sufficient information).
    */
  trait PossiblyWithoutOutput[T] extends Encoder[T] {
    def producesOutputFor(value: T): Boolean
  }

  /**
    * Creates an [[Encoder]] from the given function.
    */
  def apply[T](implicit encoder: Encoder[T]): Encoder[T] = encoder

  /**
    * Allows for somewhat concise [[Encoder]] definition for case classes, without any macro magic.
    * Can be used e.g. like this:
    *
    * {{{
    * case class Foo(int: Int, string: String, doubleOpt: Option[Double])
    *
    * val fooEncoder = Encoder.from(Foo.unapply _)
    * }}}
    *
    * Encodes an instance as a simple array of values.
    */
  def from[T, Unapplied](unapply: T => Option[Unapplied])(implicit tupleEnc: Encoder[Unapplied]): Encoder[T] =
    Encoder((w, x) => tupleEnc.write(w, unapply(x).get))

  /**
    * Same as the other `from` overload above, but for nullary case classes (i.e. with an empty parameter list).
    */
  def from[T](unapply: T => Boolean): Encoder[T] =
    Encoder((w, x) => if (unapply(x)) w.writeEmptyArray() else sys.error("Unapply unexpectedly failed: " + unapply))

  /**
    * Creates a "unified" [[Encoder]] from two encoders that each target only a single data format.
    */
  def targetSpecific[T](cbor: Encoder[T], json: Encoder[T]): Encoder[T] = { (w, x) =>
    if (w.target == Cbor) cbor.write(w, x)
    else json.write(w, x)
  }

  implicit final class EncoderOps[A](val underlying: Encoder[A]) extends AnyVal {
    def contramap[B](f: B => A): Encoder[B]                     = Encoder((w, b) => underlying.write(w, f(b)))
    def contramapWithWriter[B](f: (Writer, B) => A): Encoder[B] = Encoder((w, b) => underlying.write(w, f(w, b)))

    def withDefaultValue(defaultValue: A): Encoder[A] =
      underlying match {
        case x: Encoder.DefaultValueAware[A] => x withDefaultValue defaultValue
        case x                               => x
      }
  }

  implicit def fromCodec[T](implicit codec: Codec[T]): Encoder[T] = codec.encoder

  ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  implicit val forNull: Encoder[Null]       = Encoder((w, _) => w.writeNull())
  implicit val forBoolean: Encoder[Boolean] = Encoder(_ writeBoolean _)
  implicit val forChar: Encoder[Char]       = Encoder(_ writeChar _)
  implicit val forByte: Encoder[Byte]       = Encoder(_ writeByte _)
  implicit val forShort: Encoder[Short]     = Encoder(_ writeShort _)
  implicit val forInt: Encoder[Int]         = Encoder(_ writeInt _)
  implicit val forLong: Encoder[Long]       = Encoder(_ writeLong _)
  implicit val forFloat: Encoder[Float]     = Encoder(_ writeFloat _)
  implicit val forDouble: Encoder[Double]   = Encoder(_ writeDouble _)
  implicit val forString: Encoder[String]   = Encoder(_ writeString _)

  implicit def forBoxedBoolean: Encoder[JBoolean] = forBoolean.asInstanceOf[Encoder[JBoolean]]
  implicit def forBoxedChar: Encoder[Character]   = forChar.asInstanceOf[Encoder[Character]]
  implicit def forBoxedByte: Encoder[JByte]       = forByte.asInstanceOf[Encoder[JByte]]
  implicit def forBoxedShort: Encoder[JShort]     = forShort.asInstanceOf[Encoder[JShort]]
  implicit def forBoxedInt: Encoder[Integer]      = forInt.asInstanceOf[Encoder[Integer]]
  implicit def forBoxedLong: Encoder[JLong]       = forLong.asInstanceOf[Encoder[JLong]]
  implicit def forBoxedFloat: Encoder[JFloat]     = forFloat.asInstanceOf[Encoder[JFloat]]
  implicit def forBoxedDouble: Encoder[JDouble]   = forDouble.asInstanceOf[Encoder[JDouble]]

  implicit val forUnit: Encoder[Unit] = Encoder((w, _) => w.writeInt(0))

  implicit val forByteArrayDefault: Encoder[Array[Byte]] = forByteArray(BaseEncoding.base64)

  def forByteArray(jsonBaseEncoding: BaseEncoding): Encoder[Array[Byte]] =
    Encoder { (w, x) =>
      if (w.writingJson) w.writeChars(jsonBaseEncoding.encode(x))
      else w.writeBytes(x)
    }

  implicit val forJBigInteger: Encoder[JBigInteger] =
    Encoder { (w, x) =>
      x.bitLength match {
        case n if n < 32        => w.writeInt(x.intValue)
        case n if n < 64        => w.writeLong(x.longValue)
        case 64 if x.signum > 0 => w.writeOverLong(negative = false, x.longValue)
        case 64                 => w.writeOverLong(negative = true, ~x.longValue)
        case _ if w.writingCbor =>
          val bytes = x.toByteArray
          w.writeTag(if (x.signum < 0) {
            Util.inPlaceNegate(bytes); Tag.NegativeBigNum
          } else Tag.PositiveBigNum)
          w.writeBytes(bytes)
        case _ => w.writeNumberString(x.toString(10))
      }
    }

  implicit val forBigInt: Encoder[BigInt] = forJBigInteger.contramap(_.bigInteger)

  implicit val forJBigDecimal: Encoder[JBigDecimal] =
    Encoder { (w, x) =>
      if (w.writingCbor) {
        if (x.scale != 0) w.writeTag(Tag.DecimalFraction).writeArrayHeader(2).writeInt(x.scale)
        w.write(x.unscaledValue)
      } else {
        if (x.scale != 0) w.writeNumberString(x.toString)
        else w.write(x.unscaledValue)
      }
    }

  implicit val forBigDecimal: Encoder[BigDecimal] = forJBigDecimal.contramap(_.bigDecimal)

  implicit val forByteArrayIterator: Encoder[Iterator[Array[Byte]]] =
    Encoder { (w, x) =>
      w.writeBytesStart()
      while (x.hasNext) w.writeBytes(x.next())
      w.writeBreak()
    }

  implicit def forBytesIterator[Bytes: ByteAccess]: Encoder[Iterator[Bytes]] =
    Encoder { (w, x) =>
      w.writeBytesStart()
      while (x.hasNext) w.writeBytes(x.next())
      w.writeBreak()
    }

  implicit val forStringIterator: Encoder[Iterator[String]] =
    Encoder { (w, x) =>
      w.writeTextStart()
      while (x.hasNext) w.writeString(x.next())
      w.writeBreak()
    }

  //#option-encoder
  implicit def forOption[T: Encoder]: Encoder.DefaultValueAware[Option[T]] =
    new Encoder.DefaultValueAware[Option[T]] {

      def write(w: Writer, value: Option[T]) =
        value match {
          case Some(x) => w.writeToArray(x)
          case None    => w.writeEmptyArray()
        }

      def withDefaultValue(defaultValue: Option[T]): Encoder[Option[T]] =
        if (defaultValue eq None) {
          new Encoder.PossiblyWithoutOutput[Option[T]] {
            def producesOutputFor(value: Option[T]) = value ne None
            def write(w: Writer, value: Option[T]) =
              value match {
                case Some(x) => w.write(x)
                case None    => w
              }
          }
        } else this
    }
  //#option-encoder

  implicit def forIndexedSeq[T: Encoder, M[X] <: IndexedSeq[X]]: DefaultValueAware[M[T]] =
    new DefaultValueAware[M[T]] {
      def write(w: Writer, value: M[T]) = w.writeIndexedSeq(value)

      def withDefaultValue(defaultValue: M[T]): Encoder[M[T]] =
        if (defaultValue.isEmpty) {
          new PossiblyWithoutOutput[M[T]] {
            def producesOutputFor(value: M[T]) = value.nonEmpty
            def write(w: Writer, value: M[T])  = if (value.nonEmpty) w.writeIndexedSeq(value) else w
          }
        } else this
    }

  implicit def forLinearSeq[T: Encoder, M[X] <: LinearSeq[X]]: DefaultValueAware[M[T]] =
    new DefaultValueAware[M[T]] {
      def write(w: Writer, value: M[T]) = w.writeLinearSeq(value)

      def withDefaultValue(defaultValue: M[T]): Encoder[M[T]] =
        if (defaultValue.isEmpty) {
          new PossiblyWithoutOutput[M[T]] {
            def producesOutputFor(value: M[T]) = value.nonEmpty
            def write(w: Writer, value: M[T])  = if (value.nonEmpty) w.writeLinearSeq(value) else w
          }
        } else this
    }

  implicit def forMap[A: Encoder, B: Encoder, M[X, Y] <: Map[X, Y]]: DefaultValueAware[M[A, B]] =
    new DefaultValueAware[M[A, B]] {
      def write(w: Writer, value: M[A, B]) = w.writeMap(value)

      def withDefaultValue(defaultValue: M[A, B]): Encoder[M[A, B]] =
        if (defaultValue.isEmpty) {
          new PossiblyWithoutOutput[M[A, B]] {
            def producesOutputFor(value: M[A, B]) = value.nonEmpty
            def write(w: Writer, value: M[A, B])  = if (value.nonEmpty) w.writeMap(value) else w
          }
        } else this
    }

  implicit def forArray[T: Encoder]: Encoder[Array[T]] =
    Encoder { (w, x) =>
      @tailrec def rec(w: Writer, ix: Int): w.type = if (ix < x.length) rec(w.write(x(ix)), ix + 1) else w
      if (w.writingJson) rec(w.writeArrayStart(), 0).writeBreak()
      else rec(w.writeArrayHeader(x.length), 0)
    }

  /**
    * The default [[Encoder]] for [[Either]] is not automatically in scope,
    * because there is no clear "standard" way of encoding instances of [[Either]].
    */
  object ForEither {

    implicit def default[A: Encoder, B: Encoder]: Encoder[Either[A, B]] =
      Encoder { (w, x) =>
        if (w.writingJson) w.writeArrayStart() else w.writeMapHeader(1)
        x match {
          case Left(a)  => w.writeInt(0).write(a)
          case Right(b) => w.writeInt(1).write(b)
        }
        if (w.writingJson) w.writeBreak() else w
      }
  }

  private val _toStringEncoder: Encoder[Any] = Encoder((w, x) => w.writeString(x.toString))
  def toStringEncoder[T]: Encoder[T]         = _toStringEncoder.asInstanceOf[Encoder[T]]

  object StringNumbers {
    implicit def charEncoder: Encoder[Char]     = Encoder.toStringEncoder[Char]
    implicit def byteEncoder: Encoder[Byte]     = Encoder.toStringEncoder[Byte]
    implicit def shortEncoder: Encoder[Short]   = Encoder.toStringEncoder[Short]
    implicit def intEncoder: Encoder[Int]       = Encoder.toStringEncoder[Int]
    implicit def longEncoder: Encoder[Long]     = Encoder.toStringEncoder[Long]
    implicit def floatEncoder: Encoder[Float]   = Encoder.toStringEncoder[Float]
    implicit def doubleEncoder: Encoder[Double] = Encoder.toStringEncoder[Double]

    implicit def boxedCharEncoder: Encoder[Character] = forChar.asInstanceOf[Encoder[Character]]
    implicit def boxedByteEncoder: Encoder[JByte]     = forByte.asInstanceOf[Encoder[JByte]]
    implicit def boxedShortEncoder: Encoder[JShort]   = forShort.asInstanceOf[Encoder[JShort]]
    implicit def boxedIntEncoder: Encoder[Integer]    = forInt.asInstanceOf[Encoder[Integer]]
    implicit def boxedLongEncoder: Encoder[JLong]     = forLong.asInstanceOf[Encoder[JLong]]
    implicit def boxedFloatEncoder: Encoder[JFloat]   = forFloat.asInstanceOf[Encoder[JFloat]]
    implicit def boxedDoubleEncoder: Encoder[JDouble] = forDouble.asInstanceOf[Encoder[JDouble]]
  }

  object StringBooleans {
    implicit val booleanEncoder: Encoder[Boolean]       = Encoder((w, x) => w.writeString(if (x) "true" else "false"))
    implicit def boxedBooleanEncoder: Encoder[JBoolean] = forBoolean.asInstanceOf[Encoder[JBoolean]]
  }

  object StringNulls {
    implicit val nullEncoder: Encoder[Null] = Encoder((w, _) => w.writeString("null"))
  }
}

sealed abstract class LowPrioEncoders extends TupleEncoders {

  implicit final def forIterableOnce[T: Encoder, M[X] <: XIterableOnceBound[X]]: Encoder[M[T]] =
    new Encoder.DefaultValueAware[M[T]] { self =>

      def write(w: Writer, value: M[T]) =
        value match {
          case x: IndexedSeq[T] => w.writeIndexedSeq(x)
          case x: LinearSeq[T]  => w.writeLinearSeq(x)
          case x                => w.writeIterableOnce(x)
        }

      def withDefaultValue(defaultValue: M[T]): Encoder[M[T]] =
        if (isEmpty(defaultValue)) {
          new Encoder.PossiblyWithoutOutput[M[T]] {
            def producesOutputFor(value: M[T]) = !isEmpty(value)
            def write(w: Writer, value: M[T])  = if (isEmpty(value)) w else self.write(w, value)
          }
        } else this

      private def isEmpty(value: M[T]): Boolean = {
        val ks = (value: XIterableOnce[T]).knownSize
        ks == 0 || (ks < 0) && value.iterator.isEmpty
      }
    }

  implicit final def forIterator[T: Encoder]: Encoder[Iterator[T]] = Encoder(_ writeIterator _)
}

/**
  * An [[AdtEncoder]] is an [[Encoder]] which encodes its values with an envelope holding the value's type id.
  *
  * It doesn't change or add to the outside interface of [[Encoder]] but merely serves as a marker
  * signaling that it takes on the responsibility of encoding the type id in addition to the value itself.
  * This allows outside encoders calling an [[AdtEncoder]] to delegate this responsibility rather than performing
  * the task themselves.
  */
trait AdtEncoder[T] extends Encoder[T]
