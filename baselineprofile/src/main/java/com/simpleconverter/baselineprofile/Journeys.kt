package com.simpleconverter.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

/** Paketname der Mess-Variante (siehe app/build.gradle.kts: eigener Name, damit die echte App unberührt bleibt). */
const val TARGET_PACKAGE = "com.simpleconverter.app.benchmark"

/** Was beim Start und beim ersten Bedienen gebraucht wird: Start, Einstellungen, zurück. */
fun MacrobenchmarkScope.typicalJourney() {
    pressHome()
    startActivityAndWait()
    device.findObject(By.desc(Pattern.compile("Settings|Einstellungen")))?.let { settings ->
        settings.click()
        device.wait(Until.hasObject(By.scrollable(true)), 3_000)
        device.pressBack()
        device.wait(Until.hasObject(By.desc(Pattern.compile("Settings|Einstellungen"))), 3_000)
    }
}
