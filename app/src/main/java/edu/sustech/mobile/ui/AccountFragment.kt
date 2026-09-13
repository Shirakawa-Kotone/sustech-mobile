package edu.sustech.mobile.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import edu.sustech.mobile.R
import edu.sustech.mobile.core.App
import edu.sustech.mobile.core.AppConfig
import edu.sustech.mobile.core.Credentials
import edu.sustech.mobile.core.friendly
import edu.sustech.mobile.core.runIo
import edu.sustech.mobile.sso.Session

/**
 * Account tab: the stored school account, per-service session state, and the
 * server override used when testing against a local mock.
 *
 * Sessions are per host, so "signed in" is answered per service — and each of
 * them re-authenticates on its own from the stored account.
 */
class AccountFragment : Fragment(R.layout.fragment_account), Refreshable {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val server = view.findViewById<TextInputEditText>(R.id.input_server)
        server.setText(AppConfig.baseUrl)
        view.findViewById<TextView>(R.id.account_server).text = AppConfig.baseUrl
        view.findViewById<MaterialButton>(R.id.btn_server_save).setOnClickListener {
            AppConfig.baseUrl = server.text?.toString().orEmpty()
            server.setText(AppConfig.baseUrl)
            view.findViewById<TextView>(R.id.account_server).text = AppConfig.baseUrl
            Toast.makeText(requireContext(), R.string.server_saved, Toast.LENGTH_SHORT).show()
            load()
        }

        view.findViewById<MaterialButton>(R.id.btn_forget).setOnClickListener {
            Credentials.clear()
            App.cookies.clear()
            Toast.makeText(requireContext(), R.string.account_forgotten, Toast.LENGTH_SHORT).show()
            startActivity(Intent(requireContext(), LoginActivity::class.java))
            requireActivity().finish()
        }

        load()
    }

    override fun refresh() = load()

    private fun load() {
        val account = view?.findViewById<TextView>(R.id.account_identity)
        val accountState = view?.findViewById<TextView>(R.id.account_identity_state)
        val printSession = view?.findViewById<TextView>(R.id.account_print_session)
        val tisSession = view?.findViewById<TextView>(R.id.account_tis_session)

        account?.text = if (Credentials.configured) Credentials.sid else getString(R.string.account_unknown)
        accountState?.setText(
            if (Credentials.configured) R.string.account_creds_saved else R.string.account_creds_missing,
        )

        runIo(
            block = { App.api.check() },
            onOk = { printSession?.setText(R.string.account_session_valid) },
            onErr = { error -> printSession?.text = error.friendly(requireContext()) },
        )
        runIo(
            block = { Session.ensureCourses() },
            onOk = { semester ->
                tisSession?.text = if (semester == null) {
                    getString(R.string.account_session_invalid)
                } else {
                    getString(
                        R.string.tis_semester,
                        semester.labelEn.ifEmpty { semester.label },
                        getString(R.string.account_session_valid),
                    )
                }
            },
            onErr = { tisSession?.setText(R.string.account_session_invalid) },
        )
    }
}
