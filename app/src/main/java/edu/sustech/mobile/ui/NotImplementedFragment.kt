package edu.sustech.mobile.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import edu.sustech.mobile.R
import edu.sustech.mobile.service.Services

/** Placeholder for a service that is on the roadmap but not built. */
class NotImplementedFragment : Fragment(R.layout.fragment_not_implemented) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val name = Services.byId(requireArguments().getString(ServiceActivity.EXTRA_SERVICE))?.title
        view.findViewById<TextView>(R.id.not_implemented_body).text =
            getString(R.string.not_implemented_body, if (name != null) getString(name) else "")
    }
}
