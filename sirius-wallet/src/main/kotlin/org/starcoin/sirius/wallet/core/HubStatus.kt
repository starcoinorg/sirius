package org.starcoin.sirius.wallet.core

import com.alibaba.fastjson.JSON
import org.starcoin.proto.Starcoin
import org.starcoin.sirius.core.*
import org.starcoin.sirius.lang.toBigInteger
import org.starcoin.sirius.lang.toHEXString
import org.starcoin.sirius.protocol.ChainAccount
import org.starcoin.sirius.util.WithLogging
import java.lang.IllegalStateException
import java.math.BigInteger

class HubStatus<A : ChainAccount> {

    companion object : WithLogging()

    private var account :ClientAccount<A>

    private var eon:Eon

    var allotment: BigInteger = BigInteger.ZERO
        private set

    internal val eonStatuses = Array(3){EonStatus()}

    var blocksPerEon: Int = 0
        internal set

    @Volatile
    private var currentEonStatusIndex = 0

    private val lastIndex = -1

    private val withdrawalKey="withdrawal".toByteArray()

    private var depositingTransactions: MutableMap<Hash, ChainTransaction> = mutableMapOf()

    internal var withdrawalStatus: WithdrawalStatus? = null
    set(value){
        if(value!=null){
            ResourceManager.instance(account.name).dataStore.put(withdrawalKey,value.toProtobuf())
        }else{
            ResourceManager.instance(account.name).dataStore.delete(withdrawalKey)
        }
        field = value
    }

    internal var update:Update?=null
    set(value){
        value?.apply {
            val key="update-${value.eon}"
            ResourceManager.instance(account.name).updateDao.put(key,value)
            field = value
        }
    }

    internal constructor(eon: Eon,account: ClientAccount<A>) {
        val eonStatus = EonStatus(eon.id)
        this.eon=eon
        this.account= account

        this.eonStatuses[currentEonStatusIndex] = eonStatus
    }

    internal fun cancelWithDrawal() {
        //this.allotment += this.withdrawalStatus?.withdrawalAmount ?: BigInteger.ZERO
        this.withdrawalStatus = null
    }

    internal fun confirmDeposit(transaction: ChainTransaction) {
        //this.allotment+=transaction.amount
        this.eonStatuses[currentEonStatusIndex].addDeposit(transaction)
        this.depositingTransactions.remove(transaction.hash())
        ResourceManager.instance(account.name).dataStore.put("deposit-${eon.id}".toByteArray(),this.eonStatuses[currentEonStatusIndex].deposit.toByteArray())
    }

    internal fun addDepositTransaction(hash:Hash,transaction: ChainTransaction){
        this.depositingTransactions.put(hash,transaction)
    }

    internal fun addUpdate(update: Update) {
        this.eonStatuses[currentEonStatusIndex].updateHistory.add(update)
    }

    internal fun hasProcessed(update: Update):Boolean {
        return this.eonStatuses[currentEonStatusIndex].updateHistory.any {
            it.equals(update)
        }
    }

    internal fun currentUpdate(eon: Eon): Update {
        val updateList = this.eonStatuses[currentEonStatusIndex].updateHistory
        if (updateList.size == 0) {
            return Update(eon.id, 0, 0, 0)
        } else {
            val index = this.eonStatuses[currentEonStatusIndex].updateHistory.size - 1
            return updateList[index]
        }
    }

    internal fun addOffchainTransaction(transaction: OffchainTransaction) {
        this.eonStatuses[currentEonStatusIndex].transactionHistory.add(transaction)
        this.eonStatuses[currentEonStatusIndex].transactionMap.put(
            transaction.hash(), transaction
        )
        ResourceManager.instance(account.name).offchainTransactionDao.put(transaction.hash(),transaction)
        val key="offline-transaction-${eon.id}".toByteArray()
        var transactionIdsBytes=ResourceManager.instance(account.name).dataStore.get(key)
        val ids = mutableListOf<String>()
        if(transactionIdsBytes!=null){
            ids.addAll(JSON.parseArray(String(transactionIdsBytes),String::class.java))
        }
        ids.add(transaction.hash().toBytes().toHEXString())
        ResourceManager.instance(account.name).dataStore.put(key,JSON.toJSONBytes(ids))
    }

    internal fun hasProcessed(transaction: OffchainTransaction):Boolean{
        return this.eonStatuses[currentEonStatusIndex].transactionMap.get(transaction.hash())!=null
    }

    internal fun transactionPath(hash: Hash): MerklePath? {
        val merkleTree = MerkleTree(eonStatuses[getEonByIndex(lastIndex)].transactionHistory)
        return merkleTree.getMembershipProof(hash)
    }

    internal fun getTransactionByHash(hash: Hash): OffchainTransaction? {
        return eonStatuses[getEonByIndex(lastIndex)].transactionMap[hash]
    }

    internal fun lastEonProof(): AMTreeProof? {
        val eonStatus = eonStatuses[getEonByIndex(lastIndex)]
        return eonStatus?.treeProof
    }

