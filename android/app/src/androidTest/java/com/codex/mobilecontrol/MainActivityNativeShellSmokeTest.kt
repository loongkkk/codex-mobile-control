package com.codex.mobilecontrol

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityNativeShellSmokeTest {
    @Test
    fun activityInflatesNativeShellIds() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull(activity.findViewById(R.id.loginCard))
                assertNotNull(activity.findViewById(R.id.threadDrawerRecycler))
                assertNotNull(activity.findViewById(R.id.messageInput))
            }
        }
    }
}
