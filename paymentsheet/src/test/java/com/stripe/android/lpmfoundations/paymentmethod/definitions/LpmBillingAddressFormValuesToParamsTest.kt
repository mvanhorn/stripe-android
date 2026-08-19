package com.stripe.android.lpmfoundations.paymentmethod.definitions

import com.google.common.truth.Truth.assertThat
import com.google.testing.junit.testparameterinjector.TestParameter
import com.stripe.android.lpmfoundations.paymentmethod.PaymentMethodMetadata
import com.stripe.android.lpmfoundations.paymentmethod.TestUiDefinitionFactoryArgumentsFactory
import com.stripe.android.lpmfoundations.paymentmethod.UiDefinitionFactory
import com.stripe.android.model.PaymentMethod
import com.stripe.android.paymentsheet.PaymentSheet
import com.stripe.android.paymentsheet.forms.FormArgumentsFactory
import com.stripe.android.paymentsheet.forms.FormViewModel
import com.stripe.android.paymentsheet.model.PaymentSelection
import com.stripe.android.paymentsheet.ui.transformToPaymentSelection
import com.stripe.android.paymentsheet.utils.ViewModelStoreTestRule
import com.stripe.android.ui.core.elements.BsbElement
import com.stripe.android.uicore.elements.AddressFieldsElement
import com.stripe.android.uicore.elements.CheckboxFieldElement
import com.stripe.android.uicore.elements.IdentifierSpec
import com.stripe.android.uicore.elements.SectionElement
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestParameterInjector

@RunWith(RobolectricTestParameterInjector::class)
internal class LpmBillingAddressFormValuesToParamsTest {
    @get:Rule
    val viewModelStoreRule = ViewModelStoreTestRule()

    @Test
    fun `creates the expected form params`(
        @TestParameter(valuesProvider = LpmBillingAddressFormValuesToParamsTestCaseProvider::class)
        testCase: LpmBillingAddressFormValuesToParamsTestCase,
    ) = runTest {
        val actual = createFormParamsFromFormValues(
            config = testCase.config,
            rawValues = testCase.rawValues,
        )

        assertThat(actual).isEqualTo(testCase.expectedParams)
    }

    @Test
    fun `creates the expected flattened form params`(
        @TestParameter(valuesProvider = LpmBillingAddressTier1TestCaseProvider::class)
        testCase: LpmBillingAddressTier1TestCase,
    ) = runTest {
        val actual = createFormParamsFromFormValues(
            config = testCase.config,
            rawValues = testCase.rawValues,
            attachedDefaultBillingDetails = testCase.attachedDefaultBillingDetails,
        )

        assertThat(
            actual.createParams.toParamMap().flattenParams().withoutClientAttributionMetadata(),
        ).containsExactlyEntriesIn(testCase.expectedCreateParamsMap)
        assertThat(actual.createParams.requiresMandate).isEqualTo(testCase.expectedRequiresMandate)
        assertThat(actual.optionsParams).isEqualTo(testCase.expectedOptionsParams)
        assertThat(actual.extraParams).isEqualTo(testCase.expectedExtraParams)
    }

    @Test
    fun `has unique configs`() {
        assertThat(lpmBillingAddressTestConfigurations).containsNoDuplicates()
    }

    @Test
    fun `covers every billing mode for every payment method`() {
        lpmBillingAddressTestConfigurations
            .groupBy { it.paymentMethodType }
            .values
            .forEach { configs ->
                assertThat(configs.map { it.billingDetailsCollectionMode })
                    .containsAtLeastElementsIn(LpmBillingDetailsCollectionMode.entries)
            }
    }

    private suspend fun createFormParamsFromFormValues(
        config: LpmBillingAddressTestConfiguration,
        rawValues: Map<IdentifierSpec, String?>,
    ): LpmBillingAddressFormParams {
        return createFormParamsFromFormValues(
            config = config,
            rawValues = rawValues,
            attachedDefaultBillingDetails = null,
        )
    }

    private suspend fun createFormParamsFromFormValues(
        config: LpmBillingAddressTestConfiguration,
        rawValues: Map<IdentifierSpec, String?>,
        attachedDefaultBillingDetails: PaymentSheet.BillingDetails?,
    ): LpmBillingAddressFormParams {
        val metadata = attachedDefaultBillingDetails?.let {
            config.metadataWithAttachedDefaultBillingDetails(it)
        } ?: config.metadata()
        val formViewModel = createFormViewModel(
            paymentMethodType = config.paymentMethodType,
            metadata = metadata,
            uiDefinitionFactoryArgumentsFactory = TestUiDefinitionFactoryArgumentsFactory.create(),
        )

        val sectionFields = formViewModel.elements
            .filterIsInstance<SectionElement>()
            .flatMap { it.fields }

        sectionFields.forEach { it.setRawValue(rawValues) }
        formViewModel.elements
            .filterIsInstance<BsbElement>()
            .forEach { element ->
                rawValues[element.identifier]?.let { element.controller.onValueChange(it) }
            }
        sectionFields
            .filterIsInstance<AddressFieldsElement>()
            .forEach { it.countryElement.setRawValue(rawValues) }
        formViewModel.elements
            .filterIsInstance<CheckboxFieldElement>()
            .forEach { element ->
                rawValues[element.identifier]?.let { element.controller.onValueChange(it.toBoolean()) }
            }

        return formViewModel.createFormParams(config.paymentMethodType, metadata)
    }

    private fun createFormViewModel(
        paymentMethodType: PaymentMethod.Type,
        metadata: PaymentMethodMetadata,
        uiDefinitionFactoryArgumentsFactory: UiDefinitionFactory.Arguments.Factory,
    ): FormViewModel {
        val formElements = requireNotNull(
            metadata.formElementsForCode(
                code = paymentMethodType.code,
                uiDefinitionFactoryArgumentsFactory = uiDefinitionFactoryArgumentsFactory,
            ),
        )

        return viewModelStoreRule.track(
            FormViewModel(
                formElements = formElements,
                formArguments = FormArgumentsFactory.create(
                    paymentMethodCode = paymentMethodType.code,
                    metadata = metadata,
                ),
            ),
        )
    }

    private suspend fun FormViewModel.createFormParams(
        paymentMethodType: PaymentMethod.Type,
        metadata: PaymentMethodMetadata,
    ): LpmBillingAddressFormParams {
        val supportedPaymentMethod = requireNotNull(
            metadata.supportedPaymentMethodForCode(paymentMethodType.code),
        )
        val paymentSelection = requireNotNull(completeFormValues.first())
            .transformToPaymentSelection(supportedPaymentMethod, metadata)

        require(paymentSelection is PaymentSelection.New)

        return LpmBillingAddressFormParams(
            createParams = paymentSelection.paymentMethodCreateParams,
            optionsParams = paymentSelection.paymentMethodOptionsParams,
            extraParams = paymentSelection.paymentMethodExtraParams,
        )
    }
}
