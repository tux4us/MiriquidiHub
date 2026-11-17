package io.github.tux4us.miriquidihub

/** Schnellkachel: öffnet direkt SimpleScreenshotActivity. */
class ScreenshotTileService : LaunchTileService() {
    override val target: Class<*> = SimpleScreenshotActivity::class.java
}
