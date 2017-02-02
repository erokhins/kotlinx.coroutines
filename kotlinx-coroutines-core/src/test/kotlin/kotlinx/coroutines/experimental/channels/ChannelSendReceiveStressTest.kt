package kotlinx.coroutines.experimental.channels

import kotlinx.coroutines.experimental.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue


@RunWith(Parameterized::class)
class ChannelSendReceiveStressTest(
    val kind: TestChannelKind,
    val nSenders: Int,
    val nReceivers: Int
) {
    companion object {
        @Parameterized.Parameters(name = "{0}, nSenders={1}, nReceivers={2}")
        @JvmStatic
        fun params(): Collection<Array<Any>> =
                listOf(1, 2, 10).flatMap { nSenders ->
                    listOf(1, 6).flatMap { nReceivers ->
                        TestChannelKind.values().map { arrayOf<Any>(it, nSenders, nReceivers) }
                    }
                }
    }

    val nEvents = 1_000_000

    val channel = kind.create()
    val sendersCompleted = AtomicInteger()
    val receiversCompleted = AtomicInteger()
    val dupes = AtomicInteger()
    val received = ConcurrentHashMap<Int,Int>()
    val receivedBy = IntArray(nReceivers)

    @Test
    fun testSendReceiveStress() = runBlocking {
        val receivers = List(nReceivers) { receiverIndex ->
            // different event receivers use different code
            launch(CommonPool + CoroutineName("receiver$receiverIndex")) {
                when (receiverIndex % 3) {
                    0 -> doReceive(receiverIndex)
                    1 -> doReceiveOrNull(receiverIndex)
                    2 -> doIterator(receiverIndex)
                }
                receiversCompleted.incrementAndGet()
            }
        }
        val senders = List(nSenders) { senderIndex ->
            launch(CommonPool + CoroutineName("sender$senderIndex")) {
                for (i in senderIndex until nEvents step nSenders)
                    channel.send(i)
                sendersCompleted.incrementAndGet()
            }
        }
        senders.forEach { it.join() }
        channel.close()
        receivers.forEach { it.join() }
        println("Tested $kind with nSenders=$nSenders, nReceivers=$nReceivers")
        println("Completed successfully ${sendersCompleted.get()} sender coroutines")
        println("Completed successfully ${receiversCompleted.get()} receiver coroutines")
        println("              Received ${received.size} events")
        println("        Received dupes ${dupes.get()}")
        repeat(nReceivers) { receiveIndex ->
            println("        Received by #$receiveIndex ${receivedBy[receiveIndex]}")
        }
        assertEquals(nSenders, sendersCompleted.get())
        assertEquals(nReceivers, receiversCompleted.get())
        assertEquals(0, dupes.get())
        assertEquals(nEvents, received.size)
        repeat(nReceivers) { receiveIndex ->
            assertTrue(receivedBy[receiveIndex] > nEvents / nReceivers / 2, "Should be balanced")
        }
    }

    private fun doReceived(receiverIndex: Int, event: Int) {
        if (received.put(event, event) != null) {
            println("Duplicate event $event")
            dupes.incrementAndGet()
        }
        receivedBy[receiverIndex]++
    }

    private suspend fun doReceive(receiverIndex: Int) {
        while (true) {
            try { doReceived(receiverIndex, channel.receive()) }
            catch (ex: ClosedReceiveChannelException) { break }
        }

    }

    private suspend fun doReceiveOrNull(receiverIndex: Int) {
        while (true) {
            doReceived(receiverIndex, channel.receiveOrNull() ?: break)
        }
    }

    private suspend fun doIterator(receiverIndex: Int) {
        for (event in channel) {
            doReceived(receiverIndex, event)
        }
    }
}