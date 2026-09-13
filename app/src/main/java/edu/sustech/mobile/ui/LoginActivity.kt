package edu.sustech.mobile.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import edu.sustech.mobile.R
import edu.sustech.mobile.core.ApiException
import edu.sustech.mobile.core.App
import edu.sustech.mobile.core.Credentials
import edu.sustech.mobile.core.friendly
import edu.sustech.mobile.core.runIo
import edu.sustech.mobile.sso.Session

/**
 * The app's only sign-in screen, and the only time credentials are ever typed.
 *
 * One school account is stored and reused for every service (printing, TIS,
 * and whatever comes next); sessions are re-established silently from that
 * account afterwards, so this screen is a first-run stop, not a gate.
 *
 * There is deliberately no per-service sign-in and no browser sign-in: TIS
 * refuses mobile browser sign-ins, and a native client has no reason to show
 * someone else's login page.
 */
class LoginActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        App.init(applicationContext)
        setContentView(R.layout.activity_login)

        val inputSid = findViewById<TextInputEditText>(R.id.input_username)
        val inputPassword = findViewById<TextInputEditText>(R.id.input_password)
        val progress = findViewById<ProgressBar>(R.id.login_progress)
        val button = findViewById<MaterialButton>(R.id.btn_login)

        inputSid.setText(Credentials.sid)

        button.setOnClickListener {
            val sid = inputSid.text?.toString()?.trim().orEmpty()
            val password = inputPassword.text?.toString().orEmpty()
            if (sid.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, R.string.login_missing_fields, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Credentials.save(sid, password)
            signIn(progress, button, password)
        }

        // Already configured: sign in silently and never show this screen again.
        if (Credentials.configured) {
            signIn(progress, button, Credentials.password)
        }
    }

    private fun signIn(progress: ProgressBar, button: MaterialButton, password: String) {
        progress.visibility = View.VISIBLE
        button.isEnabled = false
        runIo(
            block = { Session.signIn() },
            onOk = { accepted ->
                progress.visibility = View.GONE
                button.isEnabled = true
                if (accepted) {
                    openMain()
                } else {
                    // The account was refused: keep the screen so it can be fixed.
                    Toast.makeText(this, R.string.login_rejected, Toast.LENGTH_LONG).show()
                }
            },
            onErr = { error ->
                progress.visibility = View.GONE
                button.isEnabled = true
                val message = if (error is ApiException && error.signInRequired) {
                    getString(R.string.login_rejected)
                } else {
                    error.friendly(this)
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            },
        )
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
