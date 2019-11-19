package com.wavesplatform.database

import java.nio.ByteBuffer

import com.google.common.primitives.{Ints, Longs, Shorts}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.state.TxNum

object KeyHelpers {
  def h(prefix: Short, height: Int): Array[Byte] =
    ByteBuffer.allocate(6).putShort(prefix).putInt(height).array()

  def hBytes(prefix: Short, bytes: Array[Byte], height: Int): Array[Byte] =
    ByteBuffer.allocate(6 + bytes.length).putShort(prefix).put(bytes).putInt(height).array()

  def bytes(prefix: Short, bytes: Array[Byte]): Array[Byte] =
    ByteBuffer.allocate(2 + bytes.length).putShort(prefix).put(bytes).array()

  def addr(prefix: Short, addressId: BigInt): Array[Byte] = bytes(prefix, addressId.toByteArray)

  def hash(prefix: Short, hashBytes: ByteStr): Array[Byte] = bytes(prefix, hashBytes.arr)

  def hAddr(prefix: Short, height: Int, addressId: BigInt): Array[Byte] = hBytes(prefix, addressId.toByteArray, height)

  def hNum(prefix: Short, height: Int, num: TxNum): Array[Byte] = bytes(prefix, Ints.toByteArray(height) ++ Shorts.toByteArray(num))

  def historyKey(name: String, prefix: Short, b: Array[Byte]): Key[Seq[Int]] = Key(name, bytes(prefix, b), readIntSeq, writeIntSeq)

  def intKey(name: String, prefix: Short, default: Int = 0): Key[Int] =
    Key(name, Shorts.toByteArray(prefix), Option(_).fold(default)(Ints.fromByteArray), Ints.toByteArray)

  def longKey(name: String, prefix: Short, default: Long = 0): Key[Long] =
    Key(name, Longs.toByteArray(prefix), Option(_).fold(default)(Longs.fromByteArray), Longs.toByteArray)

  def bytesSeqNr(name: String, prefix: Short, b: Array[Byte], default: Int = 0): Key[Int] =
    Key(name, bytes(prefix, b), Option(_).fold(default)(Ints.fromByteArray), Ints.toByteArray)

  def unsupported[A](message: String): A => Array[Byte] = _ => throw new UnsupportedOperationException(message)
}
