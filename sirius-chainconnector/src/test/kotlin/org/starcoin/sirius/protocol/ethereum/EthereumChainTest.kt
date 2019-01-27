package org.starcoin.sirius.protocol.ethereum

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.starcoin.sirius.core.*
import org.starcoin.sirius.crypto.CryptoService
import org.starcoin.sirius.crypto.eth.EthCryptoKey
import org.starcoin.sirius.protocol.Chain
import org.starcoin.sirius.protocol.ChainAccount
import org.starcoin.sirius.protocol.ChainEvent
import org.starcoin.sirius.protocol.EthereumTransaction
import org.starcoin.sirius.util.WithLogging
import java.util.concurrent.atomic.AtomicLong
import kotlin.properties.Delegates


class EthereumChainTest : EthereumServer(true) {
    companion object : WithLogging()

    private var chain: Chain<ChainTransaction, Block<ChainTransaction>, ChainAccount> by Delegates.notNull()
    private val alice = EthereumAccount(CryptoService.generateCryptoKey())
    private var etherbase: EthereumAccount by Delegates.notNull()
    private var ethchain: EthereumChain by Delegates.notNull()

    @Before
    fun setUp() {
        this.ethStart()
        ethchain = EthereumChain()
        val etherbaseKey = etherbaseKey()
        etherbase = EthereumAccount(etherbaseKey, AtomicLong(ethchain.getNonce(etherbaseKey.address).longValueExact()))
        chain = ethchain as Chain<ChainTransaction, Block<ChainTransaction>, ChainAccount>
    }

    @After
    fun tearDown() {
        this.ethStop()
    }

    @Test
    fun testSubmitTransaction() {
        val transAmount = 100.toBigInteger()
        val tx = chain.newTransaction(etherbase, alice.address, transAmount)
        var receipt: Receipt?
        while ({
                receipt =
                        chain.getTransactionReceipts(listOf(chain.submitTransaction(etherbase, tx)))[0];receipt!!.status
            }()) {
            Thread.sleep(200)
        }
        Assert.assertEquals(transAmount, chain.getBalance(alice.address))
    }

    @Test
    fun testFindTransaction() {
        val transAmount = 100.toBigInteger()
        val tx = chain.newTransaction(etherbase, alice.address, transAmount) as EthereumTransaction
        tx.sign(etherbase.key as EthCryptoKey)
        tx.verify()
        LOG.info("tx from:${tx.from}")
        Assert.assertNotNull(tx.from)
        val tx1 = chain.newTransaction(etherbase, alice.address, transAmount) as EthereumTransaction
        tx1.verify()
        val hash = chain.submitTransaction(etherbase, tx1)
        val txFind = chain.findTransaction(hash)
        Assert.assertNotNull(txFind?.from)
    }

    @Test
    fun testGetBlock() {
        val block = chain.getBlock()
        Assert.assertNotNull(block)
    }

    @Test
    fun testWatchBlock() {
        val ch = chain.watchBlock()
        runBlocking {
            for (c in 5 downTo 0) {
                val block = ch.receive()
                LOG.info("block info: height:${block.height},hash: ${block.blockHash()}")
                Assert.assertNotNull(block.height)
                Assert.assertNotNull(block.blockHash())
            }
        }
    }

    @Test
    fun testWatchTransactions() {
        val ch = chain.watchTransactions {
            it.tx.to == alice.address && it.tx.from == etherbase.address
        }
        Thread.sleep(2000)
        val transAmount = 100.toBigInteger()
        GlobalScope.launch {
            for (i in 5 downTo 0) {
                val hash = chain.submitTransaction(
                    etherbase,
                    chain.newTransaction(etherbase, alice.address, transAmount)
                )
                LOG.info("tx hash is $hash")
            }
        }
        runBlocking {
            for (i in 5 downTo 0) {
                val txResult = ch.receive()
                LOG.info("tx recived ${txResult.tx.toString()}")
                Assert.assertEquals(transAmount, txResult.tx.amount)
                Assert.assertEquals(etherbase.address, txResult.tx.from)
                Assert.assertEquals(alice.address, txResult.tx.to)
            }
        }

    }

    fun testWatchEvents() {
        val contracAddress: Address = Address.DUMMY_ADDRESS
        val events = listOf(ChainEvent.MockTopic)
        chain.watchEvents(contracAddress, events)
    }
}