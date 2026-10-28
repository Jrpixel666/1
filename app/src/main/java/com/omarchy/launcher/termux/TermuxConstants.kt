package com.omarchy.launcher.termux

/** Constants for Termux's documented RUN_COMMAND Intent API (com.termux.app.RunCommandService). */
object TermuxConstants {
    const val TERMUX_PACKAGE = "com.termux"
    const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
    const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
    const val RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"

    const val DEFAULT_BASH = "/data/data/com.termux/files/usr/bin/bash"
    const val DEFAULT_HOME = "/data/data/com.termux/files/home"

    const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
    const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    const val EXTRA_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION"
    const val EXTRA_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
    const val EXTRA_DESCRIPTION = "com.termux.RUN_COMMAND_COMMAND_DESCRIPTION"
    const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"

    // Keys present on the result Bundle delivered to EXTRA_PENDING_INTENT when running in background.
    const val RESULT_STDOUT = "stdout"
    const val RESULT_STDERR = "stderr"
    const val RESULT_EXIT_CODE = "exitCode"
    const val RESULT_ERRMSG = "errmsg"
}
