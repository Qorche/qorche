package io.qorche.cli

import com.github.ajalt.clikt.core.main

/** Entry point for the Qorche CLI. Delegates to [QorcheCommand] for argument parsing and dispatch. */
fun main(args: Array<String>) {
    QorcheCommand().main(args)
}
