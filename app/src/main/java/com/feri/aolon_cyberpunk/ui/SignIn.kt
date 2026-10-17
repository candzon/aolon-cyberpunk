package com.feri.aolon_cyberpunk.ui

import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.feri.aolon_cyberpunk.R
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.launch

/** Opens the Google account picker and returns an ID token for Firebase. */
suspend fun requestGoogleIdToken(context: Context): String {
    val option = GetSignInWithGoogleOption.Builder(context.getString(R.string.default_web_client_id)).build()
    val result = CredentialManager.create(context).getCredential(context, GetCredentialRequest(listOf(option)))
    return GoogleIdTokenCredential.createFrom(result.credential.data).idToken
}

/** Without this, the next sign-in silently reuses the account that just signed out. */
suspend fun clearGoogleCredential(context: Context) {
    runCatching { CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest()) }
}

@Composable
fun SignInScreen(onToken: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text("Aolon", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Text(
            "Sign in with Google to keep your workouts, steps and watch settings in your Firebase database.",
            color = Neon.Muted,
            style = MaterialTheme.typography.bodyLarge,
        )
        error?.let { Text(it, color = Neon.Red, style = MaterialTheme.typography.bodyMedium) }
        PrimaryButton("Sign in with Google", {
            error = null
            scope.launch {
                runCatching { requestGoogleIdToken(context) }
                    .onSuccess(onToken)
                    .onFailure {
                        Log.w("AolonSignIn", "Google sign-in failed", it)
                        // Play services reports a signing-key mismatch as a cancellation with code 16.
                        error = when {
                            it.message?.contains("[16]") == true -> "Google rejected this app's signing key. Add its SHA-1 in Firebase project settings."
                            it is GetCredentialCancellationException -> null
                            else -> it.message ?: "Sign-in failed. Try again."
                        }
                    }
            }
        }, Modifier.fillMaxWidth())
    }
}
