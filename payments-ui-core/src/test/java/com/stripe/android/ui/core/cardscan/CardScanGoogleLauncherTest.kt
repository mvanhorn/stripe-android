package com.stripe.android.ui.core.cardscan

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.Turbine
import com.google.android.gms.wallet.CreditCardExpirationDate
import com.google.android.gms.wallet.PaymentCardRecognitionResult
import com.google.common.truth.Truth.assertThat
import com.stripe.android.testing.CoroutineTestRule
import com.stripe.android.testing.createComposeCleanupRule
import com.stripe.android.ui.core.cardscan.CardScanGoogleLauncher.Companion.rememberCardScanGoogleLauncher
import com.stripe.android.utils.FakeActivityLauncher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CardScanGoogleLauncherTest {
    @get:Rule
    val coroutineTestRule = CoroutineTestRule()

    @get:Rule
    val composeTestRule = createComposeRule()

    @get:Rule
    val composeCleanupRule = createComposeCleanupRule()

    @Test
    fun `parseActivityResult with valid GPCR data returns Completed`() = runScenario {
        val mockResult = mock<PaymentCardRecognitionResult>()
        val mockExpirationDate = mock<CreditCardExpirationDate>()

        whenever(mockResult.pan).thenReturn("4242424242424242")
        whenever(mockResult.creditCardExpirationDate).thenReturn(mockExpirationDate)
        whenever(mockExpirationDate.month).thenReturn(12)
        whenever(mockExpirationDate.year).thenReturn(2042)

        mockStatic(PaymentCardRecognitionResult::class.java).use { mockedStatic ->
            mockedStatic.`when`<PaymentCardRecognitionResult> {
                PaymentCardRecognitionResult.getFromIntent(any())
            }.thenReturn(mockResult)

            val intent = Intent().putExtra(
                "com.google.android.gms.wallet.PaymentCardRecognitionResult",
                mockResult
            )
            val result = ActivityResult(Activity.RESULT_OK, intent)

            val scanResult = launcher.parseActivityResult(result)

            assertThat(scanResult).isInstanceOf(CardScanResult.Completed::class.java)
            val completedResult = scanResult as CardScanResult.Completed
            assertThat(completedResult.scannedCard.pan).isEqualTo("4242424242424242")
            assertThat(completedResult.scannedCard.expirationMonth).isEqualTo(12)
            assertThat(completedResult.scannedCard.expirationYear).isEqualTo(2042)

            assertThat(fakeEventsReporter.apiCheckSucceededCalls.awaitItem()).isNotNull()
            assertThat(fakeEventsReporter.scanSucceededCalls.awaitItem().implementation)
                .isEqualTo("google_pay")
        }
    }

    @Test
    fun `parseActivityResult with RESULT_CANCELED returns Canceled`() = runScenario {
        val result = ActivityResult(Activity.RESULT_CANCELED, null)

        val scanResult = launcher.parseActivityResult(result)

        assertThat(scanResult).isInstanceOf(CardScanResult.Canceled::class.java)

        assertThat(fakeEventsReporter.apiCheckSucceededCalls.awaitItem()).isNotNull()
        assertThat(fakeEventsReporter.scanCancelledCalls.awaitItem().implementation)
            .isEqualTo("google_pay")
    }

    @Test
    fun `parseActivityResult with RESULT_OK but null data returns Failed`() = runScenario {
        val result = ActivityResult(Activity.RESULT_OK, null)

        val scanResult = launcher.parseActivityResult(result)

        assertThat(scanResult).isInstanceOf(CardScanResult.Failed::class.java)
        val failedResult = scanResult as CardScanResult.Failed
        assertThat(failedResult.error).isInstanceOf(CardScanActivityResultException::class.java)

        assertThat(fakeEventsReporter.apiCheckSucceededCalls.awaitItem()).isNotNull()
        val scanFailedCall = fakeEventsReporter.scanFailedCalls.awaitItem()
        assertThat(scanFailedCall.implementation).isEqualTo("google_pay")
        assertThat(scanFailedCall.error).isInstanceOf(CardScanActivityResultException::class.java)
    }

    @Test
    fun `parseActivityResult with RESULT_OK and valid data but no PAN returns Failed`() = runScenario {
        // Create an intent without PaymentCardRecognitionResult data
        val intent = Intent().apply {
            // Don't put any PaymentCardRecognitionResult data
        }
        val result = ActivityResult(Activity.RESULT_OK, intent)

        val scanResult = launcher.parseActivityResult(result)

        assertThat(scanResult).isInstanceOf(CardScanResult.Failed::class.java)
        val failedResult = scanResult as CardScanResult.Failed
        assertThat(failedResult.error).isInstanceOf(CardScanParseException::class.java)

        assertThat(fakeEventsReporter.apiCheckSucceededCalls.awaitItem()).isNotNull()
        val scanFailedCall = fakeEventsReporter.scanFailedCalls.awaitItem()
        assertThat(scanFailedCall.implementation).isEqualTo("google_pay")
        assertThat(scanFailedCall.error).isInstanceOf(CardScanParseException::class.java)
    }

    @Test
    fun `parseActivityResult with custom result code returns Failed`() = runScenario {
        val result = ActivityResult(123, null) // Some other result code

        val scanResult = launcher.parseActivityResult(result)

        assertThat(scanResult).isInstanceOf(CardScanResult.Failed::class.java)
        val failedResult = scanResult as CardScanResult.Failed
        assertThat(failedResult.error).isInstanceOf(CardScanActivityResultException::class.java)

        assertThat(fakeEventsReporter.apiCheckSucceededCalls.awaitItem()).isNotNull()
        val scanFailedCall = fakeEventsReporter.scanFailedCalls.awaitItem()
        assertThat(scanFailedCall.implementation).isEqualTo("google_pay")
        assertThat(scanFailedCall.error).isInstanceOf(CardScanActivityResultException::class.java)
    }

    @Test
    fun `card scan launcher should be able to launch card scan activity`() = runScenario {
        assertThat(launcher.isAvailable.value).isTrue()

        launcher.launch(ApplicationProvider.getApplicationContext())
        assertThat(activityLauncher.launchCall.awaitItem()).isEqualTo(Unit)

        assertThat(fakeEventsReporter.apiCheckSucceededCalls.awaitItem()).isNotNull()
        assertThat(fakeEventsReporter.scanStartedCalls.awaitItem().implementation).isEqualTo("google_pay")
    }

    @Test
    fun `card scan launcher launches delayed intent when activity launcher is registered`() {
        val paymentCardRecognitionClient = DelayedPaymentCardRecognitionClient()

        runScenario(paymentCardRecognitionClient = paymentCardRecognitionClient) {
            launcher.launch(ApplicationProvider.getApplicationContext())
            paymentCardRecognitionClient.completeFetchIntent()

            assertThat(activityLauncher.launchCall.awaitItem()).isEqualTo(Unit)
            assertThat(fakeEventsReporter.apiCheckSucceededCalls.awaitItem()).isNotNull()
            assertThat(fakeEventsReporter.scanStartedCalls.awaitItem().implementation).isEqualTo("google_pay")
        }
    }

    @Test
    fun `card scan launcher drops delayed intent when activity launcher is unregistered`() {
        val paymentCardRecognitionClient = DelayedPaymentCardRecognitionClient()

        runScenario(paymentCardRecognitionClient = paymentCardRecognitionClient) {
            launcher.launch(ApplicationProvider.getApplicationContext())
            launcher.activityLauncher = null
            paymentCardRecognitionClient.completeFetchIntent()

            assertThat(fakeEventsReporter.apiCheckSucceededCalls.awaitItem()).isNotNull()
        }
    }

    @Test
    fun `card scan launcher drops delayed intent after leaving composition`() = runComposeScenario {
        composeTestRule.runOnIdle {
            launcher.launch(ApplicationProvider.getApplicationContext())
            isLauncherComposed.value = false
        }
        composeTestRule.waitForIdle()
        paymentCardRecognitionClient.completeFetchIntent()

        assertThat(fakeEventsReporter.apiCheckSucceededCalls.awaitItem()).isNotNull()
    }

    @Test
    fun `card scan launcher can relaunch after dropping delayed intent`() {
        val paymentCardRecognitionClient = DelayedPaymentCardRecognitionClient()

        runScenario(paymentCardRecognitionClient = paymentCardRecognitionClient) {
            launcher.launch(ApplicationProvider.getApplicationContext())
            launcher.activityLauncher = null
            paymentCardRecognitionClient.completeFetchIntent()

            launcher.activityLauncher = activityLauncher
            launcher.launch(ApplicationProvider.getApplicationContext())
            paymentCardRecognitionClient.completeFetchIntent()

            assertThat(activityLauncher.launchCall.awaitItem()).isEqualTo(Unit)
            assertThat(fakeEventsReporter.apiCheckSucceededCalls.awaitItem()).isNotNull()
            assertThat(fakeEventsReporter.scanStartedCalls.awaitItem().implementation).isEqualTo("google_pay")
        }
    }

    @Test
    fun `card scan launcher should not be available when fetchIntent fails`() = runScenario(
        paymentCardRecognitionClient = FakePaymentCardRecognitionClient(false)
    ) {
        assertThat(launcher.isAvailable.value).isFalse()
        launcher.launch(ApplicationProvider.getApplicationContext())

        val apiCheckFailedCall = fakeEventsReporter.apiCheckFailedCalls.awaitItem()
        assertThat(apiCheckFailedCall.error?.message).isEqualTo("Failed to fetch intent")

        val scanFailedCall = fakeEventsReporter.scanFailedCalls.awaitItem()
        assertThat(scanFailedCall.implementation).isEqualTo("google_pay")
        assertThat(scanFailedCall.error).isInstanceOf(Exception::class.java)
    }

    private class Scenario(
        val launcher: CardScanGoogleLauncher,
        val fakeEventsReporter: FakeCardScanEventsReporter,
        val activityLauncher: FakeActivityLauncher<IntentSenderRequest>,
    )

    private class ComposeScenario(
        val launcher: CardScanGoogleLauncher,
        val fakeEventsReporter: FakeCardScanEventsReporter,
        val paymentCardRecognitionClient: DelayedPaymentCardRecognitionClient,
        val isLauncherComposed: MutableState<Boolean>,
    )

    private fun runScenario(
        block: suspend Scenario.() -> Unit
    ) = runScenario(
        paymentCardRecognitionClient = FakePaymentCardRecognitionClient(true),
        block = block
    )

    private fun runScenario(
        paymentCardRecognitionClient: PaymentCardRecognitionClient,
        block: suspend Scenario.() -> Unit
    ) = runTest {
        val activityLauncher = FakeActivityLauncher<IntentSenderRequest>()
        val fakeEventsReporter = FakeCardScanEventsReporter()
        val launcher = CardScanGoogleLauncher(
            context = ApplicationProvider.getApplicationContext(),
            eventsReporter = fakeEventsReporter,
            options = null,
            paymentCardRecognitionClient = paymentCardRecognitionClient
        ).apply {
            this.activityLauncher = activityLauncher
        }

        val scenario = Scenario(
            launcher = launcher,
            fakeEventsReporter = fakeEventsReporter,
            activityLauncher = activityLauncher
        )

        scenario.block()

        activityLauncher.validate()
        fakeEventsReporter.validate()
    }

    private fun runComposeScenario(
        block: suspend ComposeScenario.() -> Unit
    ) = runTest {
        val paymentCardRecognitionClient = DelayedPaymentCardRecognitionClient()
        val fakeEventsReporter = FakeCardScanEventsReporter()
        val activityLaunchCalls = Turbine<Unit>()
        val isLauncherComposed = mutableStateOf(true)
        lateinit var launcher: CardScanGoogleLauncher
        val registryOwner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = object : ActivityResultRegistry() {
                override fun <I : Any?, O : Any?> onLaunch(
                    requestCode: Int,
                    contract: ActivityResultContract<I, O>,
                    input: I,
                    options: ActivityOptionsCompat?
                ) {
                    activityLaunchCalls.add(Unit)
                }
            }
        }

        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides registryOwner,
                LocalPaymentCardRecognitionClient provides paymentCardRecognitionClient
            ) {
                if (isLauncherComposed.value) {
                    launcher = rememberCardScanGoogleLauncher(
                        context = LocalContext.current,
                        eventsReporter = fakeEventsReporter,
                        onResult = {}
                    )
                }
            }
        }

        ComposeScenario(
            launcher = launcher,
            fakeEventsReporter = fakeEventsReporter,
            paymentCardRecognitionClient = paymentCardRecognitionClient,
            isLauncherComposed = isLauncherComposed,
        ).block()

        activityLaunchCalls.ensureAllEventsConsumed()
        fakeEventsReporter.validate()
    }

    private class DelayedPaymentCardRecognitionClient : PaymentCardRecognitionClient {
        private var fetchIntentCount = 0
        private var pendingCompletion: (() -> Unit)? = null

        override fun fetchIntent(
            context: Context,
            onFailure: (Throwable) -> Unit,
            onSuccess: (IntentSenderRequest) -> Unit
        ) {
            val request = IntentSenderRequest.Builder(
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(),
                    PendingIntent.FLAG_IMMUTABLE
                ).intentSender
            ).build()

            if (fetchIntentCount++ == 0) {
                onSuccess(request)
            } else {
                pendingCompletion = { onSuccess(request) }
            }
        }

        fun completeFetchIntent() {
            val completion = checkNotNull(pendingCompletion)
            pendingCompletion = null
            completion()
        }
    }
}
