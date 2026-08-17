package com.example.tpms.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.example.tpms.TpmsManager
import com.example.tpms.TpmsState
import com.example.tpms.ui.TpmsDashboard
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** UI tests for [TpmsDashboard]. */
class MainScreenTest {

  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  @Before
  fun setup() {
    composeTestRule.setContent {
      TpmsDashboard(
        state = TpmsState(),
        onPairingClick = {},
        onSettingsClick = {}
      )
    }
  }

  @Test
  fun dashboard_showsTitle() {
    composeTestRule.onNodeWithText("🚗  Vehicle TPMS").assertExists()
  }

  @Test
  fun dashboard_showsButtons() {
    composeTestRule.onNodeWithText("ID Study 🔔").assertExists()
    composeTestRule.onNodeWithText("Settings ⚙️").assertExists()
  }
}

