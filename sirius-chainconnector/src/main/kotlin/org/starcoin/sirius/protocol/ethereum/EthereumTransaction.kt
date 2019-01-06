package org.starcoin.sirius.protocol


import org.ethereum.core.Transaction
import org.ethereum.util.ByteUtil
import org.starcoin.sirius.core.Address
import org.starcoin.sirius.core.ChainTransaction
import org.starcoin.sirius.core.Hash
import org.starcoin.sirius.lang.toULong
import java.math.BigInteger

// TODO use BigInteger to replace Long?
class EthereumTransaction(val ethTx: Transaction) : ChainTransaction(
    Address.wrap(ethTx.receiveAddress),
    BigInteger(1, ethTx.value)
) {
    override val from: Address?
        get() = when {
            ethTx.sender == null -> null
            else -> Address.wrap(ethTx.sender)
        }
    // Note: Generate nonce by EthereumChain.getNonce
    val nonce: Long?
        get() = ethTx.nonce.toULong()
    val gasPrice: Long
        get() = ethTx.gasPrice.toULong()
    val gasLimit: Long
        get() = ethTx.gasLimit.toULong()
    val data: ByteArray?
        get() = ethTx.data

    constructor(
        to: Address?,
        nonce: Long?,
        gasPrice: Long?,
        gasLimit: Long?,
        value: Long?,
        data: ByteArray?
    ) : this(
        Transaction(
            nonce?.let { ByteUtil.longToBytesNoLeadZeroes(it) },
            gasPrice?.let { ByteUtil.longToBytesNoLeadZeroes(it) },
            gasLimit?.let { ByteUtil.longToBytesNoLeadZeroes(it) },
            to?.toBytes(),
            value?.let { ByteUtil.longToBytesNoLeadZeroes(it) },
            data
        )
    )
    override fun txHash(): Hash {
        return Hash.wrap(this.ethTx.hash)
    }
}
