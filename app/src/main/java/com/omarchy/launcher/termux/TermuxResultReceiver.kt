package com.omarchy.launcher.termux

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Receives the result Bundle Termux's RunCommandService posts back after a background command. */
class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val bundle = intent.extras ?: return
        val stdout = bundle.getString(TermuxConstants.RESULT_STDOUT).orEmpty()
        val stderr = bundle.getString(TermuxConstants.RESULT_STDERR).orEmpty()
        val exitCode = bundle.getInt(TermuxConstants.RESULT_EXIT_CODE, -1)
        val errmsg = bundle.getString(TermuxConstants.RESULT_ERRMSG).orEmpty()

        if (stdout.isNotBlank()) TermuxOutputBus.appendBlock(stdout.trimEnd())
        if (stderr.isNotBlank()) TermuxOutputBus.appendBlock(stderr.trimEnd())
        if (errmsg.isNotBlank()) TermuxOutputBus.append("[error] $errmsg")
        TermuxOutputBus.append("[exit $exitCode]")
    }
}
