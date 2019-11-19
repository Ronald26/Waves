package com.wavesplatform.state

import java.util.concurrent.locks.{Lock, ReentrantReadWriteLock}

import cats.implicits._
import cats.kernel.Monoid
import com.wavesplatform.account.{Address, Alias}
import com.wavesplatform.api.BlockMeta
import com.wavesplatform.block.Block.BlockId
import com.wavesplatform.block.{Block, MicroBlock, SignedBlockHeader}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.database.LevelDBWriter
import com.wavesplatform.features.BlockchainFeatures
import com.wavesplatform.features.FeatureProvider._
import com.wavesplatform.lang.ValidationError
import com.wavesplatform.lang.script.Script
import com.wavesplatform.metrics.{TxsInBlockchainStats, _}
import com.wavesplatform.mining.{MiningConstraint, MiningConstraints}
import com.wavesplatform.settings.{BlockchainSettings, WavesSettings}
import com.wavesplatform.state.diffs.BlockDiffer
import com.wavesplatform.state.reader.{CompositeBlockchain, LeaseDetails}
import com.wavesplatform.transaction.Asset.IssuedAsset
import com.wavesplatform.transaction.TxValidationError.{BlockAppendError, GenericError, MicroBlockAppendError}
import com.wavesplatform.transaction._
import com.wavesplatform.transaction.lease._
import com.wavesplatform.transaction.transfer.TransferTransaction
import com.wavesplatform.utils.{ScorexLogging, Time, UnsupportedFeature, forceStopApplication}
import kamon.Kamon
import monix.reactive.subjects.ReplaySubject
import monix.reactive.{Observable, Observer}

