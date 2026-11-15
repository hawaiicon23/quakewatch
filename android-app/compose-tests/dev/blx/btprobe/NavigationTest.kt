package dev.blx.btprobe

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import java.io.File
import java.io.FileOutputStream
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** UI integration checks: real activity routes, data entry and shared preference theming. */
class NavigationTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private var originalTheme:String?=null
    private var originalSolid=false
    private var originalReduce=false
    private var originalImageAdvertising=true
    @Before fun preserveAppearance(){
        originalImageAdvertising=MeshService.settings(compose.activity).getBoolean("image_advertising",true)
        val p=compose.activity.getSharedPreferences("appearance",Context.MODE_PRIVATE)
        originalTheme=p.getString("theme","system");originalSolid=p.getBoolean("solid",false);originalReduce=p.getBoolean("reduceMotion",false)
    }
    @After fun restoreAppearance(){
        MeshService.settings(compose.activity).edit().putBoolean("image_advertising",originalImageAdvertising).commit()
        compose.activity.getSharedPreferences("appearance",Context.MODE_PRIVATE).edit().putString("theme",originalTheme).putBoolean("solid",originalSolid).putBoolean("reduceMotion",originalReduce).commit()
    }
    @Test fun devicesSeparateAdvertisingFromDataConnections(){
        compose.onNodeWithText("Devices & links").performScrollTo().performClick()
        compose.onNode(hasText("Seen over advertising ·",substring=true)).assertIsDisplayed()
        compose.onNodeWithTag("device-connections-list").performScrollToNode(hasText("Active data links ·",substring=true))
        compose.onNode(hasText("Active data links ·",substring=true)).assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
    }
    @Test fun earthquakeAlertChoicesPersistWithoutDisablingSensing(){
        val p=MeshService.settings(compose.activity);val original=p.getString("quake_alert_mode","notification")
        try{
            compose.onNodeWithContentDescription("Settings").performClick()
            for((key,label) in listOf("off" to "Off","audio" to "Audio","notification" to "Notification")){
                compose.onNode(hasText(label) and isSelectable()).performScrollTo().performClick()
                assertEquals(key,p.getString("quake_alert_mode",null))
                compose.runOnUiThread{assertTrue(MeshService.get()!!.quake.enabled())}
            }
        }finally{p.edit().putString("quake_alert_mode",original).commit()}
    }
    @Test fun imageAdvertisingCanBeDisabledIndependently(){
        compose.onNodeWithContentDescription("Settings").performClick()
        val toggle=compose.onNode(hasText("Image broadcast fallback") and isToggleable())
        toggle.performScrollTo().performClick()
        assertEquals(!originalImageAdvertising,MeshService.settings(compose.activity).getBoolean("image_advertising",true))
        toggle.performClick()
        assertEquals(originalImageAdvertising,MeshService.settings(compose.activity).getBoolean("image_advertising",true))
    }
    @Test fun networkAndToolsPreserveUnsentDraft(){
        compose.onNodeWithText("Nearby, together.").assertIsDisplayed()
        compose.onNodeWithText("Tools",useUnmergedTree=true).performClick()
        compose.onNodeWithText("Test message").performScrollTo().performTextReplacement("DRAFT")
        closeSoftKeyboard()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Network",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Network",useUnmergedTree=true).performClick()
        compose.onNodeWithText("Tools",useUnmergedTree=true).performClick()
        compose.onNodeWithText("DRAFT").performScrollTo().assertIsDisplayed()
    }
    @Test fun voiceHasAccessibleDisabledTalkActionBeforeConnection(){
        compose.onNodeWithText("Walkie-talkie").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Hold to talk").performScrollTo().assertIsNotEnabled().assertHasClickAction()
        compose.onNodeWithText("Discover data links").performScrollTo().assertIsDisplayed()
    }
    @Test fun sensingRouteShowsIndependentVotes(){
        compose.onNodeWithText("Sensing",useUnmergedTree=true).performClick()
        compose.onNodeWithText("Feel. Compare. Understand.").assertIsDisplayed()
        compose.onNodeWithText("Accelerometer monitoring is always on while the background service runs, including during Bluetooth reconnects.").performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("Shake-test mode") and isToggleable()).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Shared node IDs").performScrollTo().assertIsDisplayed()
    }
    @Test fun chatKeepsKeyboardComposerAndAttachment(){
        compose.onNodeWithText("Chat",useUnmergedTree=true).performClick()
        compose.onNodeWithContentDescription("Message nearby phones").performTextInput("Unsent UI check")
        compose.onNodeWithText("Unsent UI check").assertIsDisplayed()
        compose.onNodeWithContentDescription("Attach an image").assertIsDisplayed()
        compose.onNodeWithContentDescription("Send message").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("conversation").assertIsDisplayed()
        closeSoftKeyboard()
        for(theme in listOf("light","dark")) {
            compose.runOnUiThread{compose.activity.getSharedPreferences("appearance",Context.MODE_PRIVATE).edit().putString("theme",theme).commit()}
            compose.waitForIdle()
            capture("chat-$theme")
        }
        compose.onNodeWithContentDescription("Chat options").performClick()
        compose.onNodeWithText("Nearby devices").performClick()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Unsent UI check").assertIsDisplayed()
    }
    private fun capture(name:String){
        compose.waitForIdle()
        val folder=File(compose.activity.filesDir,"design-verification").apply{check(isDirectory || mkdirs())}
        val scale=compose.activity.resources.configuration.fontScale
        FileOutputStream(File(folder,"$name-$scale.png")).use{compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test fun settingsAppearanceAndPowerDraftRemainUsable(){
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Light").performClick()
        compose.onNodeWithText("Make it yours.").assertIsDisplayed()
        capture("settings-light")
        compose.onNodeWithText("Dark").performClick()
        capture("settings-dark")
        compose.onNodeWithText("Custom request").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Bluetooth transmit power request in dBm").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress){it(20f)}
            .assertRangeInfoEquals(ProgressBarRangeInfo(20f,0f..20f,19))
    }
    @Test fun lightDarkAndReducedEffectsRemainNavigable(){
        val p=compose.activity.getSharedPreferences("appearance",Context.MODE_PRIVATE)
        for(theme in listOf("light","dark")){
            compose.runOnUiThread{p.edit().putString("theme",theme).putBoolean("solid",true).putBoolean("reduceMotion",true).commit()}
            compose.waitForIdle()
            compose.onNodeWithText("Tools",useUnmergedTree=true).performClick()
            compose.onNodeWithText("Your field kit.").assertIsDisplayed()
            compose.onNodeWithText("Network",useUnmergedTree=true).performClick()
            compose.onNodeWithText("Nearby, together.").assertIsDisplayed()
            capture("network-$theme")
        }
    }
}
