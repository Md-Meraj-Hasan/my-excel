package com.meraj.niceexcel

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * File-based client for the isolated :libreoffice service.
 *
 * Never call from the Android main thread. The workbook is transferred by
 * private file path, not by putting the full workbook bytes in a Binder Bundle.
 */
object LibreOfficeServiceClient {
    private const val TAG = "NiceExcel-LO-IPC"
    private const val SERVICE_TIMEOUT_SECONDS = 45L
    private const val CALC_TIMEOUT_MINUTES = 8L

    /**
     * Builds a temporary XLSX snapshot, sends it to Calc, and returns the
     * formula caches read from the Calc-produced XLSX.
     */
    fun recalculateSnapshot(
        context: Context,
        snapshot: Map<String, Map<String, String>>
    ): Map<String, String> {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "LibreOffice service calls must run off the Android UI thread"
        }
        if (snapshot.values.none { cells -> cells.values.any { it.trimStart().startsWith("=") } }) {
            return emptyMap()
        }

        val appContext = context.applicationContext
        val input = LibreOfficeCalcEngine.createSnapshotWorkbook(appContext, snapshot)
        val output = File.createTempFile("calc-output-", ".xlsx", input.parentFile).apply {
            check(delete()) { "Cannot prepare Calc output path: $absolutePath" }
        }

        try {
            recalculateFile(appContext, input, output)
            return LibreOfficeCalcEngine.readFormulaResults(output)
        } finally {
            input.delete()
            output.delete()
        }
    }

    /**
     * Recalculates an existing exported XLSX into a separate output file.
     * The caller owns both files and decides when to replace the destination.
     */
    fun recalculateFile(context: Context, input: File, output: File) {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "LibreOffice service calls must run off the Android UI thread"
        }
        require(input.isFile && input.length() > 0L) { "Input XLSX is missing or empty: $input" }
        require(input.canonicalFile != output.canonicalFile) { "Calc input and output must be different files" }

        val appContext = context.applicationContext
        val connected = CountDownLatch(1)
        val replied = CountDownLatch(1)
        val remote = AtomicReference<Messenger?>()
        val failure = AtomicReference<Throwable?>()
        val detail = AtomicReference<String?>()

        val replyMessenger = Messenger(Handler(Looper.getMainLooper()) { message ->
            try {
                val data = message.data
                detail.set(data.getString(LibreOfficeEngineService.KEY_DETAIL))
                if (!data.getBoolean(LibreOfficeEngineService.KEY_OK, false)) {
                    failure.set(
                        IllegalStateException(
                            detail.get() ?: "LibreOffice service returned an unknown error"
                        )
                    )
                }
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                replied.countDown()
            }
            true
        })

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder == null) {
                    failure.set(IllegalStateException("LibreOffice service returned an empty Binder"))
                } else {
                    remote.set(Messenger(binder))
                }
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                failure.compareAndSet(
                    null,
                    IllegalStateException("LibreOffice isolated process disconnected")
                )
                connected.countDown()
                replied.countDown()
            }

            override fun onBindingDied(name: ComponentName?) {
                failure.compareAndSet(
                    null,
                    IllegalStateException("LibreOffice service binding died")
                )
                connected.countDown()
                replied.countDown()
            }

            override fun onNullBinding(name: ComponentName?) {
                failure.compareAndSet(
                    null,
                    IllegalStateException("LibreOffice service returned a null binding")
                )
                connected.countDown()
                replied.countDown()
            }
        }

        var bound = false
        try {
            Log.i(TAG, "CALC_REQUEST_START inputBytes=${input.length()}")
            bound = appContext.bindService(
                Intent(appContext, LibreOfficeEngineService::class.java),
                connection,
                Context.BIND_AUTO_CREATE
            )
            check(bound) { "Unable to bind the isolated LibreOffice service" }

            check(connected.await(SERVICE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "Timed out waiting for LibreOffice service connection"
            }
            failure.get()?.let { throw it }
            val service = remote.get()
                ?: throw IllegalStateException("LibreOffice service connection is unavailable")

            val request = Message.obtain(null, LibreOfficeEngineService.MSG_RECALCULATE_XLSX)
            request.replyTo = replyMessenger
            request.data = Bundle().apply {
                putString(LibreOfficeEngineService.KEY_INPUT_PATH, input.absolutePath)
                putString(LibreOfficeEngineService.KEY_OUTPUT_PATH, output.absolutePath)
            }
            service.send(request)

            check(replied.await(CALC_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                "Timed out waiting for LibreOffice Calc calculation"
            }
            failure.get()?.let { throw it }
            check(output.isFile && output.length() > 0L) {
                "LibreOffice service reported success but produced no XLSX output"
            }
            Log.i(TAG, "CALC_REQUEST_SUCCESS outputBytes=${output.length()} detail=${detail.get()}")
        } finally {
            if (bound) runCatching { appContext.unbindService(connection) }
        }
    }
}
