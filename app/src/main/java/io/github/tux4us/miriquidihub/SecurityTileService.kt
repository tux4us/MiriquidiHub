package io.github.tux4us.miriquidihub

/** Schnellkachel: öffnet direkt SecurityCheckActivity. */
class SecurityTileService : LaunchTileService() {
    override val target: Class<*> = SecurityCheckActivity::class.java
}
