package com.wavesplatform.state.patch

import com.wavesplatform.common.utils.EitherExt2
import com.wavesplatform.lagonaki.mocks.TestBlock
import com.wavesplatform.settings.TestFunctionalitySettings
import com.wavesplatform.state.diffs._
import com.wavesplatform.transaction.Asset.Waves
import com.wavesplatform.transaction.GenesisTransaction
import com.wavesplatform.transaction.lease.LeaseTransaction
import com.wavesplatform.transaction.transfer._
import com.wavesplatform.{NoShrink, TransactionGen}
import org.scalatest.{Matchers, PropSpec}
import org.scalatestplus.scalacheck.{ScalaCheckPropertyChecks => PropertyChecks}

class CancelLeaseOverflowTest extends PropSpec with PropertyChecks with Matchers with TransactionGen with NoShrink {

  private val settings = TestFunctionalitySettings.Enabled.copy(blockVersion3AfterHeight = 5)

  property("CancelLeaseOverflow cancels active outgoing leases for accounts having negative spendable balances") {
    val leaseOverflowGen = for {
      sender1   <- accountGen
      sender2   <- accountGen
      recipient <- accountGen
      amount    <- positiveLongGen
      fee       <- smallFeeGen
      ts        <- timestampGen
    } yield (
      GenesisTransaction.create(sender1, amount + fee, ts).explicitGet(),
      GenesisTransaction.create(sender2, amount + fee * 2, ts).explicitGet(),
      LeaseTransaction.selfSigned(1.toByte, sender1, sender2, amount, fee, ts).explicitGet(),
      LeaseTransaction.selfSigned(1.toByte, sender2, recipient, amount, fee, ts).explicitGet(),
      TransferTransaction.selfSigned(1.toByte, sender2, recipient, Waves, amount, Waves, fee, Array.emptyByteArray, ts).explicitGet()
    )

    forAll(leaseOverflowGen) {
      case (gt1, gt2, lease1, lease2, tx) =>
        assertDiffAndState(
          Seq(TestBlock.create(Seq(gt1, gt2)), TestBlock.create(Seq(lease1)), TestBlock.create(Seq(lease2, tx)), TestBlock.create(Seq.empty)),
          TestBlock.create(Seq.empty),
          settings
        ) {
          case (_, newState) =>
            newState.leaseDetails(lease2.id()).forall(_.isActive) shouldBe false
            newState.leaseDetails(lease1.id()).exists(_.isActive) shouldBe true
        }
    }
  }
}
