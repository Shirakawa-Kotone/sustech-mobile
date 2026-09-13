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
import edu.sustech.mobile.core.friendly
import edu.sustech.mobile.core.runIo

/**
 * Account tab: per-service session state plus the print server override.
 *
 * Sessions are per host, so "signed in" is answered per service — one service
 * being signed out says nothing about the others.
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
            Toast.makeText(requireContext(), R.string.login_server_saved, Toast.LENGTH_SHORT).show()
            load()
        }
        view.findViewById<MaterialButton>(R.id.btn_logout).setOnClickListener {
            App.cookies.clear()
            Toast.makeText(requireContext(), R.string.logout_done, Toast.LENGTH_SHORT).show()
            startActivity(Intent(requireContext(), LoginActivity::class.java))
            requireActivity().finish()
        }
        load()
    }

    override fun refresh() = load()

    private fun load() {
        val name = view?.findViewById<TextView>(R.id.account_name)
        val printSession = view?.findViewById<TextView>(R.id.account_print_session)
        val tisSession = view?.findViewById<TextView>(R.id.account_tis_session)

        runIo(
            block = { App.api.check() },
            onOk = { account ->
                name?.text = account.trueName.ifEmpty { getString(R.string.account_unknown) }
                printSession?.setText(R.string.account_session_valid)
            },
            onErr = { error ->
                name?.text = getString(R.string.account_unknown)
                printSession?.text = error.friendly(requireContext())
            },
        )
        runIo(
            block = { App.tis.currentSemester() },
            onOk = { semester ->
                tisSession?.text = getString(
                    R.string.tis_semester,
                    semester.labelEn.ifEmpty { semester.label },
                    getString(R.string.account_session_valid),
                )
            },
            onErr = { tisSession?.setText(R.string.account_session_invalid) },
        )
    }
}
