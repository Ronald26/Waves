package com.wavesplatform.api.grpc

import com.google.protobuf.ByteString
import com.wavesplatform.account.{Address, PrivateKey, PublicKey}
import com.wavesplatform.block.BlockHeader
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils._
import com.wavesplatform.protobuf.block.{PBBlock, PBBlocks, VanillaBlock}
import com.wavesplatform.protobuf.transaction._
import com.wavesplatform.transaction.Proofs
import com.wavesplatform.{crypto, block => vb}

//noinspection ScalaStyle
trait PBImplicitConversions {
  implicit class VanillaTransactionConversions(tx: VanillaTransaction) {
    def toPB = PBTransactions.protobuf(tx)
  }

  implicit class PBSignedTransactionConversions(tx: PBSignedTransaction) {
    def toVanilla = PBTransactions.vanilla(tx).explicitGet()
  }

  implicit class PBTransactionConversions(tx: PBTransaction) {
    def toVanilla = PBSignedTransaction(Some(tx)).toVanilla
    def sender    = PublicKey(tx.senderPublicKey.toByteArray)

    def signed(signer: PrivateKey): PBSignedTransaction = {
      import com.wavesplatform.common.utils._
      PBSignedTransaction(
        Some(tx),
        Proofs.create(Seq(ByteStr(crypto.sign(signer, toVanilla.bodyBytes())))).explicitGet().map(bs => ByteString.copyFrom(bs.arr)))
    }
  }

  implicit class VanillaBlockConversions(block: VanillaBlock) {
    def toPB = PBBlocks.protobuf(block)
  }

  implicit class PBBlockConversions(block: PBBlock) {
    def toVanilla = PBBlocks.vanilla(block).explicitGet()
  }

  implicit class PBBlockHeaderConversionOps(header: PBBlock.Header) {
    def toVanilla(signature: ByteStr): vb.BlockHeader = {
      BlockHeader(
        header.version.toByte,
        header.timestamp,
        header.reference.toByteStr,
        header.baseTarget,
        header.generationSignature.toByteStr,
        header.generator.toPublicKey,
        header.featureVotes.map(intToShort),
        header.rewardVote,
        header.transactionsRoot.toByteStr
      )
    }
  }

  implicit class VanillaHeaderConversionOps(header: vb.BlockHeader) {
    def toPBHeader: PBBlock.Header = PBBlock.Header(
      0: Byte,
      header.reference.toPBByteString,
      header.baseTarget,
      header.generationSignature.toPBByteString,
      header.featureVotes.map(shortToInt),
      header.timestamp,
      header.version,
      ByteString.copyFrom(header.generator)
    )
  }

  implicit class PBRecipientConversions(r: Recipient) {
    def toAddress        = PBRecipients.toAddress(r).explicitGet()
    def toAlias          = PBRecipients.toAlias(r).explicitGet()
    def toAddressOrAlias = PBRecipients.toAddressOrAlias(r).explicitGet()
  }

  implicit class VanillaByteStrConversions(bytes: ByteStr) {
    def toPBByteString = ByteString.copyFrom(bytes.arr)
    def toPublicKey    = PublicKey(bytes)
  }

  implicit class PBByteStringConversions(bytes: ByteString) {
    def toByteStr          = ByteStr(bytes.toByteArray)
    def toPublicKey        = PublicKey(bytes.toByteArray)
    def toAddress: Address = PBRecipients.toAddress(this.toByteStr).fold(ve => throw new IllegalArgumentException(ve.toString), identity)
  }

  implicit def vanillaByteStrToPBByteString(bs: ByteStr): ByteString = bs.toPBByteString
  implicit def pbByteStringToVanillaByteStr(bs: ByteString): ByteStr = bs.toByteStr

  private[this] implicit def shortToInt(s: Short): Int = {
    java.lang.Short.toUnsignedInt(s)
  }

  private[this] def intToShort(int: Int): Short = {
    require(int >= 0 && int <= 65535, s"Short overflow: $int")
    int.toShort
  }
}
