package com.wavesplatform.it.sync

import com.typesafe.config.{Config, ConfigFactory}
import com.wavesplatform.account.KeyPair
import com.wavesplatform.common.utils.EitherExt2
import com.wavesplatform.it.Node
import com.wavesplatform.it.api.SyncHttpApi._
import com.wavesplatform.it.transactions.NodesFromDocker
import com.wavesplatform.transaction.Asset.Waves
import com.wavesplatform.transaction.transfer.TransferTransaction
import monix.eval.Task
import monix.execution.Scheduler
import org.scalatest.{CancelAfterFailure, FunSuite, Matchers}

import scala.concurrent.duration._
import scala.util.{Random, Try}

class UtxSuite extends FunSuite with CancelAfterFailure with NodesFromDocker with Matchers {
  override protected def nodeConfigs: Seq[Config] = UtxSuite.Configs

  val miner: Node    = nodes.head
  val notMiner: Node = nodes(1)

  val ENOUGH_FEE = 5000000
  val AMOUNT     = ENOUGH_FEE * 10

  test("Invalid transaction should be removed from from utx") {
    val seed = Array.fill(32)(-1: Byte)

    Random.nextBytes(seed)

    val account = KeyPair(seed)

    val transferToAccount = TransferTransaction
      .selfSigned(1.toByte, miner.privateKey, account, Waves, AMOUNT, Waves, ENOUGH_FEE, Array.emptyByteArray, System.currentTimeMillis())
      .explicitGet()

    miner.signedBroadcast(transferToAccount.json())

    nodes.waitForHeightAriseAndTxPresent(transferToAccount.id().toString)

    val firstTransfer = TransferTransaction
      .selfSigned(1.toByte, account, miner.privateKey, Waves, AMOUNT - ENOUGH_FEE, Waves, ENOUGH_FEE, Array.emptyByteArray, System.currentTimeMillis())
      .explicitGet()

    val secondTransfer = TransferTransaction
      .selfSigned(1.toByte, account, notMiner.privateKey, Waves, AMOUNT - ENOUGH_FEE, Waves, ENOUGH_FEE, Array.emptyByteArray, System.currentTimeMillis())
      .explicitGet()

    val tx2Id = notMiner.signedBroadcast(secondTransfer.json()).id
    val tx1Id = miner.signedBroadcast(firstTransfer.json()).id

    waitForEmptyUtx(nodes)

    val exactlyOneTxInBlockchain =
      txInBlockchain(tx1Id, nodes) ^ txInBlockchain(tx2Id, nodes)

    assert(exactlyOneTxInBlockchain, "Only one tx should be in blockchain")
  }

  def waitForEmptyUtx(nodes: Seq[Node]): Unit = {
    implicit val sch: Scheduler = monix.execution.Scheduler.global

    def loop(): Task[Unit] = {
      val utxIds = nodes.map(_.utx.size)

      if (utxIds.sum != 0) {
        Task
          .sleep(1.second)
          .flatMap(_ => loop())
      } else {
        Task.pure(())
      }
    }

    loop().runSyncUnsafe(6.minutes)
  }

  def txInBlockchain(txId: String, nodes: Seq[Node]): Boolean = {
    nodes.forall { node =>
      Try(node.transactionInfo(txId)).isSuccess
    }
  }
}

object UtxSuite {
  import com.wavesplatform.it.NodeConfigs._
  private val minerConfig = ConfigFactory.parseString(s"""
                                                         |waves {
                                                         |  synchronization.synchronization-timeout = 10s
                                                         |  blockchain.custom.functionality {
                                                         |    pre-activated-features.1 = 0
                                                         |    generation-balance-depth-from-50-to-1000-after-height = 100
                                                         |  }
                                                         |  miner.quorum = 0
                                                         |}""".stripMargin)

  private val notMinerConfig = ConfigFactory.parseString(s"""
                                                            |waves {
                                                            |  synchronization.synchronization-timeout = 10s
                                                            |  blockchain.custom.functionality {
                                                            |    pre-activated-features.1 = 0
                                                            |    generation-balance-depth-from-50-to-1000-after-height = 100
                                                            |  }
                                                            |  miner.enable = no
                                                            |}""".stripMargin)

  val Configs: Seq[Config] = Seq(
    minerConfig.withFallback(Default.head),
    notMinerConfig.withFallback(Default(1)),
  )

}
