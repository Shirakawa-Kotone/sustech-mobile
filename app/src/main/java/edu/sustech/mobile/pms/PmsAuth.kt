package edu.sustech.mobile.pms

import android.util.Base64
import edu.sustech.mobile.core.App
import edu.sustech.mobile.core.AppConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

/**
 * PMS login.
 *
 * The 联创 site does not use SUSTech CAS for its API. It has its own flow:
 *
 *   1. POST /api/client/Auth/GetAuthToken  -> { szToken }
 *   2. GET  /api/client/Auth/PublicKey     -> { publicKey, nonceStr }
 *   3. RSA(PKCS#1 v1.5, base64) of `password + ";" + nonceStr`
 *   4. POST /api/client/Auth/Login         -> sets the OSESSIONID cookie
 *
 * The website is additionally CAS-fronted, which is why the app also offers
 * [edu.sustech.mobile.ui.CasLoginActivity]: sign in once in a WebView and the
 * PMS back end links the CAS identity to the print account. That path never
 * sees a password.
 */
object PmsAuth {

    /**
     * Posts the four-step password login and returns the account's display
     * name. Cookies land in [edu.sustech.mobile.core.CookieStore] because the
     * requests go through the shared OkHttp client.
     */
    fun login(username: String, password: String): String {
        val base = AppConfig.baseUrl

        val tokenBody = post(base, "/api/client/Auth/GetAuthToken", JSONObject())
        if (tokenBody.optInt("code", -1) != 0) {
            throw PmsException(tokenBody.optString("message", "GetAuthToken failed"))
        }
        val token = tokenBody.optString("szToken", "")
        if (token.isEmpty()) throw PmsException("GetAuthToken returned no szToken")

        val keyBody = get(base, "/api/client/Auth/PublicKey")
        if (keyBody.optInt("code", -1) != 0) {
            throw PmsException(keyBody.optString("message", "PublicKey failed"))
        }
        val keyResult = keyBody.optJSONObject("result") ?: JSONObject()
        val publicKey = keyResult.optString("publicKey", "")
        val nonce = keyResult.optString("nonceStr", "")
        if (publicKey.isEmpty()) throw PmsException("PublicKey returned no key")

        val loginBody = post(
            base,
            "/api/client/Auth/Login",
            JSONObject()
                .put("szLogonName", username)
                .put("szPassword", rsaEncrypt(publicKey, "$password;$nonce"))
                .put("szToken", token),
        )
        val code = loginBody.optInt("code", -1)
        if (code != 0) {
            throw PmsException("${loginBody.optString("message", "login failed")} (code=$code)")
        }
        AppConfig.lastUsername = username
        val result = loginBody.optJSONObject("result") ?: JSONObject()
        return result.optString("szTrueName", username)
    }

    // -- Transport ------------------------------------------------------------

    private fun post(base: String, path: String, payload: JSONObject): JSONObject {
        val request = Request.Builder()
            .url(base + path)
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", base + "/client/new/cprintPc/")
            .build()
        return send(request)
    }

    private fun get(base: String, path: String): JSONObject {
        val request = Request.Builder()
            .url(base + path)
            .get()
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", base + "/client/new/cprintPc/")
            .build()
        return send(request)
    }

    private fun send(request: Request): JSONObject = App.http.newCall(request).execute().use { response ->
        val text = response.body?.string().orEmpty()
        if (response.code == 403 || text.startsWith("Access forbidden")) {
            throw PmsException("Off campus (HTTP ${response.code})", offCampus = true)
        }
        try {
            JSONObject(text)
        } catch (_: Exception) {
            throw PmsException("Non-JSON response: HTTP ${response.code}")
        }
    }

    // -- RSA ------------------------------------------------------------------

    /** JSEncrypt-compatible encryption: PKCS#1 v1.5 padding, base64 output. */
    fun rsaEncrypt(publicKey: String, plaintext: String): String {
        val der = decodeKey(publicKey)
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return Base64.encodeToString(cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    /** Accepts a full PEM block or the raw base64 the server returns. */
    fun decodeKey(key: String): ByteArray {
        val body = key
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .filterNot { it.isWhitespace() }
        return Base64.decode(body, Base64.DEFAULT)
    }
}
