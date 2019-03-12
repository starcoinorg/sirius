package org.starcoin.sirius.hub

import io.grpc.Status
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.channels.consumeEach
import org.starcoin.sirius.channel.EventBus
import org.starcoin.sirius.core.*
import org.starcoin.sirius.datastore.DataStoreFactory
import org.starcoin.sirius.datastore.MapDataStoreFactory
import org.starcoin.sirius.datastore.delegate
import org.starcoin.sirius.protocol.*
import org.starcoin.sirius.util.WithLogging
import org.starcoin.sirius.util.error
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import kotlin.properties.Delegates

sealed class HubAction {
    data class IOUAction(val iou: IOU, val response: Channel<Exception?>) : HubAction()
    data class OffchainTransactionAction(
        val tx: OffchainTransaction,
        val fromUpdate: Update,
        val toUpdate: Update,
        val response: Channel<Exception?>
    ) : HubAction()

    data class BlockAction<T : ChainTransaction>(val block: Block<T>) : HubAction()
    //data class ChainTransactionAction<T : ChainTransaction>(val txResult: TransactionResult<T>) : HubAction()
}

enum class HubStatus {
    Prepare,
    Ready,
    Recovery
}

class HubImpl<A : ChainAccount>(
    private val owner: A,
    private val chain: Chain<out ChainTransaction, out Block<out ChainTransaction>, A>,
    private val contract: HubContract<A>,
    private val dataStoreFactory: DataStoreFactory = MapDataStoreFactory()
) : Hub {

    companion object : WithLogging()

    private lateinit var eonState: EonState

    private val syncStore = dataStoreFactory.getOrCreate("sync")

    private val eventBus = EventBus<HubEvent>()

    private val ready: Boolean
        get() = hubStatus == HubStatus.Ready

    private val recoveryMode: Boolean
        get() = hubStatus == HubStatus.Recovery

    private val txReceipts = ConcurrentHashMap<Hash, CompletableFuture<Receipt>>()

    private val strategy: MaliciousStrategy = MaliciousStrategy()

    private var blocksPerEon = 0

    private var startBlockNumber: Long by Delegates.notNull()

    private var processedBlockNumber: Long by syncStore.delegate(-1L)

    private val withdrawals = ConcurrentHashMap<Address, Withdrawal>()

    private var hubStatus = HubStatus.Prepare

    private var processBlockJob: Job by Delegates.notNull()

    val hubActor = GlobalScope.actor<HubAction>(capacity = 100, onCompletion = {
        LOG.info("HubActor completion, exception:$it")
    }) {
        consumeEach {
            when (it) {
                is HubAction.IOUAction -> {
                    try {
                        strategy.processSendNewTransaction(it.iou)
                        {
                            eonState.addIOU(it.iou)
                            fireEvent(
                                HubEvent(
                                    HubEventType.NEW_TX, it.iou.transaction, it.iou.transaction.to
                                )
                            )
                        }
                        it.response.send(null)
                    } catch (e: Exception) {
                        it.response.send(e)
                    }
                }
                is HubAction.OffchainTransactionAction -> {
                    try {
                        confirmOffchainTransaction(
                            it.tx, it.fromUpdate, it.toUpdate
                        )
                        it.response.send(null)
                    } catch (e: Exception) {
                        it.response.send(e)
                    }
                }
                is HubAction.BlockAction<*> -> {
                    processBlock(it.block)
                }
            }
        }
    }

    override val hubInfo: HubInfo
        get() {
            if (!this.ready) {
                return HubInfo(this.ready, this.recoveryMode, 0, this.blocksPerEon)
            }
            return HubInfo(
                ready,
                this.recoveryMode,
                eonState.eon,
                blocksPerEon,
                stateRoot.toAMTreePathNode(),
                owner.key.keyPair.public
            )
        }

    override val stateRoot: AMTreeNode
        get() = this.eonState.state.root

    override var hubMaliciousFlag: EnumSet<HubService.HubMaliciousFlag>
        get() = EnumSet.copyOf(this.maliciousFlags)
        set(flags) {
            this.maliciousFlags.addAll(flags)
        }

    private var maliciousFlags: EnumSet<HubService.HubMaliciousFlag> =
        EnumSet.noneOf(HubService.HubMaliciousFlag::class.java)

    private val gang: ParticipantGang by lazy {
        val gang = ParticipantGang.random()
        val update = Update(UpdateData(currentEon().id))
        update.sign(gang.privateKey)
        runBlocking {
            registerParticipant(gang.participant, update)
        }
        gang
    }

    override fun start() {
        LOG.info("ProcessedBlockNumber: $processedBlockNumber")
        val currentBlockNumber = chain.getBlockNumber()
        LOG.info("CurrentBlockNumber: $currentBlockNumber")
        val contractHubInfo = contract.queryHubInfo(owner)
        LOG.info("ContractHubInfo: $contractHubInfo")

        this.blocksPerEon = contractHubInfo.blocksPerEon
        this.startBlockNumber = contractHubInfo.startBlockNumber.longValueExact()

        if (processedBlockNumber < 0) {
            processedBlockNumber = startBlockNumber
        }

        val currentEon = Eon.calculateEon(startBlockNumber, processedBlockNumber, blocksPerEon)

        eonState = EonState(currentEon.id, this.dataStoreFactory)

        this.processBlockJob = GlobalScope.launch(start = CoroutineStart.LAZY) {
            val blockChannel = chain.watchBlock(startBlockNum = (currentBlockNumber + 1).toBigInteger())
            for (block in blockChannel) {
                hubActor.send(HubAction.BlockAction(block))
            }
        }
        val recoveryMode = contract.isRecoveryMode(owner)
        if (recoveryMode) {
            this.hubStatus = HubStatus.Recovery
            LOG.error("Hub in recovery mode.")
        } else {
            if (this.eonState.getAccountOrNull(owner.address) == null) {
                runBlocking {
                    LOG.info("Register Hub owner self account.")
                    val participant = Participant(owner.key.keyPair.public)
                    val initUpdate = Update().apply { sign(owner.key) }
                    doRegisterParticipant(participant, initUpdate)
                }
            }
            this.processBlockJob.start()
            //first commit create by contract construct.
            //if miss latest commit, should commit root first.
            if (contractHubInfo.latestEon < currentEon.id) {
                this.doCommit()
            } else {
                this.hubStatus = HubStatus.Ready
            }
        }
    }

    private fun getEonState(eon: Int): EonState? {
        var eonState = this.eonState
        if (eonState.eon < eon) {
            return null
        }
        if (eonState.eon == eon) {
            return eonState
        }
        while (eon <= eonState.eon) {
            if (eonState.eon == eon) {
                return eonState
            }
            eonState = eonState.previous ?: return null
        }
        return null
    }

    override suspend fun registerParticipant(participant: Participant, initUpdate: Update): Update {
        this.checkReady()
        return this.doRegisterParticipant(participant, initUpdate)
    }

    private suspend fun doRegisterParticipant(participant: Participant, initUpdate: Update): Update {
        require(initUpdate.verifySig(participant.publicKey))
        if (this.getHubAccount(participant.address) != null) {
            throw StatusRuntimeException(Status.ALREADY_EXISTS)
        }
        initUpdate.signHub(this.owner.key)
        val account = HubAccount(participant.publicKey, initUpdate, 0)
        this.eonState.addAccount(account)
        return initUpdate
    }

    override suspend fun deposit(participant: Address, amount: Long) {
        val account = this.eonState.getAccount(participant)
        account.addDeposit(amount)
        this.eonState.saveAccount(account)
    }

    override suspend fun getHubAccount(address: Address): HubAccount? {
        return this.getHubAccount(this.eonState.eon, address)
    }

    override suspend fun getHubAccount(eon: Int, address: Address): HubAccount? {
        return this.getEonState(eon)?.getAccountOrNull(address)
    }

    fun getHubAccount(predicate: (HubAccount) -> Boolean): HubAccount? {
        this.checkReady()
        return this.eonState.getAccountOrNull(predicate)
    }

    private suspend fun confirmOffchainTransaction(
        tx: OffchainTransaction, fromUpdate: Update, toUpdate: Update
    ) {
        LOG.info("confirmOffchainTransaction from:${tx.from} , to: ${tx.to}, tx:${tx.hash()}")
        val from = this.eonState.getAccount(tx.from)
        val to = this.eonState.getAccount(tx.to)
        strategy.processOffchainTransaction(tx)
        {
            from.confirmTransaction(tx, fromUpdate)
            to.confirmTransaction(tx, toUpdate)
        }

        fromUpdate.signHub(this.owner.key)
        toUpdate.signHub(this.owner.key)

        eonState.saveAccount(from)
        eonState.saveAccount(to)

        this.fireEvent(HubEvent(HubEventType.NEW_UPDATE, fromUpdate, from.address))
        this.fireEvent(HubEvent(HubEventType.NEW_UPDATE, toUpdate, to.address))
    }

    private suspend fun fireEvent(event: HubEvent) {
        LOG.info("fireEvent:$event")
        this.eventBus.send(event)
    }

    override suspend fun sendNewTransfer(iou: IOU) {
        val response = Channel<Exception?>()
        hubActor.send(HubAction.IOUAction(iou, response))
        val exception = response.receive()
        if (exception != null) {
            throw exception
        }
    }

    override suspend fun receiveNewTransfer(receiverIOU: IOU) {
        val senderIOU = this.eonState.getPendingSendTx(receiverIOU.transaction.from) ?: throw StatusRuntimeException(
            Status.NOT_FOUND
        )
        require(receiverIOU.transaction == senderIOU.transaction) { "Transaction has bean modified" }
        val response = Channel<Exception?>()
        hubActor.send(
            HubAction.OffchainTransactionAction(
                receiverIOU.transaction,
                senderIOU.update,
                receiverIOU.update,
                response
            )
        )
        val exception = response.receive()
        if (exception != null) {
            throw exception
        }
    }

    override suspend fun queryNewTransfer(address: Address): List<OffchainTransaction> {
        return eonState.getPendingReceiveTxs(address)
    }

    override suspend fun getProof(address: Address): AMTreeProof? {
        return this.getProof(this.eonState.eon, address)
    }

    override suspend fun getProof(eon: Int, address: Address): AMTreeProof? {
        this.checkReady()
        val eonState = this.getEonState(eon) ?: return null
        return eonState.state.getMembershipProofOrNull(address)
    }

    override fun currentEon(): Eon {
        return Eon(eonState.eon, eonState.currentEpoch)
    }

    private fun checkReady() {
        check(this.ready) { "Hub is not ready for service, please wait." }
    }

    override suspend fun watch(address: Address): ReceiveChannel<HubEvent> {
        return this.watch { it.isPublicEvent || it.address == address }
    }

    override suspend fun watch(predicate: (HubEvent) -> Boolean): ReceiveChannel<HubEvent> {
        return this.eventBus.subscribe(predicate)
    }

    private fun doCommit() {
        val hubRoot =
            HubRoot(this.eonState.state.root.toAMTreePathNode(), this.eonState.eon)
        LOG.info("doCommit ProcessedBlockNumber:$processedBlockNumber, root:$hubRoot")
        this.contract.commit(owner, hubRoot)
    }

    private fun processTransferDeliveryChallenge(challenge: TransferDeliveryChallenge) {
        val tx = challenge.tx

        val to = tx.to
        val currentAccount = this.eonState.getAccount(to)
        val accountProof = this.eonState.state.getMembershipProof(to)
        val previousAccount = this.eonState.previous?.getAccountOrNull(to)

        var txProof: MerklePath? = null
        val txs = previousAccount?.getTransactions() ?: emptyList()
        if (!txs.isEmpty()) {
            val merkleTree = MerkleTree(txs)
            txProof = merkleTree.getMembershipProof(tx.hash())
        }
        if (txProof != null) {
            val closeChallenge =
                CloseTransferDeliveryChallenge(accountProof, txProof, currentAccount.address, tx.hash())
            this.contract.closeTransferDeliveryChallenge(owner, closeChallenge)
        } else {
            LOG.warning("Can not find tx Proof with challenge:$challenge")
        }
    }

    private fun processBalanceUpdateChallenge(address: Address, challenge: BalanceUpdateProof) {
        val proofPath = this.eonState.state.getMembershipProofOrNull(address)
        if (proofPath == null) {
            LOG.error("Can not find proof by address $address to close challenge: $challenge")
            return
        }
        this.contract.closeBalanceUpdateChallenge(owner, CloseBalanceUpdateChallenge(address, proofPath))
    }

    private suspend fun processWithdrawal(from: Address, withdrawal: Withdrawal) {
        withdrawals[from] = withdrawal
        this.strategy.processWithdrawal(from, withdrawal)
        {
            val amount = withdrawal.amount
            val hubAccount = this.eonState.getAccount(from)
            this.withdrawals[from] = withdrawal
            if (!hubAccount.addWithdraw(amount)) {
                //signed update (e) or update (e − 1), τ (e − 1)
                //TODO path is nullable?
                val path = this.eonState.state.getMembershipProofOrNull(from)
                val cancelWithdrawal = CancelWithdrawal(from, hubAccount.update, path ?: AMTreeProof.DUMMY_PROOF)
                contract.cancelWithdrawal(owner, cancelWithdrawal)
            } else {
                eonState.saveAccount(hubAccount)
                val withdrawalStatus = WithdrawalStatus(WithdrawalStatusType.INIT, withdrawal)
                withdrawalStatus.pass()
                this.fireEvent(HubEvent(HubEventType.WITHDRAWAL, withdrawalStatus, from))
            }
        }

    }

    private suspend fun processDeposit(deposit: Deposit) {
        this.strategy.processDeposit(deposit)
        {
            val hubAccount = this.eonState.getAccount(deposit.address)
            hubAccount.addDeposit(deposit.amount)
            this.eonState.saveAccount(hubAccount)
            this.fireEvent(
                HubEvent(
                    HubEventType.NEW_DEPOSIT,
                    deposit,
                    deposit.address
                )
            )
        }
    }

    private suspend fun processBlock(block: Block<*>) {
        LOG.info("Hub processBlock:$block")

        val eon = Eon.calculateEon(this.startBlockNumber, block.height, this.blocksPerEon)
        var newEon = false
        this.eonState.setEpoch(eon.epoch)
        when (eon.id) {
            this.eonState.eon + 1 -> {
                val eonState = this.eonState.toNextEon()
                this.eonState = eonState
                newEon = true
            }
            this.eonState.eon -> {
                //continue
            }
            else -> {
                LOG.error("Unexpect block ${block.height}, current eon: ${this.eonState.eon}, new eon: ${eon.id}")
                return
            }
        }
        //TODO handle blockchain fork and unexpected exit.
        this.processedBlockNumber = block.height
        block.transactions.filter { it.tx.to == contract.contractAddress }.forEach {
            processTransaction(block, it)
        }

        if (newEon) {
            this.doCommit()
        }

    }

    private fun doRecovery() {
        LOG.warning("Hub entry RecoveryMode, stop process block.")
        this.hubStatus = HubStatus.Recovery
        this.processBlockJob.cancel()
    }

    private suspend fun processTransaction(block: Block<*>, txResult: TransactionResult<*>) {
        LOG.info("Hub process tx: ${txResult.tx.hash()}, ${block.height} result: ${txResult.receipt}")
        if (!txResult.receipt.status) {
            LOG.warning("tx ${txResult.tx.hash()} status is fail.")
        }
        if (txResult.receipt.recoveryMode) {
            LOG.info("tx:${txResult.tx.hash()} trigger recoveryMode.")
            this.doRecovery()
            return
        }
        val tx = txResult.tx
        val hash = tx.hash()
        txReceipts[hash]?.complete(txResult.receipt)
        val contractFunction = tx.contractFunction
        when (contractFunction) {
            null -> {
                if (!txResult.receipt.status) {
                    return
                }
                val deposit = Deposit(tx.from!!, tx.amount)
                LOG.info("Deposit:" + deposit.toJSON())
                val eon = Eon.calculateEon(startBlockNumber, processedBlockNumber, blocksPerEon)
                if (eon.id > this.currentEon().id) {
                    while (eon.id > currentEon().id) {
                        //TODO
                        LOG.info("Receive next new eon Deposit, so wait.")
                        delay(1000)
                    }
                    processDeposit(deposit)
                } else {
                    this.processDeposit(deposit)
                }
            }
            is CommitFunction -> {
                val hubRoot = contractFunction.decode(tx.data)!!
                if (txResult.receipt.status) {
                    this.hubStatus = HubStatus.Ready
                    this.fireEvent(
                        HubEvent(
                            HubEventType.NEW_HUB_ROOT,
                            hubRoot
                        )
                    )
                } else {
                    //TODO retry commit ?
                    LOG.warning("Commit hub root $hubRoot fail, hub entry recoveryMode.")
                    doRecovery()
                }
            }
            is InitiateWithdrawalFunction -> {
                if (!txResult.receipt.status) {
                    return
                }
                val input = contractFunction.decode(tx.data)
                    ?: fail { "$contractFunction decode tx:${txResult.tx} fail." }
                LOG.info("$contractFunction: $input")
                this.processWithdrawal(tx.from!!, input)
            }
            is OpenTransferDeliveryChallengeFunction -> {
                if (!txResult.receipt.status) {
                    return
                }
                val input = contractFunction.decode(tx.data)
                    ?: fail { "$contractFunction decode tx:${txResult.tx} fail." }
                LOG.info("$contractFunction: $input")
                this.processTransferDeliveryChallenge(input)
            }
            is OpenBalanceUpdateChallengeFunction -> {
                if (!txResult.receipt.status) {
                    return
                }
                val input = contractFunction.decode(tx.data)
                    ?: fail { "$contractFunction decode tx:${txResult.tx} fail." }
                LOG.info("$contractFunction: $input")
                this.processBalanceUpdateChallenge(tx.from!!, input)
            }
            is CancelWithdrawalFunction -> {
                if (!txResult.receipt.status) {
                    return
                }
                val input = contractFunction.decode(tx.data)
                    ?: fail { "$contractFunction decode tx:${txResult.tx} fail." }
                val withdrawal = this.withdrawals[input.address]!!
                LOG.info("CancelWithdrawal: ${input.address}, amount: ${withdrawal.amount}")
                val withdrawalStatus = WithdrawalStatus(WithdrawalStatusType.INIT, withdrawal)
                withdrawalStatus.cancel()
                this.fireEvent(
                    HubEvent(HubEventType.WITHDRAWAL, withdrawalStatus, input.address)
                )
            }
        }
    }

    override suspend fun resetHubMaliciousFlag(): EnumSet<HubService.HubMaliciousFlag> {
        val result = this.hubMaliciousFlag
        this.maliciousFlags = EnumSet.noneOf(HubService.HubMaliciousFlag::class.java)
        return result
    }


    private inner class MaliciousStrategy {

        suspend fun processDeposit(deposit: Deposit, normalAction: suspend () -> Unit) {
            // steal deposit to a hub gang Participant
            if (maliciousFlags.contains(HubService.HubMaliciousFlag.STEAL_DEPOSIT)) {
                LOG.info(
                    gang.participant.address.toString()
                            + " steal deposit from "
                            + deposit.address.toString()
                )
                val hubAccount =
                    eonState.getAccount(gang.participant.address)
                hubAccount.addDeposit(deposit.amount)
                eonState.saveAccount(hubAccount)
            } else {
                normalAction()
            }
        }

        suspend fun processWithdrawal(from: Address, withdrawal: Withdrawal, normalAction: suspend () -> Unit) {
            // steal withdrawal from a random user who has enough balance.
            if (maliciousFlags.contains(HubService.HubMaliciousFlag.STEAL_WITHDRAWAL)) {
                val hubAccount =
                    getHubAccount { account -> (account.address != from && account.balance >= withdrawal.amount) }
                if (hubAccount != null) {
                    hubAccount.addWithdraw(withdrawal.amount)
                    LOG.info(
                        (from.toString()
                                + " steal withdrawal from "
                                + hubAccount.address.toString())
                    )
                    eonState.saveAccount(hubAccount)
                } else {
                    normalAction()
                }
            } else {
                normalAction()
            }
        }

        suspend fun processOffchainTransaction(tx: OffchainTransaction, normalAction: suspend () -> Unit) {
            // steal transaction, not real update account's tx.
            if (maliciousFlags.contains(HubService.HubMaliciousFlag.STEAL_TRANSACTION)) {
                LOG.info("steal transaction:" + tx.toJSON())
                // do nothing
            } else {
                normalAction()
            }
        }

        suspend fun processSendNewTransaction(sendIOU: IOU, normalAction: suspend () -> Unit) {
            if (maliciousFlags.contains(HubService.HubMaliciousFlag.STEAL_TRANSACTION_IOU)) {
                LOG.info("steal transaction iou from:" + sendIOU.transaction.from)
                val from = eonState.getAccount(sendIOU.transaction.from)
                from.checkIOU(sendIOU)
                //Async do steal, not block user request.
                GlobalScope.launch {
                    val tx = OffchainTransaction(
                        sendIOU.transaction.eon,
                        sendIOU.transaction.from,
                        gang.participant.address,
                        sendIOU.transaction.amount
                    )

                    val to = eonState.getAccount(gang.participant.address)
                    val sendTxs = ArrayList(to.getTransactions())
                    sendTxs.add(tx)
                    val toUpdate = Update.newUpdate(
                        to.update.eon, to.update.version + 1, to.address, sendTxs
                    )
                    toUpdate.sign(gang.privateKey)

                    val fromUpdate = sendIOU.update

                    from.confirmTransaction(sendIOU.transaction, fromUpdate, true)
                    to.confirmTransaction(tx, toUpdate, true)

                    fromUpdate.signHub(owner.key)
                    toUpdate.signHub(owner.key)

                    eonState.saveAccount(from)
                    eonState.saveAccount(to)
                    // only notice from.
                    fireEvent(HubEvent(HubEventType.NEW_UPDATE, fromUpdate, from.address))
                }
            } else {
                normalAction()
            }
        }
    }

    override fun stop() {
        this.processBlockJob.cancel()
    }
}
