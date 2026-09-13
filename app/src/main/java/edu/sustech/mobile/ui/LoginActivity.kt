package edu.sustech.mobile.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import edu.sustech.mobile.R
import edu.sustech.mobile.core.ApiException
import edu.sustech.mobile.core.App
import edu.sustech.mobile.core.AppConfig
import edu.sustech.mobile.core.friendly
import edu.sustech.mobile.core.runIo
import edu.sustech.mobile.pms.PmsAuth

/**
 * Sign-in for the print service, which is the one service with a second path:
 * besides the school sign-in page it also accepts its own RSA-encrypted
 * account login. Every other service uses [WebLoginActivity] directly.
 */
class LoginActivity : AppCompatActivity() {

    private val webLogin = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) openMain()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        App.init(applicationContext)
        setContentView(R.layout.activity_login)

        val inputUsername = findViewById<TextInputEditText>(R.id.input_username)
        val inputPassword = findViewById<TextInputEditText>(R.id.input_password)
        val inputServer = findViewById<TextInputEditText>(R.id.input_server)
        val progress = findViewById<ProgressBar>(R.id.login_progress)
        val advancedPanel = findViewById<View>(R.id.advanced_panel)

        inputUsername.setText(AppConfig.lastUsername)

        findViewById<MaterialButton>(R.id.btn_cas).setOnClickListener {
            webLogin.launch(
                Intent(this, WebLoginActivity::class.java)
                    .putExtra(WebLoginActivity.EXTRA_URL, AppConfig.baseUrl + PMS_PRINT_PAGE)
                    .putExtra(WebLoginActivity.EXTRA_COOKIE, PMS_COOKIE),
            )
        }

        findViewById<TextView>(R.id.advanced_toggle).setOnClickListener {
            advancedPanel.visibility = if (advancedPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        inputServer.setText(AppConfig.baseUrl)

        findViewById<MaterialButton>(R.id.btn_server_save).setOnClickListener {
            AppConfig.baseUrl = inputServer.text?.toString().orEmpty()
            inputServer.setText(AppConfig.baseUrl)
            Toast.makeText(this, R.string.login_server_saved, Toast.LENGTH_SHORT).show()
        }
        findViewById<MaterialButton>(R.id.btn_server_reset).setOnClickListener {
            AppConfig.resetBaseUrl()
            inputServer.setText(AppConfig.baseUrl)
        }

        findViewById<MaterialButton>(R.id.btn_login).setOnClickListener {
            val username = inputUsername.text?.toString()?.trim().orEmpty()
            val password = inputPassword.text?.toString().orEmpty()
            if (username.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, R.string.login_username, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            progress.visibility = View.VISIBLE
            runIo(
                block = { PmsAuth.login(username, password) },
                onOk = { name ->
                    progress.visibility = View.GONE
                    Toast.makeText(this, name, Toast.LENGTH_SHORT).show()
                    openMain()
                },
                onErr = { error ->
                    progress.visibility = View.GONE
                    Toast.makeText(this, error.friendly(this), Toast.LENGTH_LONG).show()
                },
            )
        }

        // A stored cookie means a previous session. Any service counts — being
        // signed in to TIS but not to printing should not trap the user here.
        if (!App.cookies.isEmpty()) {
            runIo(
                block = { App.api.check(); true },
                onOk = { openMain() },
                onErr = {
                    runIo(
                        block = { App.tis.currentSemester(); true },
                        onOk = { openMain() },
                        onErr = { if (it !is ApiException) App.toast(it.message.orEmpty()) },
                    )
                },
            )
        }
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private companion object {
        const val PMS_PRINT_PAGE = "/client/new/cprintPc/"
        const val PMS_COOKIE = "OSESSIONID"
    }
}
