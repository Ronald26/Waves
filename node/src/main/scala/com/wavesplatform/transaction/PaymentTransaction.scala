package com.wavesplatform.transaction

import com.wavesplatform.account.{Address, KeyPair, PublicKey}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.crypto
import com.wavesplatform.lang.ValidationError
import com.wavesplatform.transaction.serialization.impl.PaymentTxSerializer
import monix.eval.Coeval
import play.api.libs.json.JsObject

import scala.reflect.ClassTag
import scala.util.Try

case class PaymentTransaction private (sender: PublicKey, recipient: Address, amount: Long, fee: Long, timestamp: Long, signature: ByteStr)
    extends SignedTransaction
    with TxWithFee.InWaves {

  override val builder             = PaymentTransaction
  override val id: Coeval[ByteStr] = Coeval.evalOnce(signature)

  override val bodyBytes: Coeval[Array[Byte]] = Coeval.evalOnce(builder.serializer.bodyBytes(this))
  override val bytes: Coeval[Array[Byte]]     = Coeval.evalOnce(builder.serializer.toBytes(this))
  override val json: Coeval[JsObject]         = Coeval.evalOnce(builder.serializer.toJson(this))
}

object PaymentTransaction extends TransactionParser {
  override type TransactionT = PaymentTransaction

  override val typeId: TxType                    = 2
  override val supportedVersions: Set[TxVersion] = Set(1)
  override val classTag                          = ClassTag(classOf[PaymentTransaction])

  val serializer = PaymentTxSerializer

  override def parseBytes(bytes: Array[TxVersion]): Try[PaymentTransaction] =
    serializer.parseBytes(bytes)

  def create(sender: KeyPair, recipient: Address, amount: Long, fee: Long, timestamp: Long): Either[ValidationError, TransactionT] = {
    create(sender, recipient, amount, fee, timestamp, ByteStr.empty).right.map(unsigned => {
      unsigned.copy(signature = ByteStr(crypto.sign(sender, unsigned.bodyBytes())))
    })
  }

  def create(
      sender: PublicKey,
      recipient: Address,
      amount: Long,
      fee: Long,
      timestamp: Long,
      signature: ByteStr
  ): Either[ValidationError, TransactionT] = {
    if (amount <= 0) {
      Left(TxValidationError.NonPositiveAmount(amount, "waves")) //CHECK IF AMOUNT IS POSITIVE
    } else if (fee <= 0) {
      Left(TxValidationError.InsufficientFee()) //CHECK IF FEE IS POSITIVE
    } else if (Try(Math.addExact(amount, fee)).isFailure) {
      Left(TxValidationError.OverflowError) // CHECK THAT fee+amount won't overflow Long
    } else {
      Right(PaymentTransaction(sender, recipient, amount, fee, timestamp, signature))
    }
  }
}
