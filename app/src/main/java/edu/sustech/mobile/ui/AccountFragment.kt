package edu.sustech.mobile.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import edu.sustech.mobile.R
import edu.sustech.mobile.core.App
import edu.sustech.mobile.core.AppConfig
import edu.sustech.mobile.core.friendly
import edu.sustech.mobile.core.runIo

/** 我的 — who is signed in, which server is used, and the sign-out button. */
class AccountFragment : androidx.fragment.app.Fragment(R.layout.fragment_account), Refreshable {

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
        val session = view?.findViewById<TextView>(R.id.account_session)
        val raw = view?.findViewById<TextView>(R.id.account_raw)
        session?.setText(R.string.account_session_invalid)
        runIo(
            block = { App.api.check() },
            onOk = { account ->
                name?.text = account.trueName.ifEmpty { getString(R.string.account_unknown) }
                session?.setText(R.string.account_session_valid)
                raw?.text = account.raw.toString()
            },
            onErr = { error ->
                name?.text = getString(R.string.account_unknown)
                session?.setText(R.string.account_session_invalid)
                raw?.text = error.friendly(requireContext())
            },
        )
    }
}
