package com.ntt.crosstest.ui.screens.telegram

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ntt.crosstest.R
import com.ntt.crosstest.data.telegram.OtpDeliveryType
import com.ntt.crosstest.data.telegram.TelegramAuthUiState
import com.ntt.crosstest.data.telegram.TelegramCountryCodes

@Composable
fun MissingTelegramConfigContent(
    onOpenExternal: () -> Unit,
    modifier: Modifier = Modifier,
    onBackToPreview: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_telegram),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.telegram_missing_config_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.telegram_missing_config_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onOpenExternal,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.telegram_open_external_btn))
        }
        if (onBackToPreview != null) {
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onBackToPreview) {
                Text(stringResource(R.string.telegram_back_to_preview_btn))
            }
        }
    }
}

@Composable
fun TelegramAuthContent(
    authState: TelegramAuthUiState,
    onSubmitPhone: (countryCode: String, phoneInput: String) -> Unit,
    onChangePhone: () -> Unit,
    onSubmitOtp: (code: String) -> Unit,
    onResendOtp: () -> Unit,
    onSubmitPassword: (password: String) -> Unit,
    onRequestRecovery: () -> Unit,
    onBackToPassword: () -> Unit,
    onSubmitRecoveryCode: (code: String) -> Unit,
    onRegisterUser: (firstName: String, lastName: String) -> Unit,
    onRetry: () -> Unit,
    onOpenExternal: () -> Unit,
    modifier: Modifier = Modifier,
    onBackToPreview: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when (authState) {
            is TelegramAuthUiState.Initializing,
            is TelegramAuthUiState.ResolvingGroup -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                val label = (authState as? TelegramAuthUiState.ResolvingGroup)?.statusMessage
                    ?: stringResource(R.string.telegram_connecting_label)
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                ExternalTelegramFallbackButton(onOpenExternal)
            }

            is TelegramAuthUiState.WaitPhoneNumber -> {
                PhoneNumberStep(
                    state = authState,
                    onSubmitPhone = onSubmitPhone,
                    onOpenExternal = onOpenExternal,
                )
            }

            is TelegramAuthUiState.WaitCode -> {
                OtpVerificationStep(
                    state = authState,
                    onSubmitOtp = onSubmitOtp,
                    onResendOtp = onResendOtp,
                    onChangePhone = onChangePhone,
                    onOpenExternal = onOpenExternal,
                )
            }

            is TelegramAuthUiState.WaitPassword -> {
                PasswordStep(
                    state = authState,
                    onSubmitPassword = onSubmitPassword,
                    onRequestRecovery = onRequestRecovery,
                    onBackToPassword = onBackToPassword,
                    onSubmitRecoveryCode = onSubmitRecoveryCode,
                    onOpenExternal = onOpenExternal,
                )
            }

            is TelegramAuthUiState.WaitRegistration -> {
                RegistrationStep(
                    state = authState,
                    onRegisterUser = onRegisterUser,
                    onOpenExternal = onOpenExternal,
                )
            }

            is TelegramAuthUiState.JoinRequestSent -> {
                Icon(
                    painter = painterResource(R.drawable.ic_telegram),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(56.dp),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.telegram_join_request_sent_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.telegram_join_request_sent_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = onOpenExternal,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.telegram_open_external_btn))
                }
            }

            is TelegramAuthUiState.Error -> {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = authState.message,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.telegram_retry_btn))
                }
                Spacer(Modifier.height(12.dp))
                ExternalTelegramFallbackButton(onOpenExternal)
            }

            is TelegramAuthUiState.Ready -> Unit
        }
        if (onBackToPreview != null && authState !is TelegramAuthUiState.Ready) {
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onBackToPreview) {
                Text(stringResource(R.string.telegram_back_to_preview_btn))
            }
        }
    }
}

