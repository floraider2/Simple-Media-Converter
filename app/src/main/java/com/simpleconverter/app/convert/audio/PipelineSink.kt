package com.simpleconverter.app.convert.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Lässt [next] auf einem eigenen Thread laufen: Der Dekoder liefert weiter, während kodiert wird.
 *
 * Auf einem gemeinsamen Thread wechseln Warten auf den Dekoder und Rechnen im Encoder ständig ab;
 * der Prozessor taktet dabei nicht hoch und beides zusammen dauert doppelt so lange wie nacheinander.
 * Das PCM wird in einen Puffer aus einem kleinen Vorrat kopiert, damit nichts neu angelegt werden muss.
 */
class PipelineSink(private val next: PcmSink) : PcmSink {

    private sealed interface Task {
        class Start(val sampleRate: Int, val channels: Int) : Task
        class Data(val pcm: ByteBuffer) : Task
        data object Finish : Task
        data object Stop : Task
    }

    private val tasks = ArrayBlockingQueue<Task>(QUEUE)
    private val pool = LinkedBlockingQueue<ByteBuffer>()
    private val finished = CountDownLatch(1)
    @Volatile private var failure: Throwable? = null
    @Volatile private var stopped = false

    private val worker = Thread({
        try {
            while (true) {
                when (val task = tasks.take()) {
                    is Task.Start -> next.start(task.sampleRate, task.channels)
                    is Task.Data -> {
                        next.write(task.pcm)
                        pool.put(task.pcm)
                    }
                    Task.Finish -> {
                        next.finish()
                        break
                    }
                    Task.Stop -> break
                }
            }
        } catch (e: InterruptedException) {
            // abgebrochen
        } catch (e: Throwable) {
            failure = e
        } finally {
            finished.countDown()
        }
    }, "PipelineSink")

    override fun start(sampleRate: Int, channels: Int) {
        worker.start()
        submit(Task.Start(sampleRate, channels))
    }

    override fun write(pcm: ByteBuffer) {
        val size = pcm.remaining()
        val copy = generateSequence { pool.poll() }.firstOrNull { it.capacity() >= size }
            ?: ByteBuffer.allocateDirect(size).order(ByteOrder.LITTLE_ENDIAN)
        copy.clear()
        copy.put(pcm)
        copy.flip()
        submit(Task.Data(copy))
    }

    override fun finish() {
        submit(Task.Finish)
        while (!finished.await(100, TimeUnit.MILLISECONDS)) {
            if (!worker.isAlive) break
        }
        failure?.let { throw it }
    }

    override fun release() {
        if (!stopped) {
            stopped = true
            if (worker.isAlive) {
                // Bei Abbruch: Warteschlange leeren, damit der Thread das Stopp-Signal sofort sieht.
                tasks.clear()
                tasks.offer(Task.Stop)
                worker.join(STOP_WAIT_MS)
                if (worker.isAlive) {
                    worker.interrupt()
                    worker.join(STOP_WAIT_MS)
                }
            }
        }
        next.release()
    }

    /** Reiht ein; wartet, solange die Schlange voll ist, und meldet Fehler des Threads sofort. */
    private fun submit(task: Task) {
        while (true) {
            failure?.let { throw it }
            if (finished.count == 0L) throw IllegalStateException("Ziel ist schon beendet")
            if (tasks.offer(task, 100, TimeUnit.MILLISECONDS)) return
        }
    }

    private companion object {
        /** Gepufferte Blöcke: genug, um Schwankungen auszugleichen, ohne viel Speicher zu binden. */
        const val QUEUE = 8
        const val STOP_WAIT_MS = 5_000L
    }
}