class BlockchainUpdaterImpl(
    private val leveldb: LevelDBWriter,
    spendableBalanceChanged: Observer[(Address, Asset)],
    wavesSettings: WavesSettings,
    time: Time,
    blockchainUpdated: Observer[BlockchainUpdated]
) extends Blockchain
    with BlockchainUpdater
    with NG
    with ScorexLogging {

  import com.wavesplatform.state.BlockchainUpdaterImpl._
  import wavesSettings.blockchainSettings.functionalitySettings

  private def inLock[R](l: Lock, f: => R) = {
    try {
      l.lock()
      val res = f
      res
    } finally {
      l.unlock()
    }
  }
  private val lock                     = new ReentrantReadWriteLock
  private def writeLock[B](f: => B): B = inLock(lock.writeLock(), f)
  private def readLock[B](f: => B): B  = inLock(lock.readLock(), f)

  private lazy val maxBlockReadinessAge = wavesSettings.minerSettings.intervalAfterLastBlockThenGenerationIsAllowed.toMillis

  private var ngState: Option[NgState]              = Option.empty
  private var restTotalConstraint: MiningConstraint = MiningConstraints(leveldb, leveldb.height).total

  private val internalLastBlockInfo = ReplaySubject.createLimited[LastBlockInfo](1)

  private def lastBlockReward: Option[Long] = this.blockReward(this.height)

  private def publishLastBlockInfo(): Unit =
    for (id <- this.lastBlockId; ts <- ngState.map(_.base.header.timestamp).orElse(leveldb.lastBlockTimestamp)) {
      val blockchainReady = ts + maxBlockReadinessAge > time.correctedTime()
      internalLastBlockInfo.onNext(LastBlockInfo(id, height, score, blockchainReady))
    }

  publishLastBlockInfo()

  def liquidBlock(id: ByteStr): Option[Block] = readLock(ngState.flatMap(_.totalDiffOf(id).map(_._1)))

  def liquidBlockMeta: Option[BlockMeta] = readLock(ngState.map { ng =>
    val (_, _, totalFee) = ng.bestLiquidDiffAndFees
    val b = ng.bestLiquidBlock
    BlockMeta(b.header, b.signature, height, b.bytes().length, b.transactionData.length, totalFee, ng.reward)
  })

  @noinline
  def bestLiquidDiff: Option[Diff] = readLock(ngState.map(_.bestLiquidDiff))

  def bestLiquidDiffAndFees: Option[(Diff, Long, Long)] = readLock(ngState.map(_.bestLiquidDiffAndFees))

  override val settings: BlockchainSettings = wavesSettings.blockchainSettings

  override def isLastBlockId(id: ByteStr): Boolean = readLock {
    ngState.fold(leveldb.lastBlockId.contains(id))(_.contains(id))
  }

  override val lastBlockInfo: Observable[LastBlockInfo] = internalLastBlockInfo

  private def featuresApprovedWithBlock(block: Block): Set[Short] = {
    val height = leveldb.height + 1

    val featuresCheckPeriod        = functionalitySettings.activationWindowSize(height)
    val blocksForFeatureActivation = functionalitySettings.blocksForFeatureActivation(height)

    if (height % featuresCheckPeriod == 0) {
      val approvedFeatures = leveldb
        .featureVotes(height)
        .map { case (feature, votes) => feature -> (if (block.header.featureVotes.contains(feature)) votes + 1 else votes) }
        .filter { case (_, votes) => votes >= blocksForFeatureActivation }
        .keySet

      if (approvedFeatures.nonEmpty) log.info(s"${displayFeatures(approvedFeatures)} APPROVED at height $height")

      val unimplementedApproved = approvedFeatures.diff(BlockchainFeatures.implemented)
      if (unimplementedApproved.nonEmpty) {
        log.warn(s"""UNIMPLEMENTED ${displayFeatures(unimplementedApproved)} APPROVED ON BLOCKCHAIN
                    |PLEASE, UPDATE THE NODE AS SOON AS POSSIBLE
                    |OTHERWISE THE NODE WILL BE STOPPED OR FORKED UPON FEATURE ACTIVATION""".stripMargin)
      }

      val activatedFeatures: Set[Short] = leveldb.activatedFeaturesAt(height)

      val unimplementedActivated = activatedFeatures.diff(BlockchainFeatures.implemented)
      if (unimplementedActivated.nonEmpty) {
        log.error(s"UNIMPLEMENTED ${displayFeatures(unimplementedActivated)} ACTIVATED ON BLOCKCHAIN")
        log.error("PLEASE, UPDATE THE NODE IMMEDIATELY")
        if (wavesSettings.featuresSettings.autoShutdownOnUnsupportedFeature) {
          log.error("FOR THIS REASON THE NODE WAS STOPPED AUTOMATICALLY")
          forceStopApplication(UnsupportedFeature)
        } else log.error("OTHERWISE THE NODE WILL END UP ON A FORK")
      }

      approvedFeatures
    } else {

      Set.empty
    }
  }

  private def nextReward(): Option[Long] = {
    val settings   = this.settings.rewardsSettings
    val nextHeight = this.height + 1

    leveldb
      .featureActivationHeight(BlockchainFeatures.BlockReward.id)
      .filter(_ <= nextHeight)
      .flatMap { activatedAt =>
        val mayBeReward     = lastBlockReward
        val mayBeTimeToVote = nextHeight - activatedAt

        mayBeReward match {
          case Some(reward) if mayBeTimeToVote > 0 && mayBeTimeToVote % settings.term == 0 =>
            Some((blockRewardVotes(this.height).filter(_ >= 0), reward))
          case None if mayBeTimeToVote >= 0 =>
            Some((Seq(), settings.initial))
          case _ => None
        }
      }
      .flatMap {
        case (votes, currentReward) =>
          val lt        = votes.count(_ < currentReward)
          val gt        = votes.count(_ > currentReward)
          val threshold = settings.votingInterval / 2 + 1

          if (lt >= threshold)
            Some(math.max(currentReward - settings.minIncrement, 0))
          else if (gt >= threshold)
            Some(currentReward + settings.minIncrement)
          else
            Some(currentReward)
      }
      .orElse(lastBlockReward)
  }

  override def processBlock(block: Block, hitSource: ByteStr, verify: Boolean = true): Either[ValidationError, Option[DiscardedTransactions]] =
    writeLock {
      val height                             = leveldb.height
      val notImplementedFeatures: Set[Short] = leveldb.activatedFeaturesAt(height).diff(BlockchainFeatures.implemented)

      Either
        .cond(
          !wavesSettings.featuresSettings.autoShutdownOnUnsupportedFeature || notImplementedFeatures.isEmpty,
          (),
          GenericError(s"UNIMPLEMENTED ${displayFeatures(notImplementedFeatures)} ACTIVATED ON BLOCKCHAIN, UPDATE THE NODE IMMEDIATELY")
        )
        .flatMap[ValidationError, Option[DiscardedTransactions]](
          _ =>
            (ngState match {
              case None =>
                leveldb.lastBlockId match {
                  case Some(uniqueId) if uniqueId != block.header.reference =>
                    val logDetails = s"The referenced block(${block.header.reference})" +
                      s" ${if (leveldb.contains(block.header.reference)) "exits, it's not last persisted" else "doesn't exist"}"
                    Left(BlockAppendError(s"References incorrect or non-existing block: " + logDetails, block))
                  case lastBlockId =>
                    val height            = lastBlockId.fold(0)(leveldb.unsafeHeightOf)
                    val miningConstraints = MiningConstraints(leveldb, height)
                    val reward            = nextReward()
                    BlockDiffer
                      .fromBlock(
                        CompositeBlockchain(leveldb, carry = leveldb.carryFee, reward = reward),
                        leveldb.lastBlock,
                        block,
                        miningConstraints.total,
                        verify
                      )
                      .map(r => Option((r, Seq.empty[Transaction], reward, hitSource)))
                }
              case Some(ng) =>
                if (ng.base.header.reference == block.header.reference) {
                  if (block.blockScore() > ng.base.blockScore()) {
                    val height            = leveldb.unsafeHeightOf(ng.base.header.reference)
                    val miningConstraints = MiningConstraints(leveldb, height)

                    BlockchainUpdateNotifier.notifyMicroBlockRollback(blockchainUpdated, block.header.reference, height)

                    BlockDiffer
                      .fromBlock(
                        CompositeBlockchain(leveldb, carry = leveldb.carryFee, reward = ng.reward),
                        leveldb.lastBlock,
                        block,
                        miningConstraints.total,
                        verify
                      )
                      .map { r =>
                        log.trace(
                          s"Better liquid block(score=${block.blockScore()}) received and applied instead of existing(score=${ng.base.blockScore()})"
                        )
                        Some((r, ng.transactions, ng.reward, hitSource))
                      }
                  } else if (areVersionsOfSameBlock(block, ng.base)) {
                    if (block.transactionData.lengthCompare(ng.transactions.size) <= 0) {
                      log.trace(s"Existing liquid block is better than new one, discarding $block")
                      Right(None)
                    } else {
                      log.trace(s"New liquid block is better version of existing, swapping")
                      val height            = leveldb.unsafeHeightOf(ng.base.header.reference)
                      val miningConstraints = MiningConstraints(leveldb, height)

                      BlockchainUpdateNotifier
                        .notifyMicroBlockRollback(blockchainUpdated, block.header.reference, height)

                      BlockDiffer
                        .fromBlock(
                          CompositeBlockchain(leveldb, carry = leveldb.carryFee, reward = ng.reward),
                          leveldb.lastBlock,
                          block,
                          miningConstraints.total,
                          verify
                        )
                        .map(r => Some((r, Seq.empty[Transaction], ng.reward, hitSource)))
                    }
                  } else
                    Left(
                      BlockAppendError(
                        s"Competitors liquid block $block(score=${block.blockScore()}) is not better than existing (ng.base ${ng.base}(score=${ng.base
                          .blockScore()}))",
                        block
                      )
                    )
                } else
                  metrics.forgeBlockTimeStats.measureSuccessful(ng.totalDiffOf(block.header.reference)) match {
                    case None => Left(BlockAppendError(s"References incorrect or non-existing block", block))
                    case Some((referencedForgedBlock, referencedLiquidDiff, carry, totalFee, discarded)) =>
                      if (!verify || referencedForgedBlock.signatureValid()) {
                        val height = leveldb.heightOf(referencedForgedBlock.header.reference).getOrElse(0)

                        if (discarded.nonEmpty) {
                          BlockchainUpdateNotifier
                            .notifyMicroBlockRollback(blockchainUpdated, referencedForgedBlock.uniqueId, height)
                          metrics.microBlockForkStats.increment()
                          metrics.microBlockForkHeightStats.record(discarded.size)
                        }

                        val constraint: MiningConstraint = {
                          val miningConstraints = MiningConstraints(leveldb, height)
                          miningConstraints.total
                        }

                        val prevReward = ng.reward
                        val reward     = nextReward()

                        val prevHitSource = ng.hitSource

                        val liquidDiffWithCancelledLeases = ng.cancelExpiredLeases(referencedLiquidDiff)

                        val diff = BlockDiffer
                          .fromBlock(
                            CompositeBlockchain(leveldb, Some(liquidDiffWithCancelledLeases), Some(referencedForgedBlock), carry, reward),
                            Some(referencedForgedBlock),
                            block,
                            constraint,
                            verify
                          )

                        diff.map { hardenedDiff =>
                          leveldb.append(liquidDiffWithCancelledLeases, carry, totalFee, prevReward, prevHitSource, referencedForgedBlock)
                          TxsInBlockchainStats.record(ng.transactions.size)
                          Some((hardenedDiff, discarded.flatMap(_.transactionData), reward, hitSource))
                        }
                      } else {
                        val errorText = s"Forged block has invalid signature: base: ${ng.base}, requested reference: ${block.header.reference}"
                        log.error(errorText)
                        Left(BlockAppendError(errorText, block))
                      }
                  }
            }).map {
              _ map {
                case (BlockDiffer.Result(newBlockDiff, carry, totalFee, updatedTotalConstraint, detailedDiff), discarded, reward, hitSource) =>
                  val newHeight   = leveldb.height + 1
                  val prevNgState = ngState

                  restTotalConstraint = updatedTotalConstraint
                  ngState = Some(
                    new NgState(
                      block,
                      newBlockDiff,
                      carry,
                      totalFee,
                      featuresApprovedWithBlock(block),
                      reward,
                      hitSource,
                      cancelLeases(collectLeasesToCancel(newHeight))
                    )
                  )
                  notifyChangedSpendable(prevNgState, ngState)
                  publishLastBlockInfo()

                  if ((block.header.timestamp > time
                        .getTimestamp() - wavesSettings.minerSettings.intervalAfterLastBlockThenGenerationIsAllowed.toMillis) || (newHeight % 100 == 0)) {
                    log.info(s"New height: $newHeight")
                  }

                  BlockchainUpdateNotifier.notifyProcessBlock(blockchainUpdated, block, detailedDiff, leveldb)

                  discarded
              }
            }
        )
    }

  private def collectLeasesToCancel(newHeight: Int): Seq[LeaseTransaction] =
    if (leveldb.isFeatureActivated(BlockchainFeatures.LeaseExpiration, newHeight)) {
      val toHeight = newHeight - leveldb.settings.functionalitySettings.leaseExpiration
      val fromHeight = leveldb.featureActivationHeight(BlockchainFeatures.LeaseExpiration.id) match {
        case Some(activationHeight) if activationHeight == newHeight => 1
        case _                                                       => toHeight
      }
      log.trace(s"Collecting leases created within [$fromHeight, $toHeight]")
      leveldb.collectActiveLeases(_ => true)
    } else Seq.empty

  private def cancelLeases(leaseTransactions: Seq[LeaseTransaction]): Map[ByteStr, Diff] =
    (for {
      lt        <- leaseTransactions
      recipient <- leveldb.resolveAlias(lt.recipient).toSeq
    } yield lt.id() -> Diff.empty.copy(
      portfolios = Map(
        lt.sender.toAddress -> Portfolio(0, LeaseBalance(0, -lt.amount), Map.empty),
        recipient           -> Portfolio(0, LeaseBalance(-lt.amount, 0), Map.empty)
      ),
      leaseState = Map(lt.id() -> false)
    )).toMap

  override def removeAfter(blockId: ByteStr): Either[ValidationError, Seq[(Block, ByteStr)]] = writeLock {
    log.info(s"Removing blocks after ${blockId.trim} from blockchain")

    val prevNgState = ngState
    val result = if (prevNgState.exists(_.contains(blockId))) {
      log.trace("Resetting liquid block, no rollback is necessary")
      BlockchainUpdateNotifier.notifyMicroBlockRollback(blockchainUpdated, blockId, leveldb.height)
      Right(Seq.empty)
    } else {
      val discardedNgBlock = prevNgState.map(ng => (ng.bestLiquidBlock, ng.hitSource)).toSeq
      ngState = None
      leveldb
        .rollbackTo(blockId)
        .map { bs =>
          BlockchainUpdateNotifier.notifyRollback(blockchainUpdated, blockId, leveldb.height)
          bs ++ discardedNgBlock
        }
        .leftMap(err => GenericError(err))
    }

    notifyChangedSpendable(prevNgState, ngState)
    publishLastBlockInfo()
    result
  }

  private def notifyChangedSpendable(prevNgState: Option[NgState], newNgState: Option[NgState]): Unit = {
    val changedPortfolios = (prevNgState, newNgState) match {
      case (Some(p), Some(n)) => diff(p.bestLiquidDiff.portfolios, n.bestLiquidDiff.portfolios)
      case (Some(x), _)       => x.bestLiquidDiff.portfolios
      case (_, Some(x))       => x.bestLiquidDiff.portfolios
      case _                  => Map.empty
    }

    changedPortfolios.foreach {
      case (addr, p) =>
        p.assetIds.view
          .filter(x => p.spendableBalanceOf(x) != 0)
          .foreach(assetId => spendableBalanceChanged.onNext(addr -> assetId))
    }
  }

  override def processMicroBlock(microBlock: MicroBlock, verify: Boolean = true): Either[ValidationError, Unit] = writeLock {
    ngState match {
      case None =>
        Left(MicroBlockAppendError("No base block exists", microBlock))
      case Some(ng) if ng.base.header.generator.toAddress != microBlock.sender.toAddress =>
        Left(MicroBlockAppendError("Base block has been generated by another account", microBlock))
      case Some(ng) =>
        ng.lastMicroBlock match {
          case None if ng.base.uniqueId != microBlock.prevResBlockSig =>
            metrics.blockMicroForkStats.increment()
            Left(MicroBlockAppendError("It's first micro and it doesn't reference base block(which exists)", microBlock))
          case Some(prevMicro) if prevMicro.totalResBlockSig != microBlock.prevResBlockSig =>
            metrics.microMicroForkStats.increment()
            Left(MicroBlockAppendError("It doesn't reference last known microBlock(which exists)", microBlock))
          case _ =>
            for {
              _ <- microBlock.signaturesValid()
              blockDifferResult <- {
                BlockDiffer.fromMicroBlock(this, leveldb.lastBlockTimestamp, microBlock, ng.base.header.timestamp, restTotalConstraint, verify)
              }
            } yield {
              val BlockDiffer.Result(diff, carry, totalFee, updatedMdConstraint, detailedDiff) = blockDifferResult
              BlockchainUpdateNotifier.notifyProcessMicroBlock(blockchainUpdated, microBlock, detailedDiff, leveldb)
              restTotalConstraint = updatedMdConstraint
              ng.append(microBlock, diff, carry, totalFee, System.currentTimeMillis)
              log.info(s"$microBlock appended")
              internalLastBlockInfo.onNext(LastBlockInfo(microBlock.totalResBlockSig, height, score, ready = true))

              for {
                (addr, p) <- diff.portfolios
                assetId   <- p.assetIds
              } spendableBalanceChanged.onNext(addr -> assetId)
            }
        }
    }
  }

  def shutdown(): Unit = {
    internalLastBlockInfo.onComplete()
  }

  private def newlyApprovedFeatures = ngState.fold(Map.empty[Short, Int])(_.approvedFeatures.map(_ -> height).toMap)

  override def approvedFeatures: Map[Short, Int] = readLock {
    newlyApprovedFeatures ++ leveldb.approvedFeatures
  }

  override def activatedFeatures: Map[Short, Int] = readLock {
    newlyApprovedFeatures.mapValues(_ + functionalitySettings.activationWindowSize(height)) ++ leveldb.activatedFeatures
  }

  override def featureVotes(height: Int): Map[Short, Int] = readLock {
    val innerVotes = leveldb.featureVotes(height)
    ngState match {
      case Some(ng) if this.height <= height =>
        val ngVotes = ng.base.header.featureVotes.map { featureId =>
          featureId -> (innerVotes.getOrElse(featureId, 0) + 1)
        }.toMap

        innerVotes ++ ngVotes
      case _ => innerVotes
    }
  }

  override def blockReward(height: Int): Option[Long] = readLock {
    leveldb.blockReward(height) match {
      case r @ Some(_) => r
      case None        => ngState.collect { case ng if leveldb.height + 1 == height => ng.reward }.flatten
    }
  }

  override def blockRewardVotes(height: Int): Seq[Long] = readLock {
    activatedFeatures.get(BlockchainFeatures.BlockReward.id) match {
      case Some(activatedAt) if activatedAt <= height =>
        ngState match {
          case None => leveldb.blockRewardVotes(height)
          case Some(ng) =>
            val innerVotes = leveldb.blockRewardVotes(height)
            if (height == this.height && settings.rewardsSettings.votingWindow(activatedAt, height).contains(height))
              innerVotes :+ ng.base.header.rewardVote
            else innerVotes
        }
      case None => Seq()
    }
  }

  override def wavesAmount(height: Int): BigInt = readLock {
    ngState match {
      case Some(ng) if this.height == height =>
        leveldb.wavesAmount(height - 1) + ng.reward.map(BigInt(_)).getOrElse(BigInt(0))
      case _ => leveldb.wavesAmount(height)
    }
  }

  override def height: Int = readLock {
    leveldb.height + ngState.fold(0)(_ => 1)
  }

  override def heightOf(blockId: BlockId): Option[Int] = readLock {
    ngState
      .collect {
        case ng if ng.contains(blockId) => this.height
      }
      .orElse(leveldb.heightOf(blockId))
  }

  override def microBlock(id: BlockId): Option[MicroBlock] = readLock {
    for {
      ng <- ngState
      mb <- ng.microBlock(id)
    } yield mb
  }

  override def microblockIds: Seq[BlockId] = readLock {
    ngState.fold(Seq.empty[BlockId])(_.microBlockIds)
  }

  override def bestLastBlockInfo(maxTimestamp: Long): Option[BlockMinerInfo] = readLock {
    ngState
      .map(_.bestLastBlockInfo(maxTimestamp))
      .orElse(
        leveldb.lastBlockHeader.map { sh =>
          BlockMinerInfo(sh.header.baseTarget, sh.header.generationSignature, sh.header.timestamp, sh.signature)
        }
      )
  }

  override def score: BigInt = readLock {
    leveldb.score + ngState.fold(BigInt(0))(_.bestLiquidBlock.blockScore())
  }

  override def carryFee: Long = readLock {
    ngState.map(_.carryFee).getOrElse(leveldb.carryFee)
  }

  override def blockHeader(height: Int): Option[SignedBlockHeader] = readLock {
    if (height == leveldb.height + 1)
      ngState.map(
        x => SignedBlockHeader(x.bestLiquidBlock.header, x.bestLiquidBlock.signature)
      )
    else
      leveldb.blockHeader(height)
  }

  override def transferById(id: BlockId): Option[(Int, TransferTransaction)] = readLock {
    ngState
      .fold(Diff.empty)(_.bestLiquidDiff)
      .transactionMap()
      .get(id)
      .collect {
        case (tx: TransferTransaction, _) => (height, tx)
      }
      .orElse(leveldb.transferById(id))
  }

  override def transactionInfo(id: ByteStr): Option[(Int, Transaction)] = readLock {
    ngState
      .fold(Diff.empty)(_.bestLiquidDiff)
      .transactionMap()
      .get(id)
      .map(t => (this.height, t._1))
      .orElse(leveldb.transactionInfo(id))
  }

  override def containsTransaction(tx: Transaction): Boolean = readLock {
    ngState.fold(leveldb.containsTransaction(tx)) { ng =>
      ng.bestLiquidDiff.transactionMap().contains(tx.id()) || leveldb.containsTransaction(tx)
    }
  }

  override def assetDescription(id: IssuedAsset): Option[AssetDescription] = readLock {
    ngState.fold(leveldb.assetDescription(id)) { ng =>
      CompositeBlockchain(leveldb, Some(ng.bestLiquidDiff)).assetDescription(id)
    }
  }

  override def resolveAlias(alias: Alias): Either[ValidationError, Address] = readLock {
    ngState.fold(leveldb.resolveAlias(alias)) { ng =>
      CompositeBlockchain(leveldb, Some(ng.bestLiquidDiff)).resolveAlias(alias)
    }
  }

  override def leaseDetails(leaseId: ByteStr): Option[LeaseDetails] = readLock {
    ngState match {
      case Some(ng) =>
        leveldb.leaseDetails(leaseId).map(ld => ld.copy(isActive = ng.bestLiquidDiff.leaseState.getOrElse(leaseId, ld.isActive))) orElse
          ng.bestLiquidDiff.transactionMap().get(leaseId).collect {
            case (lt: LeaseTransaction, _) =>
              LeaseDetails(lt.sender, lt.recipient, this.height, lt.amount, ng.bestLiquidDiff.leaseState(lt.id()))
          }
      case None =>
        leveldb.leaseDetails(leaseId)
    }
  }

  override def filledVolumeAndFee(orderId: ByteStr): VolumeAndFee = readLock {
    ngState.fold(leveldb.filledVolumeAndFee(orderId))(
      _.bestLiquidDiff.orderFills.get(orderId).orEmpty.combine(leveldb.filledVolumeAndFee(orderId))
    )
  }

  private[this] def lposPortfolioFromNG(a: Address, mb: ByteStr): Portfolio = readLock {
    val diffPf  = ngState.fold(Portfolio.empty)(_.balanceDiffAt(a, mb))
    val lease   = leveldb.leaseBalance(a)
    val balance = leveldb.balance(a)
    Portfolio(balance, lease, Map.empty).combine(diffPf)
  }

  /** Retrieves Waves balance snapshot in the [from, to] range (inclusive) */
  override def balanceSnapshots(address: Address, from: Int, to: BlockId): Seq[BalanceSnapshot] = readLock {
    val blockchainBlock = leveldb.heightOf(to)
    if (blockchainBlock.nonEmpty || ngState.isEmpty) {
      leveldb.balanceSnapshots(address, from, to)
    } else {
      val bs = BalanceSnapshot(height, lposPortfolioFromNG(address, to))
      if (leveldb.height > 0 && from < this.height) bs +: leveldb.balanceSnapshots(address, from, to) else Seq(bs)
    }
  }

  override def accountScript(address: Address): Option[(Script, Long)] = readLock {
    ngState.fold(leveldb.accountScript(address)) { ng =>
      ng.bestLiquidDiff.scripts.get(address) match {
        case None      => leveldb.accountScript(address)
        case Some(scr) => scr
      }
    }
  }

  override def hasAccountScript(address: Address): Boolean = readLock {
    ngState
      .flatMap(
        _.bestLiquidDiff.scripts
          .get(address)
          .map(_.nonEmpty)
      )
      .getOrElse(leveldb.hasAccountScript(address))
  }

  override def assetScript(asset: IssuedAsset): Option[(Script, Long)] = readLock {
    ngState.fold(leveldb.assetScript(asset)) { ng =>
      ng.bestLiquidDiff.assetScripts.get(asset) match {
        case None      => leveldb.assetScript(asset)
        case Some(scr) => scr
      }
    }
  }

  override def accountData(acc: Address, key: String): Option[DataEntry[_]] = readLock {
    ngState.fold(leveldb.accountData(acc, key)) { ng =>
      val diffData = ng.bestLiquidDiff.accountData.get(acc).orEmpty
      diffData.data.get(key).orElse(leveldb.accountData(acc, key))
    }
  }

  def collectActiveLeases(filter: LeaseTransaction => Boolean): Seq[LeaseTransaction] =
    CompositeBlockchain.collectActiveLeases(leveldb, ngState.map(_.bestLiquidDiff))(filter)

  /** Builds a new portfolio map by applying a partial function to all portfolios on which the function is defined.
    *
    * @note Portfolios passed to `pf` only contain Waves and Leasing balances to improve performance */
  override def collectLposPortfolios[A](pf: PartialFunction[(Address, Portfolio), A]): Map[Address, A] = readLock {
    ngState.fold(leveldb.collectLposPortfolios(pf)) { ng =>
      val b = Map.newBuilder[Address, A]
      for ((a, p) <- ng.bestLiquidDiff.portfolios if p.lease != LeaseBalance.empty || p.balance != 0) {
        pf.runWith(b += a -> _)(a -> this.wavesPortfolio(a))
      }

      leveldb.collectLposPortfolios(pf) ++ b.result()
    }
  }

  override def transactionHeight(id: ByteStr): Option[Int] = readLock {
    ngState flatMap { ng =>
      ng.bestLiquidDiff.transactionMap().get(id).map(_ => this.height)
    } orElse leveldb.transactionHeight(id)
  }

  override def balance(address: Address, mayBeAssetId: Asset): Long = readLock {
    ngState match {
      case Some(ng) =>
        leveldb.balance(address, mayBeAssetId) + ng.bestLiquidDiff.portfolios.getOrElse(address, Portfolio.empty).balanceOf(mayBeAssetId)
      case None =>
        leveldb.balance(address, mayBeAssetId)
    }
  }

  override def leaseBalance(address: Address): LeaseBalance = readLock {
    ngState match {
      case Some(ng) =>
        cats.Monoid.combine(leveldb.leaseBalance(address), ng.bestLiquidDiff.portfolios.getOrElse(address, Portfolio.empty).lease)
      case None =>
        leveldb.leaseBalance(address)
    }
  }

  override def hitSource(height: Int): Option[ByteStr] = readLock {
    ngState match {
      case Some(ng) if this.height == height => ng.hitSource.some
      case _                                 => leveldb.hitSource(height)
    }
  }

  private[this] object metrics {
    val blockMicroForkStats       = Kamon.counter("blockchain-updater.block-micro-fork")
    val microMicroForkStats       = Kamon.counter("blockchain-updater.micro-micro-fork")
    val microBlockForkStats       = Kamon.counter("blockchain-updater.micro-block-fork")
    val microBlockForkHeightStats = Kamon.histogram("blockchain-updater.micro-block-fork-height")
    val forgeBlockTimeStats       = Kamon.timer("blockchain-updater.forge-block-time")
  }
}

object BlockchainUpdaterImpl {
  private def diff(p1: Map[Address, Portfolio], p2: Map[Address, Portfolio]) = Monoid.combine(p1, p2.map { case (k, v) => k -> v.negate })

  private def displayFeatures(s: Set[Short]): String =
    s"FEATURE${if (s.size > 1) "S" else ""} ${s.mkString(", ")} ${if (s.size > 1) "have been" else "has been"}"

  def areVersionsOfSameBlock(b1: Block, b2: Block): Boolean =
    b1.header.generator == b2.header.generator &&
      b1.header.baseTarget == b2.header.baseTarget &&
      b1.header.reference == b2.header.reference &&
      b1.header.timestamp == b2.header.timestamp
}