@Composable
private fun PhoneNumberStep(
    state: TelegramAuthUiState.WaitPhoneNumber,
    onSubmitPhone: (countryCode: String, phoneInput: String) -> Unit,
    onOpenExternal: () -> Unit,
) {
    var selectedDialCode by rememberSaveable { mutableStateOf(state.countryCode) }
    var phoneInput by rememberSaveable { mutableStateOf(state.phoneNumberInput) }
    var dropdownExpanded by remember { mutableStateOf(false) }

    Icon(
        painter = painterResource(R.drawable.ic_telegram),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(56.dp),
    )
    Spacer(Modifier.height(16.dp))
    Text(
        text = stringResource(R.string.telegram_auth_title),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.telegram_auth_subtitle),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(24.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box {
            val currentCountry = TelegramCountryCodes.ALL.firstOrNull { it.dialCode == selectedDialCode }
            OutlinedButton(
                onClick = { dropdownExpanded = true },
                enabled = !state.isLoading,
                modifier = Modifier.height(56.dp),
            ) {
                Text("${currentCountry?.flag ?: "🌐"} $selectedDialCode")
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(
                expanded = dropdownExpanded,
                onDismissRequest = { dropdownExpanded = false },
            ) {
                TelegramCountryCodes.ALL.forEach { item ->
                    DropdownMenuItem(
                        text = { Text("${item.flag} ${item.name} (${item.dialCode})") },
                        onClick = {
                            selectedDialCode = item.dialCode
                            dropdownExpanded = false
                        },
                    )
                }
            }
        }

        OutlinedTextField(
            value = phoneInput,
            onValueChange = { phoneInput = it },
            label = { Text(stringResource(R.string.telegram_phone_label)) },
            placeholder = { Text(stringResource(R.string.telegram_phone_hint)) },
            leadingIcon = { Icon(Icons.Filled.Phone, contentDescription = null) },
            singleLine = true,
            enabled = !state.isLoading,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Phone,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    if (!state.isLoading) onSubmitPhone(selectedDialCode, phoneInput)
                }
            ),
            modifier = Modifier.weight(1f),
        )
    }

    if (!state.error.isNullOrBlank()) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = state.error,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    Spacer(Modifier.height(16.dp))
    Button(
        onClick = { onSubmitPhone(selectedDialCode, phoneInput) },
        enabled = !state.isLoading && phoneInput.isNotBlank(),
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
    ) {
        if (state.isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            Text(stringResource(R.string.telegram_send_code_btn))
        }
    }

    Spacer(Modifier.height(20.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Text(
            text = stringResource(R.string.telegram_or_divider),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
    Spacer(Modifier.height(16.dp))
    ExternalTelegramFallbackButton(onOpenExternal)
}

@Composable
private fun OtpVerificationStep(
    state: TelegramAuthUiState.WaitCode,
    onSubmitOtp: (String) -> Unit,
    onResendOtp: () -> Unit,
    onChangePhone: () -> Unit,
    onOpenExternal: () -> Unit,
) {
    var codeInput by rememberSaveable(state.phoneNumber) { mutableStateOf("") }
    var lastAutoSubmitted by remember { mutableStateOf("") }

    LaunchedEffect(codeInput, state.codeLength) {
        if (codeInput.length == state.codeLength &&
            codeInput.all { it.isDigit() } &&
            codeInput != lastAutoSubmitted &&
            !state.isLoading
        ) {
            lastAutoSubmitted = codeInput
            onSubmitOtp(codeInput)
        }
    }

    Icon(
        imageVector = Icons.Filled.Sms,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(52.dp),
    )
    Spacer(Modifier.height(16.dp))
    Text(
        text = stringResource(R.string.telegram_otp_title),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(8.dp))

    val subtitle = when (state.codeType) {
        OtpDeliveryType.TELEGRAM_APP -> stringResource(
            R.string.telegram_otp_sent_app_desc,
            state.codeLength,
            state.phoneNumber,
        )
        OtpDeliveryType.SMS -> stringResource(R.string.telegram_otp_sent_sms_desc, state.phoneNumber)
        OtpDeliveryType.CALL, OtpDeliveryType.MISSED_CALL -> stringResource(
            R.string.telegram_otp_sent_call_desc,
            state.phoneNumber,
        )
        else -> stringResource(R.string.telegram_otp_sent_generic_desc, state.phoneNumber)
    }
    Text(
        text = subtitle,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(20.dp))

    OutlinedTextField(
        value = codeInput,
        onValueChange = { raw ->
            val digits = raw.filter { it.isDigit() }.take(state.codeLength)
            codeInput = digits
        },
        label = { Text(stringResource(R.string.telegram_otp_label)) },
        singleLine = true,
        enabled = !state.isLoading,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(
            onDone = {
                if (!state.isLoading && codeInput.isNotBlank()) onSubmitOtp(codeInput)
            }
        ),
        modifier = Modifier.fillMaxWidth(),
    )

    if (!state.error.isNullOrBlank()) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = state.error,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    Spacer(Modifier.height(16.dp))
    Button(
        onClick = { onSubmitOtp(codeInput) },
        enabled = !state.isLoading && codeInput.isNotBlank(),
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
    ) {
        if (state.isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            Text(stringResource(R.string.telegram_verify_code_btn))
        }
    }

    Spacer(Modifier.height(12.dp))
    if (state.timeoutSeconds > 0) {
        Text(
            text = stringResource(R.string.telegram_resend_countdown_format, state.timeoutSeconds),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else if (state.nextType != null) {
        TextButton(
            onClick = {
                lastAutoSubmitted = ""
                onResendOtp()
            },
            enabled = !state.isLoading,
        ) {
            val resendText = if (state.nextType == OtpDeliveryType.SMS) {
                stringResource(R.string.telegram_resend_sms_btn)
            } else {
                stringResource(R.string.telegram_resend_btn)
            }
            Text(resendText)
        }
    }

    TextButton(
        onClick = onChangePhone,
        enabled = !state.isLoading,
    ) {
        Text(stringResource(R.string.telegram_change_phone_btn))
    }

    Spacer(Modifier.height(12.dp))
    ExternalTelegramFallbackButton(onOpenExternal)
}

@Composable
private fun PasswordStep(
    state: TelegramAuthUiState.WaitPassword,
    onSubmitPassword: (String) -> Unit,
    onRequestRecovery: () -> Unit,
    onBackToPassword: () -> Unit,
    onSubmitRecoveryCode: (String) -> Unit,
    onOpenExternal: () -> Unit,
) {
    var passwordInput by rememberSaveable { mutableStateOf("") }
    var recoveryCodeInput by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }

    Icon(
        imageVector = Icons.Filled.Lock,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(52.dp),
    )
    Spacer(Modifier.height(16.dp))
    Text(
        text = stringResource(R.string.telegram_2fa_title),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(8.dp))

    if (!state.isRecoveryMode) {
        Text(
            text = stringResource(R.string.telegram_2fa_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (state.passwordHint.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.telegram_2fa_hint_format, state.passwordHint),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = passwordInput,
            onValueChange = { passwordInput = it },
            label = { Text(stringResource(R.string.telegram_2fa_password_label)) },
            singleLine = true,
            enabled = !state.isLoading,
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = null,
                    )
                }
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    if (!state.isLoading && passwordInput.isNotEmpty()) onSubmitPassword(passwordInput)
                }
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        Text(
            text = stringResource(R.string.telegram_2fa_recovery_desc, state.recoveryEmailPattern),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = recoveryCodeInput,
            onValueChange = { recoveryCodeInput = it },
            label = { Text(stringResource(R.string.telegram_2fa_recovery_code_label)) },
            singleLine = true,
            enabled = !state.isLoading,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }

    if (!state.error.isNullOrBlank()) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = state.error,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    Spacer(Modifier.height(16.dp))
    Button(
        onClick = {
            if (state.isRecoveryMode) onSubmitRecoveryCode(recoveryCodeInput)
            else onSubmitPassword(passwordInput)
        },
        enabled = !state.isLoading && (if (state.isRecoveryMode) recoveryCodeInput.isNotBlank() else passwordInput.isNotEmpty()),
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
    ) {
        if (state.isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            Text(stringResource(R.string.telegram_2fa_submit_btn))
        }
    }

    if (!state.isRecoveryMode && state.hasRecoveryEmail) {
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onRequestRecovery, enabled = !state.isLoading) {
            Text(stringResource(R.string.telegram_2fa_forgot_btn))
        }
    } else if (state.isRecoveryMode) {
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onBackToPassword, enabled = !state.isLoading) {
            Text(stringResource(R.string.telegram_2fa_back_to_password_btn))
        }
    }

    Spacer(Modifier.height(12.dp))
    ExternalTelegramFallbackButton(onOpenExternal)
}

@Composable
private fun RegistrationStep(
    state: TelegramAuthUiState.WaitRegistration,
    onRegisterUser: (String, String) -> Unit,
    onOpenExternal: () -> Unit,
) {
    var firstName by rememberSaveable { mutableStateOf(state.suggestedFirstName) }
    var lastName by rememberSaveable { mutableStateOf(state.suggestedLastName) }

    Text(
        text = stringResource(R.string.telegram_reg_title),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.telegram_reg_desc),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(20.dp))

    OutlinedTextField(
        value = firstName,
        onValueChange = { firstName = it },
        label = { Text(stringResource(R.string.telegram_reg_first_name_label)) },
        singleLine = true,
        enabled = !state.isLoading,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = lastName,
        onValueChange = { lastName = it },
        label = { Text(stringResource(R.string.telegram_reg_last_name_label)) },
        singleLine = true,
        enabled = !state.isLoading,
        modifier = Modifier.fillMaxWidth(),
    )

    if (!state.error.isNullOrBlank()) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = state.error,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    Spacer(Modifier.height(16.dp))
    Button(
        onClick = { onRegisterUser(firstName, lastName) },
        enabled = !state.isLoading && firstName.isNotBlank(),
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
    ) {
        if (state.isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            Text(stringResource(R.string.telegram_reg_submit_btn))
        }
    }

    Spacer(Modifier.height(16.dp))
    ExternalTelegramFallbackButton(onOpenExternal)
}

@Composable
private fun ExternalTelegramFallbackButton(onOpenExternal: () -> Unit) {
    OutlinedButton(
        onClick = onOpenExternal,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.telegram_open_external_btn))
    }
}
