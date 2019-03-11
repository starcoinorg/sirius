package org.starcoin.sirius.contract

import kotlinx.serialization.Serializable
import org.apache.commons.lang3.RandomStringUtils
import org.apache.commons.lang3.RandomUtils
import org.ethereum.core.BlockSummary
import org.ethereum.crypto.ECKey
import org.ethereum.listener.EthereumListenerAdapter
import org.ethereum.util.blockchain.SolidityCallResult
import org.ethereum.util.blockchain.SolidityContract
import org.ethereum.util.blockchain.StandaloneBlockchain
import org.junit.Assert
import org.junit.Before
import org.starcoin.sirius.core.*
import org.starcoin.sirius.crypto.eth.EthCryptoKey
import org.starcoin.sirius.lang.hexToByteArray
import org.starcoin.sirius.protocol.ethereum.loadContractMetadata
import org.starcoin.sirius.serialization.BigIntegerSerializer
import org.starcoin.sirius.util.MockUtils
import org.starcoin.sirius.util.WithLogging
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicLong


@Serializable
data class Data(
    val boolean: Boolean,
    val int: Int,
    val string: String,
    val address: Address, @Serializable(with = BigIntegerSerializer::class) val bigInteger: BigInteger
) {
    companion object : WithLogging() {
        fun random(): Data {
            return random(RandomUtils.nextBoolean())
        }

        fun random(booleanValue: Boolean): Data {
            return Data(
                booleanValue,
                RandomUtils.nextInt(),
                RandomStringUtils.randomAlphabetic(RandomUtils.nextInt(10, 30)),
                Address.random(),
                MockUtils.nextBigInteger()
            )
        }
    }
}

data class ContractData(val sb: StandaloneBlockchain, val contract: SolidityContract, val owner: ECKey)

abstract class ContractTestBase(val contractPath: String, val contractName: String) {

    companion object : WithLogging()

    lateinit var sb: StandaloneBlockchain
    lateinit var contract: SolidityContract
    lateinit var callUser: EthCryptoKey
    val blockHeight: AtomicLong = AtomicLong(0)
    lateinit var tx: OffchainTransaction

    @Before
    fun setup() {
        printlnJVMArgs()
        val tmp = deployContract()
        sb = tmp.sb
        contract = tmp.contract
        callUser = EthCryptoKey(tmp.owner)
    }

    open fun getContractConstructArg():Any?{
        return null
    }

    @Suppress("INACCESSIBLE_TYPE")
    fun deployContract(): ContractData {
        val sb = StandaloneBlockchain().withAutoblock(true).withGasLimit(9000000).withGasPrice(2147483647)

        sb.addEthereumListener(object : EthereumListenerAdapter() {
            override fun onBlock(blockSummary: BlockSummary) {
                blockHeight.incrementAndGet()
                LOG.info("block length:$blockHeight")
            }
        })

        val contractMetadata = loadContractMetadata(contractPath)
        LOG.info("$contractPath abi ${contractMetadata.abi}")
        LOG.info("$contractPath bin ${contractMetadata.bin}")
        LOG.info("Contract bin size: ${contractMetadata.bin.hexToByteArray().size}")
        val arg = getContractConstructArg()
        val contract:StandaloneBlockchain.SolidityContractImpl
        if(contractPath.equals("solidity/SiriusService")) {
            val firstMetadata = loadContractMetadata("solidity/ChallengeService")
            val first = sb.submitNewContract(firstMetadata)
            contract = (arg?.let { sb.submitNewContract(contractMetadata, first.address, arg) } ?: sb.submitNewContract(
                contractMetadata
            )) as StandaloneBlockchain.SolidityContractImpl
        } else {
            contract = (arg?.let { sb.submitNewContract(contractMetadata, arg) } ?: sb.submitNewContract(
                contractMetadata
            )) as StandaloneBlockchain.SolidityContractImpl
        }
        val lastSummary = StandaloneBlockchain::class.java.getDeclaredField("lastSummary")
        lastSummary.setAccessible(true)
        val sum = lastSummary.get(sb) as BlockSummary
        sum.getReceipts().stream().forEach { receipt -> assert(receipt.isTxStatusOK) }

        return ContractData(sb, contract, sb.sender)
    }

    fun commitData(eon: Int, amount: Long, flag: Boolean) {
        val info = AMTreeInternalNodeInfo(Hash.random(), amount, Hash.random())
        val node = AMTreePathNode(info.hash(), PathDirection.ROOT, 0, amount)
        val root = HubRoot(node, eon)
        val data = root.toRLP()
        val callResult = contract.callFunction("commit", data)

        if (flag) {
            assert(callResult.returnValue as Boolean)
            verifyReturn(callResult)
        } else {
            LOG.warning(callResult.receipt.error)
            callResult.receipt.logInfoList.forEach { logInfo ->
                LOG.info("event:$logInfo")
            }
        }
    }

    fun commitRealData(eon: Int, up: Update,  allotment : Long,  amount: Long, flag: Boolean, txs:MutableList<OffchainTransaction>) : AMTree {
        val accounts = mutableListOf<HubAccount>()
        val realEon = eon + 1
        accounts.add(HubAccount(callUser.keyPair.public, up, allotment, amount, 0, txs))
        val tree = AMTree(realEon, accounts)
        val node = tree.root.toAMTreePathNode()

        val root = HubRoot(node, realEon)
        val data = root.toRLP()
        val callResult = contract.callFunction("commit", data)

        if (flag) {
            assert(callResult.returnValue as Boolean)
            verifyReturn(callResult)
        } else {
            LOG.warning(callResult.receipt.error)
            callResult.receipt.logInfoList.forEach { logInfo ->
                LOG.info("event:$logInfo")
            }
        }

        return tree
    }

    fun verifyReturn(callResult: SolidityCallResult) {
        LOG.warning(callResult.receipt.error)
        Assert.assertTrue(callResult.isSuccessful)
        callResult.receipt.logInfoList.forEach { logInfo ->
            LOG.info("event:$logInfo")
        }
    }
}