    internal fun currentTransactions(): List<OffchainTransaction> {
        return eonStatuses[currentEonStatusIndex].transactionHistory
    }

    internal fun nextEon(eon: Eon, path: AMTreeProof) {
        val currentUpdate = this.currentUpdate(eon)
        this.allotment += this.eonStatuses[currentEonStatusIndex].deposit
        this.allotment += currentUpdate.receiveAmount
        this.allotment -= currentUpdate.sendAmount
        if (this.withdrawalStatus?.eon == eon.id - 2) {
            this.allotment -= this.withdrawalStatus?.withdrawalAmount ?: BigInteger.ZERO
            this.withdrawalStatus?.clientConfirm()
            this.withdrawalStatus = null
        }

        LOG.info("current update is $currentUpdate")
        LOG.info("allotment is $allotment")

        val maybe = currentEonStatusIndex + 1
        LOG.info("maybe is $maybe")
        if (maybe > 2) {
            currentEonStatusIndex = 0
        } else {
            currentEonStatusIndex = maybe
        }
        this.eonStatuses[currentEonStatusIndex] = EonStatus(eon.id)

        this.eonStatuses[currentEonStatusIndex].treeProof = path
        val key="proof-${this.eon.id}"
        ResourceManager.instance(account.name).aMTreeProofDao.put(key,path)
        ResourceManager.instance(account.name).dataStore.put("allot-${eon.id}".toByteArray(),this.allotment.toByteArray())

        this.eon=eon
    }

    internal fun newChallenge(update: Update):BalanceUpdateProof {
        val index= getEonByIndex(lastIndex)
        if (eonStatuses[index] != null && eonStatuses[index].treeProof != null) {
            return BalanceUpdateProof(eonStatuses[index].treeProof!!.path!!)
        } else {
            return BalanceUpdateProof(update)
        }
    }

    internal fun syncAllotment(accountInfo: Starcoin.HubAccount) {
        this.allotment += accountInfo.eonState.deposit.toByteArray().toBigInteger()
        this.allotment += accountInfo.eonState.allotment.toByteArray().toBigInteger()
    }

    @Synchronized
    internal fun getEonByIndex(i: Int): Int {
        if (i > 0 || i < -2) {
            return -3
        } else {
            val index = currentEonStatusIndex + i
            return if (index < 0) {
                index + 2
            } else {
                index
            }
        }
    }

    fun couldWithDrawal():Boolean {
        if (this.withdrawalStatus == null) return true else return false
    }

    internal fun getAvailableCoin(eon: Eon): BigInteger {
        var allotment = this.allotment
        allotment+=this.eonStatuses[currentEonStatusIndex].deposit
        if (this.withdrawalStatus != null) {
            allotment -= this.withdrawalStatus?.withdrawalAmount?:BigInteger.ZERO
        }
        allotment += this.currentUpdate(eon).receiveAmount
        allotment -= this.currentUpdate(eon).sendAmount
        return allotment
    }

    internal fun lastUpdate(eon: Eon):Update{
        val updateList = this.eonStatuses[getEonByIndex(lastIndex)].updateHistory
        if (updateList.size == 0) {
            throw IllegalStateException("can't find last eon update")
        } else {
            val index = updateList.size - 1
            return updateList[index]
        }

    }

    internal fun reloadData(eon: Int){
        for(i in 0..2){
            val update=ResourceManager.instance(account.name).updateDao.get("update-${eon-i}")
            val proof=ResourceManager.instance(account.name).aMTreeProofDao.get("proof-${eon-i}")
            val key="offline-transaction-${eon-i}".toByteArray()
            val transactionIdsBytes = ResourceManager.instance(account.name).dataStore.get(key)
            eonStatuses[i]=EonStatus(eon-i).apply {
                if(update!=null)
                    this.updateHistory.add(update!!)
                this.treeProof = proof
            }
            if(transactionIdsBytes!=null){
                val transactionIds=JSON.parseArray(String(transactionIdsBytes),String::class.java)
                val transactions = transactionIds.map{ResourceManager.instance(account.name).offchainTransactionDao.get(Hash.wrap(it))}

                for (transaction in transactions){
                    if(transaction==null){
                        continue
                    }
                    eonStatuses[i].transactionHistory.add(transaction!!)
                    eonStatuses[i].transactionMap.put(transaction.hash(),transaction!!)
                }
            }

            val depositBytes=ResourceManager.instance(account.name).dataStore.get("deposit-${eon-i}".toByteArray())
            if(depositBytes!=null){
                eonStatuses[i].deposit=depositBytes.toBigInteger()
            }
        }
        this.currentEonStatusIndex =0
        val allotBytes=ResourceManager.instance(account.name).dataStore.get("allot-${eon}".toByteArray())

        val withdrawalBytes=ResourceManager.instance(account.name).dataStore.get(withdrawalKey)
        if(withdrawalBytes!=null){
            this.withdrawalStatus=WithdrawalStatus.parseFromProtobuf(withdrawalBytes)
        }

        this.allotment=allotBytes?.toBigInteger()?: BigInteger.ZERO
    }
}
