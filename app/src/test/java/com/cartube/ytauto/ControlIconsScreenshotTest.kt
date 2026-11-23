package com.cartube.ytauto

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Ensures car skip raster previews exist after tools/export_car_skip_icons.py runs in CI. */
@RunWith(RobolectricTestRunner::class)
class ControlIconsScreenshotTest {

    @Test
    fun carSkipIconPreviewsExistOnDisk() {
        val artifacts = listOf(
            "/opt/cursor/artifacts/car-skip-previous.png",
            "/opt/cursor/artifacts/car-skip-next.png",
            "/opt/cursor/artifacts/car-action-strip-icons.png",
        )
        for (path in artifacts) {
            assertTrue("Missing preview $path — run tools/export_car_skip_icons.py", File(path).isFile)
        }
    }
}