package io.github.tux4us.miriquidihub

/** Schnellkachel: öffnet direkt NetworkDiagnosticActivity. */
class NetworkTileService : LaunchTileService() {
    override val target: Class<*> = NetworkDiagnosticActivity::class.java
}
