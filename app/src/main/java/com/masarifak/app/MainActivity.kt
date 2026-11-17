package com.masarifak.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Bundle
import android.widget.Toast
import android.widget.EditText
import android.speech.RecognizerIntent
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.JsPromptResult
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes
import com.google.android.gms.tasks.Task
import org.json.JSONObject
import java.security.MessageDigest
import java.io.File
import java.net.URLEncoder

data class NativeSmsEntry(
    val i: String,
    val n: String,
    val b: String,
    val p: String,
    val s: String = "1",
    val d: String = "1",
    val v: Int = 0
)

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val gson = Gson()
    private lateinit var firebaseAuth: FirebaseAuth
    private val firestore by lazy {
        FirebaseFirestore.getInstance().also { db ->
            db.firestoreSettings = FirebaseFirestoreSettings.Builder()
                .setPersistenceEnabled(false)
                .build()
        }
    }
    @Volatile private var pendingCloudSave: Task<Void>? = null
    @Volatile private var pendingUpdateVersion: String? = null
    @Volatile private var pendingUpdateNotes: String = ""
    private val updateDownloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            val expected = getSharedPreferences("msf_update", Context.MODE_PRIVATE).getLong("download_id", -2L)
            if (id == expected) handleCompletedUpdateDownload(id)
        }
    }
    private var updateReceiverRegistered = false

    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // Do not interpret RESULT_CANCELED as a user cancellation by itself.
        // Google may return the actual failure status in the result Intent.
        try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            val account = task.getResult(ApiException::class.java)
            val idToken = account.idToken
            if (idToken.isNullOrBlank()) {
                dispatchGoogleAuthError("اختير حساب Google لكن لم يصل رمز الدخول. تحقق من Web Client ID وبصمة SHA-1 لشهادة APK.")
            } else {
                val credential = GoogleAuthProvider.getCredential(idToken, null)
                firebaseAuth.signInWithCredential(credential)
                    .addOnSuccessListener { authResult ->
                        val user = authResult.user
                        if (user == null) dispatchGoogleAuthError("تم قبول Google لكن تعذر قراءة مستخدم Firebase")
                        else resetLegacyCloudDataIfNeeded(user.uid, user.email ?: "", user.displayName ?: "")
                    }
                    .addOnFailureListener { e ->
                        dispatchGoogleAuthError("وصل حساب Google لكن رفض Firebase تسجيله: " + (e.localizedMessage ?: e.javaClass.simpleName))
                    }
            }
        } catch (e: ApiException) {
            val statusName = GoogleSignInStatusCodes.getStatusCodeString(e.statusCode)
            val statusMessage = e.localizedMessage ?: "لا توجد تفاصيل إضافية"
            dispatchGoogleAuthError(
                "فشل إكمال اختيار حساب Google. رمز الخطأ: ${e.statusCode} ($statusName). التفاصيل: $statusMessage. نتيجة Android: ${result.resultCode}."
            )
        } catch (e: Exception) {
            dispatchGoogleAuthError(
                "تعذر إكمال تسجيل Google: " + (e.localizedMessage ?: e.javaClass.simpleName) + ". نتيجة Android: ${result.resultCode}."
            )
        }
    }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data
            if (uri != null) {
                try {
                    val descriptor = contentResolver.openAssetFileDescriptor(uri, "r")
                    val declaredSize = descriptor?.length ?: -1L
                    descriptor?.close()
                    if (declaredSize > 150L * 1024L * 1024L) throw IllegalArgumentException("حجم الملف المختار يتجاوز 150 ميجابايت")
                    val input = contentResolver.openInputStream(uri) ?: throw IllegalStateException("تعذر فتح الملف المختار")
                    input.use { stream ->
                        val bytes = stream.readBytes()
                        val updateVersion = pendingUpdateVersion
                        if (!updateVersion.isNullOrBlank()) {
                            val notes = pendingUpdateNotes
                            pendingUpdateVersion = null
                            pendingUpdateNotes = ""
                            uploadUpdateApkBytes(bytes, updateVersion, notes)
                        } else {
                            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                            evaluateJs("if(window.onNativeXlsxPicked) window.onNativeXlsxPicked('__BASE64__:$b64');")
                        }
                    }
                } catch (e: Exception) {
                    val updateVersion = pendingUpdateVersion
                    pendingUpdateVersion = null
                    pendingUpdateNotes = ""
                    if (!updateVersion.isNullOrBlank()) {
                        evaluateJs("if(window.onNativeUpdatePublished) window.onNativeUpdatePublished(false, ${gson.toJson(e.localizedMessage ?: "تعذر قراءة ملف التحديث")});")
                    } else {
                        evaluateJs("if(window.onNativeXlsxPicked) window.onNativeXlsxPicked('');")
                    }
                    e.printStackTrace()
                }
            } else {
                pendingUpdateVersion = null
                pendingUpdateNotes = ""
            }
        } else {
            pendingUpdateVersion = null
            pendingUpdateNotes = ""
        }
    }

    private val voiceLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val text = matches?.firstOrNull() ?: ""
            evaluateJs("if(window.onNativeVoiceResult) window.onNativeVoiceResult(${gson.toJson(text)});")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        firebaseAuth = FirebaseAuth.getInstance()
        createNotificationChannels()
        registerUpdateDownloadReceiver()

        webView = WebView(this)
        setContentView(webView)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleAppBackNavigation()
            }
        })

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            useWideViewPort = true
            loadWithOverviewMode = true
            displayZoomControls = false
            builtInZoomControls = false
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                evaluateJs("if(window.onNativeBridgeReady) window.onNativeBridgeReady();")
                scanAndDispatchSms(silent = true)
                val user = firebaseAuth.currentUser
                if (user != null) resetLegacyCloudDataIfNeeded(user.uid, user.email ?: "", user.displayName ?: "")
                else evaluateJs("if(window.onGoogleAuthState) window.onGoogleAuthState(false);")
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?
            ): Boolean {
                if (message != null && message.startsWith("__MSF_")) {
                    handleMsfCommand(message)
                    result?.confirm()
                    return true
                }
                return super.onJsAlert(view, url, message, result)
            }

            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message ?: "")
                    .setPositiveButton("موافق") { _, _ -> result?.confirm() }
                    .setNegativeButton("إلغاء") { _, _ -> result?.cancel() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }

            override fun onJsPrompt(
                view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?
            ): Boolean {
                val input = EditText(this@MainActivity).apply {
                    setText(defaultValue ?: "")
                    setSingleLine(true)
                }
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message ?: "")
                    .setView(input)
                    .setPositiveButton("موافق") { _, _ -> result?.confirm(input.text?.toString() ?: "") }
                    .setNegativeButton("إلغاء") { _, _ -> result?.cancel() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }
        }

        webView.addJavascriptInterface(AndroidBridgeInterface(), "AndroidBridge")
        webView.loadUrl("file:///android_asset/index.html")
    }

    override fun onResume() {
        super.onResume()
        resumePendingUpdateInstall()
        evaluateJs("if(window.onNativeAppResume) window.onNativeAppResume();")
        // Refresh the inbox after returning from background so bank messages received
        // while the activity was paused are processed by the app's deduplicating parser.
        if (::webView.isInitialized) scanAndDispatchSms(silent = true)
    }

    private var lastBackPressTime = 0L

    private fun handleAppBackNavigation() {
        webView.evaluateJavascript(
            "(function() { return (window.handleAndroidBack && window.handleAndroidBack()) ? 'HANDLED' : 'EXIT'; })();"
        ) { result ->
            val status = result?.replace("\"", "")?.trim()
            if (status != "HANDLED") {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    val now = System.currentTimeMillis()
                    if (now - lastBackPressTime < 2000) finish()
                    else {
                        lastBackPressTime = now
                        Toast.makeText(this@MainActivity, "اضغط رجوع مرة أخرى للخروج من مصاريفك", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        handleAppBackNavigation()
    }

    fun evaluateJs(script: String) {
        runOnUiThread {
            webView.evaluateJavascript(script, null)
        }
    }

    private fun handleMsfCommand(cmd: String) {
        when {
            cmd == "__MSF_SCAN_SMS__" -> {
                scanAndDispatchSms(silent = false)
            }
            cmd == "__MSF_REQ_PERM__" -> {
                requestSmsPermissions()
            }
            cmd.startsWith("__MSF_SHARE__:") -> {
                val text = cmd.removePrefix("__MSF_SHARE__:")
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                startActivity(Intent.createChooser(intent, "مشاركة"))
            }
            cmd.startsWith("__MSF_NOTIFY__:") -> {
                val payload = cmd.removePrefix("__MSF_NOTIFY__:")
                val parts = payload.split("|")
                val title = parts.getOrNull(0) ?: "مصاريفك"
                val body = parts.getOrNull(1) ?: ""
                showNotification(title, body)
            }
            cmd == "__MSF_PICK_XLSX__" -> {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                }
                filePickerLauncher.launch(intent)
            }
            cmd == "__MSF_PICK_UPDATE_APK__" -> {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/vnd.android.package-archive"
                }
                filePickerLauncher.launch(intent)
            }
            cmd == "__MSF_VOICE__" -> {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-SA")
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "انطق تفاصيل المصروف...")
                }
                try {
                    voiceLauncher.launch(intent)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun scanAndDispatchSms(silent: Boolean = true) {
        Thread {
            try {
                val list = mutableListOf<NativeSmsEntry>()
                val seen = mutableSetOf<String>()
                val canReadInbox = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
                if (canReadInbox) {
                    val uri = Uri.parse("content://sms/inbox")
                    val projection = arrayOf("_id", "address", "body", "date", "type")
                    val cursor = contentResolver.query(uri, projection, null, null, "date DESC LIMIT 10000")
                    cursor?.use { c ->
                        val idCol = c.getColumnIndex("_id")
                        val addrCol = c.getColumnIndex("address")
                        val bodyCol = c.getColumnIndex("body")
                        val dateCol = c.getColumnIndex("date")
                        val typeCol = c.getColumnIndex("type")
                        while (c.moveToNext()) {
                            val id = if (idCol >= 0) c.getString(idCol) else ""
                            val addr = if (addrCol >= 0) c.getString(addrCol) else ""
                            val body = if (bodyCol >= 0) c.getString(bodyCol) else ""
                            val date = if (dateCol >= 0) c.getLong(dateCol) else System.currentTimeMillis()
                            val type = if (typeCol >= 0) c.getInt(typeCol) else 1
                            if (type != 1 || body.isBlank()) continue
                            val encAddr = URLEncoder.encode(addr, "UTF-8").replace("+", "%20")
                            val encBody = URLEncoder.encode(body, "UTF-8").replace("+", "%20")
                            list.add(NativeSmsEntry(i = id, n = encAddr, b = encBody, p = date.toString(), s = "1", d = type.toString(), v = 0))
                            seen.add("$addr|$body|$date")
                        }
                    }
                } else if (!silent) {
                    runOnUiThread { requestSmsPermissions() }
                }

                // Add receiver-captured messages even when READ_SMS was unavailable.
                val prefs = getSharedPreferences("msf_background_sms", Context.MODE_PRIVATE)
                val queued = org.json.JSONArray(prefs.getString("pending_bank_sms", "[]") ?: "[]")
                for (i in 0 until queued.length()) {
                    val item = queued.optJSONObject(i) ?: continue
                    val id = item.optString("id", "")
                    val sender = item.optString("sender", "")
                    val body = item.optString("body", "")
                    val date = item.optString("date", "0")
                    val decodedSender = try { java.net.URLDecoder.decode(sender, "UTF-8") } catch (_: Exception) { sender }
                    val decodedBody = try { java.net.URLDecoder.decode(body, "UTF-8") } catch (_: Exception) { body }
                    val key = "$decodedSender|$decodedBody|$date"
                    if (body.isBlank() || !seen.add(key)) continue
                    list.add(NativeSmsEntry(i = id, n = sender, b = body, p = date, s = "1", d = "1", v = 0))
                }

                val jsonPayload = gson.toJson(list)
                runOnUiThread {
                    evaluateJs("if(window.onNativeMediaDiscovered) window.onNativeMediaDiscovered($jsonPayload);")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun requestSmsPermissions() {
        val perms = arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)
        ActivityCompat.requestPermissions(this, perms, 101)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 102) return
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (requestCode == 103) {
            evaluateJs("if(window.onNativeAudioPermissionResult) window.onNativeAudioPermissionResult($granted);")
            return
        }
        evaluateJs("if(window.onNativePermissionResult) window.onNativePermissionResult($granted);")
        if (granted) scanAndDispatchSms(silent = false)
        if (requestCode == 101 && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 102)
        }
    }

    private fun showNotification(title: String, body: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(this, "masarifak_sms_channel")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        nm.notify((System.currentTimeMillis() % 10000).toInt(), notification)
    }

    private fun startGoogleSignIn() {
        try {
            val webClientId = getString(R.string.default_web_client_id)
            if (webClientId.isBlank()) {
                dispatchGoogleAuthError("إعداد Google غير مكتمل داخل التطبيق (Web Client ID مفقود).")
                return
            }
            val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestIdToken(webClientId)
                .build()
            val client = GoogleSignIn.getClient(this, options)
            googleSignInLauncher.launch(client.signInIntent)
        } catch (e: Exception) {
            dispatchGoogleAuthError("تعذر فتح شاشة حساب Google: " + (e.localizedMessage ?: e.javaClass.simpleName))
        }
    }

    private fun resetLegacyCloudDataIfNeeded(uid: String, email: String, displayName: String) {
        val appData = firestore.collection("users").document(uid).collection("appData")
        appData.document("main").get()
            .addOnSuccessListener { snapshot ->
                val raw = snapshot.getString("stateJson")
                val encrypted = snapshot.getBoolean("encrypted") == true
                val schemaVersion = snapshot.getLong("schemaVersion")?.toInt() ?: 0
                val validCurrentState = try {
                    if (raw.isNullOrBlank()) false else {
                        val parsed = JSONObject(raw)
                        parsed.optJSONArray("expenses") != null &&
                            parsed.optJSONArray("categories") != null &&
                            schemaVersion >= 37 && !encrypted
                    }
                } catch (_: Exception) { false }

                if (snapshot.exists() && !validCurrentState) {
                    appData.get()
                        .addOnSuccessListener { docs ->
                            val batch = firestore.batch()
                            for (doc in docs.documents) batch.delete(doc.reference)
                            batch.commit()
                                .addOnSuccessListener { dispatchFreshGoogleSession(uid, email, displayName) }
                                .addOnFailureListener { e ->
                                    val payload = mapOf("ok" to true, "uid" to uid, "email" to email,
                                        "displayName" to displayName, "cloudError" to
                                            ("تعذر تهيئة مساحة Google السحابية. تحقق من اتصال الإنترنت وصلاحيات قاعدة البيانات."))
                                    evaluateJs("if(window.onGoogleAuthResult) window.onGoogleAuthResult(${gson.toJson(payload)});")
                                }
                        }
                        .addOnFailureListener { e ->
                            val payload = mapOf("ok" to true, "uid" to uid, "email" to email,
                                "displayName" to displayName, "cloudError" to
                                    ("تعذر الاتصال بخدمة Google السحابية. تحقق من الإنترنت ثم أعد المحاولة."))
                            evaluateJs("if(window.onGoogleAuthResult) window.onGoogleAuthResult(${gson.toJson(payload)});")
                        }
                } else {
                    loadUserCloudState(uid, email, displayName)
                }
            }
            .addOnFailureListener { e ->
                val payload = mapOf("ok" to true, "uid" to uid, "email" to email,
                    "displayName" to displayName, "cloudError" to
                        ("تعذر الاتصال بخدمة Google السحابية. تحقق من الإنترنت ثم أعد المحاولة."))
                evaluateJs("if(window.onGoogleAuthResult) window.onGoogleAuthResult(${gson.toJson(payload)});")
            }
    }

    private fun dispatchFreshGoogleSession(uid: String, email: String, displayName: String) {
        val payload = mapOf("ok" to true, "uid" to uid, "email" to email,
            "displayName" to displayName, "stateJson" to null, "encrypted" to false)
        evaluateJs("if(window.onGoogleAuthResult) window.onGoogleAuthResult(${gson.toJson(payload)});")
    }

    private fun loadUserCloudState(uid: String, email: String, displayName: String) {
        firestore.collection("users").document(uid).collection("appData").document("main").get()
            .addOnSuccessListener { snapshot ->
                val stateJson = if (snapshot.exists()) snapshot.getString("stateJson") else null
                val encrypted = snapshot.exists() && snapshot.getBoolean("encrypted") == true
                val payload = mapOf("ok" to true, "uid" to uid, "email" to email, "displayName" to displayName,
                    "stateJson" to stateJson, "encrypted" to encrypted)
                evaluateJs("if(window.onGoogleAuthResult) window.onGoogleAuthResult(${gson.toJson(payload)});")
            }
            .addOnFailureListener { e ->
                val payload = mapOf("ok" to true, "uid" to uid, "email" to email, "displayName" to displayName,
                    "stateJson" to null, "encrypted" to false,
                    "cloudError" to "تعذر الاتصال بحساب Google السحابي. تحقق من الإنترنت ثم أعد المحاولة.")
                evaluateJs("if(window.onGoogleAuthResult) window.onGoogleAuthResult(${gson.toJson(payload)});")
            }
    }

    private fun registerUpdateDownloadReceiver() {
        if (updateReceiverRegistered) return
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        try {
            ContextCompat.registerReceiver(this, updateDownloadReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            updateReceiverRegistered = true
        } catch (e: Exception) { e.printStackTrace() }
    }

    override fun onDestroy() {
        if (updateReceiverRegistered) {
            try { unregisterReceiver(updateDownloadReceiver) } catch (_: Exception) {}
            updateReceiverRegistered = false
        }
        super.onDestroy()
    }

    private fun resumePendingUpdateInstall() {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) return
        val prefs = getSharedPreferences("msf_update", Context.MODE_PRIVATE)
        val id = prefs.getLong("download_id", -1L)
        if (id > 0L) handleCompletedUpdateDownload(id)
    }

    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun signerDigests(info: android.content.pm.PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners?.toList() ?: emptyList()
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.toList() ?: emptyList()
        }
        return signatures.map { sha256Hex(it.toByteArray()) }.toSet()
    }

    @Suppress("DEPRECATION")
    private fun handleCompletedUpdateDownload(downloadId: Long) {
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor = dm.query(DownloadManager.Query().setFilterById(downloadId)) ?: return
        var status = -1
        cursor.use { if (it.moveToFirst()) status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) }
        if (status == DownloadManager.STATUS_FAILED) {
            evaluateJs("if(window.onNativeUpdateInstallStatus) window.onNativeUpdateInstallStatus(false, 'فشل تنزيل ملف التحديث. أعد المحاولة.');")
            getSharedPreferences("msf_update", Context.MODE_PRIVATE).edit().remove("download_id").apply()
            return
        }
        if (status != DownloadManager.STATUS_SUCCESSFUL) return
        val prefs = getSharedPreferences("msf_update", Context.MODE_PRIVATE)
        val fileName = prefs.getString("file_name", "masarifak-update.apk") ?: "masarifak-update.apk"
        val expectedHash = prefs.getString("sha256", "") ?: ""
        val expectedVersion = prefs.getString("version", "") ?: ""
        val file = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
        try {
            if (!file.isFile || file.length() < 100L * 1024L) throw IllegalStateException("ملف التحديث غير مكتمل")
            if (!expectedHash.matches(Regex("(?i)^[a-f0-9]{64}$"))) throw IllegalStateException("بصمة SHA-256 غير صالحة في بيانات التحديث")
            if (!sha256File(file).equals(expectedHash, ignoreCase = true)) throw IllegalStateException("فشل التحقق من سلامة ملف APK (SHA-256)")
            val flags = if (Build.VERSION.SDK_INT >= 28) android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES else android.content.pm.PackageManager.GET_SIGNATURES
            val candidate = packageManager.getPackageArchiveInfo(file.absolutePath, flags) ?: throw IllegalStateException("الملف ليس APK قابلاً للقراءة")
            if (candidate.packageName != packageName) throw IllegalStateException("هذا التحديث ليس لتطبيق مصاريفك")
            if (candidate.versionName != expectedVersion) throw IllegalStateException("رقم إصدار APK لا يطابق بيان التحديث")
            val current = packageManager.getPackageInfo(packageName, flags)
            if (signerDigests(candidate) != signerDigests(current)) throw IllegalStateException("توقيع APK مختلف؛ أوقفت التثبيت لحماية التطبيق وبياناتك")
            if (Build.VERSION.SDK_INT >= 28 && candidate.longVersionCode <= current.longVersionCode) throw IllegalStateException("الإصدار المختار ليس أحدث من النسخة المثبتة")
            if (Build.VERSION.SDK_INT < 28 && candidate.versionCode <= current.versionCode) throw IllegalStateException("الإصدار المختار ليس أحدث من النسخة المثبتة")
            if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
                evaluateJs("if(window.onNativeUpdateInstallStatus) window.onNativeUpdateInstallStatus(null, 'اسمح لمصاريفك بتثبيت التطبيقات من هذا المصدر، ثم ارجع إلى التطبيق واضغط تثبيت التحديث.');")
                val settings = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
                startActivity(settings)
                return
            }
            val contentUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val install = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            getSharedPreferences("msf_update", Context.MODE_PRIVATE).edit().remove("download_id").apply()
            startActivity(install)
            evaluateJs("if(window.onNativeUpdateInstallStatus) window.onNativeUpdateInstallStatus(true, 'تم فتح شاشة تثبيت التحديث. أكّد التثبيت من Android.');")
        } catch (e: Exception) {
            evaluateJs("if(window.onNativeUpdateInstallStatus) window.onNativeUpdateInstallStatus(false, ${gson.toJson(e.localizedMessage ?: "تعذر تجهيز التحديث")});")
            prefs.edit().remove("download_id").apply()
        }
    }

    private fun startUpdateDownload(url: String, sha256: String, version: String) {
        try {
            val uri = Uri.parse(url)
            if (uri.scheme != "https" || uri.host.isNullOrBlank()) throw IllegalArgumentException("رابط التحديث غير آمن")
            if (!sha256.matches(Regex("(?i)^[a-f0-9]{64}$"))) throw IllegalArgumentException("بيان التحديث لا يحتوي بصمة SHA-256 صالحة")
            if (!version.matches(Regex("^[0-9]+\\.[0-9]+\\.[0-9]+$"))) throw IllegalArgumentException("رقم الإصدار غير صالح")
            val fileName = "masarifak-update-$version.apk"
            File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName).delete()
            val request = DownloadManager.Request(uri)
                .setTitle("تحديث مصاريفك $version")
                .setDescription("جارٍ تنزيل ملف التحديث والتحقق من سلامته")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
                .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, fileName)
            val id = (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            getSharedPreferences("msf_update", Context.MODE_PRIVATE).edit()
                .putLong("download_id", id).putString("file_name", fileName)
                .putString("sha256", sha256.lowercase()).putString("version", version).apply()
            evaluateJs("if(window.onNativeUpdateInstallStatus) window.onNativeUpdateInstallStatus(null, 'بدأ تنزيل التحديث. سيجري التحقق من الملف والتوقيع قبل التثبيت.');")
        } catch (e: Exception) {
            evaluateJs("if(window.onNativeUpdateInstallStatus) window.onNativeUpdateInstallStatus(false, ${gson.toJson(e.localizedMessage ?: "تعذر بدء التنزيل")});")
        }
    }

    private fun uploadUpdateApkBytes(bytes: ByteArray, version: String, releaseNotes: String) {
        val user = firebaseAuth.currentUser
        if (user == null) {
            evaluateJs("if(window.onNativeUpdatePublished) window.onNativeUpdatePublished(false, 'سجّل الدخول بحساب Google قبل نشر التحديث');")
            return
        }
        if (bytes.size !in (100 * 1024)..(150 * 1024 * 1024) || bytes.size < 4 ||
            bytes[0] != 0x50.toByte() || bytes[1] != 0x4b.toByte()) {
            evaluateJs("if(window.onNativeUpdatePublished) window.onNativeUpdatePublished(false, 'الملف المختار ليس APK صالحاً أو حجمه خارج الحد المسموح');")
            return
        }
        val validationFile = File(cacheDir, "masarifak-update-validation.apk")
        try {
            validationFile.writeBytes(bytes)
            @Suppress("DEPRECATION")
            val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
            val candidate = packageManager.getPackageArchiveInfo(validationFile.absolutePath, flags)
                ?: throw IllegalArgumentException("تعذر قراءة حزمة APK")
            if (candidate.packageName != packageName) throw IllegalArgumentException("الحزمة ليست com.masarifak.app")
            if (candidate.versionName != version) throw IllegalArgumentException("رقم الإصدار داخل APK لا يطابق الرقم الذي أدخلته")
            val installed = packageManager.getPackageInfo(packageName, flags)
            if (signerDigests(candidate) != signerDigests(installed)) throw IllegalArgumentException("شهادة توقيع APK مختلفة عن التطبيق المثبت؛ لن يُنشر تحديث غير قابل للتثبيت")
        } catch (e: Exception) {
            evaluateJs("if(window.onNativeUpdatePublished) window.onNativeUpdatePublished(false, ${gson.toJson(e.localizedMessage ?: "ملف APK غير صالح")});")
            validationFile.delete()
            return
        } finally {
            validationFile.delete()
        }
        val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val fileName = "masarifak-$version.apk"
        val ref = FirebaseStorage.getInstance().reference.child("appUpdates/android/$fileName")
        evaluateJs("if(window.onNativeUpdatePublished) window.onNativeUpdatePublished(null, 'جارٍ رفع ملف APK إلى Firebase Storage…');")
        val metadata = StorageMetadata.Builder()
            .setContentType("application/vnd.android.package-archive")
            .setCustomMetadata("sha256", sha256)
            .setCustomMetadata("packageName", "com.masarifak.app")
            .build()
        ref.putBytes(bytes, metadata)
            .continueWithTask { task ->
                if (!task.isSuccessful) throw task.exception ?: IllegalStateException("فشل رفع APK")
                ref.downloadUrl
            }
            .addOnSuccessListener { downloadUri ->
                val manifest = hashMapOf<String, Any>(
                    "version" to version,
                    "releaseNotes" to releaseNotes,
                    "apkDownloadUrl" to downloadUri.toString(),
                    "packageName" to "com.masarifak.app",
                    "sha256" to sha256,
                    "fileName" to fileName,
                    "publishedAt" to System.currentTimeMillis(),
                    "channelId" to "masarifak_official_android"
                )
                firestore.collection("appConfig").document("androidUpdate").set(manifest)
                    .addOnSuccessListener {
                        evaluateJs("if(window.onNativeUpdatePublished) window.onNativeUpdatePublished(true, ${gson.toJson("تم نشر الإصدار $version بنجاح")});")
                    }
                    .addOnFailureListener { e ->
                        evaluateJs("if(window.onNativeUpdatePublished) window.onNativeUpdatePublished(false, ${gson.toJson("رُفع APK لكن تعذر نشر manifest: " + (e.localizedMessage ?: ""))});")
                    }
            }
            .addOnFailureListener { e ->
                evaluateJs("if(window.onNativeUpdatePublished) window.onNativeUpdatePublished(false, ${gson.toJson(e.localizedMessage ?: "فشل رفع APK")});")
            }
    }

    private fun dispatchGoogleAuthError(message: String) {
        val payload = mapOf("ok" to false, "error" to message)
        evaluateJs("if(window.onGoogleAuthResult) window.onGoogleAuthResult(${gson.toJson(payload)});")
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                "masarifak_sms_channel",
                "إشعارات مصاريفك",
                NotificationManager.IMPORTANCE_HIGH
            )
            nm.createNotificationChannel(channel)
        }
    }

    inner class AndroidBridgeInterface {
        @JavascriptInterface
        fun isNativeApk(): Boolean = true

        @JavascriptInterface
        fun startGoogleSignIn() {
            runOnUiThread {
                try {
                    this@MainActivity.startGoogleSignIn()
                } catch (e: Exception) {
                    dispatchGoogleAuthError("حدث خطأ أثناء تشغيل تسجيل Google: " + (e.localizedMessage ?: e.javaClass.simpleName))
                }
            }
        }


        @JavascriptInterface
        fun refreshGoogleCloudState() {
            val user = firebaseAuth.currentUser ?: return
            resetLegacyCloudDataIfNeeded(user.uid, user.email ?: "", user.displayName ?: "")
        }

        @JavascriptInterface
        @Synchronized
        fun saveCloudState(stateJson: String) {
            val user = firebaseAuth.currentUser
            if (user == null) {
                evaluateJs("if(window.onGoogleCloudSaveError) window.onGoogleCloudSaveError('سجّل الدخول إلى Google أولاً');")
                return
            }
            try {
                if (stateJson.isBlank()) throw IllegalArgumentException("بيانات السجل فارغة")
                val state = JSONObject(stateJson)
                if (state.optJSONArray("expenses") == null || state.optJSONArray("categories") == null)
                    throw IllegalArgumentException("بنية سجل المصاريف غير صالحة")
                if (stateJson.toByteArray(Charsets.UTF_8).size > 850 * 1024)
                    throw IllegalArgumentException("حجم السجل تجاوز حد مستند Google")
            } catch (e: Exception) {
                val msg = gson.toJson(e.localizedMessage ?: "بيانات السجل غير صالحة")
                evaluateJs("if(window.onGoogleCloudSaveError) window.onGoogleCloudSaveError($msg);")
                return
            }
            val state = JSONObject(stateJson)
            val data = hashMapOf<String, Any>("stateJson" to stateJson, "encrypted" to false,
                "schemaVersion" to 37, "appName" to "مصاريفك",
                "userName" to state.optString("userName", user.displayName ?: "مستخدم Google"),
                "homeName" to state.optString("homeName", "المحفظة الرئيسية"),
                "currency" to state.optString("currency", "ر.س"), "updatedAt" to System.currentTimeMillis(),
                "email" to (user.email ?: ""))
            val document = firestore.collection("users").document(user.uid).collection("appData").document("main")
            val previous = pendingCloudSave
            val task: Task<Void> = if (previous == null) document.set(data) else previous.continueWithTask { document.set(data) }
            pendingCloudSave = task
            task.addOnSuccessListener { evaluateJs("if(window.onGoogleCloudSaveSuccess) window.onGoogleCloudSaveSuccess();") }
                .addOnFailureListener { e ->
                    val msg = gson.toJson(e.localizedMessage ?: "تعذر حفظ البيانات في Google")
                    evaluateJs("if(window.onGoogleCloudSaveError) window.onGoogleCloudSaveError($msg);")
                }
        }

        @JavascriptInterface
        fun checkForAppUpdate() {
            firestore.collection("appConfig").document("androidUpdate").get()
                .addOnSuccessListener { snapshot ->
                    val data = if (snapshot.exists()) snapshot.data else null
                    evaluateJs("if(window.onNativeUpdateManifest) window.onNativeUpdateManifest(${gson.toJson(data)});")
                }
                .addOnFailureListener { e ->
                    evaluateJs("if(window.onNativeUpdateManifest) window.onNativeUpdateManifest(null, ${gson.toJson("تعذر فحص التحديثات. تحقق من صلاحيات خدمة التحديث في Firebase.")});")
                }
        }

        @JavascriptInterface
        fun prepareUpdateUpload(version: String, releaseNotes: String) {
            val cleanVersion = version.trim().removePrefix("v")
            if (!cleanVersion.matches(Regex("^[0-9]+\\.[0-9]+\\.[0-9]+$"))) {
                evaluateJs("if(window.onNativeUpdatePublished) window.onNativeUpdatePublished(false, 'رقم الإصدار يجب أن يكون بصيغة 27.0.0');")
                return
            }
            pendingUpdateVersion = cleanVersion
            pendingUpdateNotes = releaseNotes.take(4000)
            evaluateJs("if(window.onNativeUpdatePublished) window.onNativeUpdatePublished(null, 'اختر ملف APK الموقّع لرفعه إلى Firebase Storage…');")
        }

        @JavascriptInterface
        fun downloadAndInstallUpdate(url: String, sha256: String, version: String) {
            runOnUiThread { startUpdateDownload(url, sha256, version) }
        }

        @JavascriptInterface
        fun openExternalUrl(url: String) {
            try {
                val uri = Uri.parse(url)
                if (uri.scheme != "https") return
                runOnUiThread { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            } catch (e: Exception) {
                evaluateJs("if(window.onNativeUpdatePublished) window.onNativeUpdatePublished(false, ${gson.toJson(e.localizedMessage ?: "تعذر فتح الرابط")});")
            }
        }

        @JavascriptInterface
        fun getGoogleUid(): String = firebaseAuth.currentUser?.uid ?: ""

        @JavascriptInterface
        fun hasSmsPermission(): Boolean {
            return ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        }

        @JavascriptInterface
        fun requestSmsPermission() {
            requestSmsPermissions()
        }

        @JavascriptInterface
        fun hasAudioPermission(): Boolean {
            return ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        }

        @JavascriptInterface
        fun requestAudioPermission() {
            if (!hasAudioPermission()) {
                runOnUiThread {
                    ActivityCompat.requestPermissions(this@MainActivity, arrayOf(Manifest.permission.RECORD_AUDIO), 103)
                }
            }
        }

        @JavascriptInterface
        fun acknowledgePendingBankSms(idsJson: String) {
            try {
                val ids = org.json.JSONArray(idsJson)
                val remove = mutableSetOf<String>()
                for (i in 0 until ids.length()) remove.add(ids.optString(i))
                val prefs = getSharedPreferences("msf_background_sms", Context.MODE_PRIVATE)
                val current = org.json.JSONArray(prefs.getString("pending_bank_sms", "[]") ?: "[]")
                val kept = org.json.JSONArray()
                for (i in 0 until current.length()) {
                    val item = current.optJSONObject(i) ?: continue
                    if (!remove.contains(item.optString("id"))) kept.put(item)
                }
                prefs.edit().putString("pending_bank_sms", kept.toString()).apply()
            } catch (_: Exception) {}
        }

        @JavascriptInterface
        fun readSmsInboxJson(limit: Int): String {
            val list = mutableListOf<Map<String, Any>>()
            try {
                val safeLimit = limit.coerceIn(1, 3000)
                val uri = Uri.parse("content://sms/inbox")
                val cursor = contentResolver.query(uri, arrayOf("_id", "address", "body", "date"), null, null, "date DESC LIMIT $safeLimit")
                cursor?.use { c ->
                    val idIdx = c.getColumnIndex("_id")
                    val addrIdx = c.getColumnIndex("address")
                    val bodyIdx = c.getColumnIndex("body")
                    val dateIdx = c.getColumnIndex("date")
                    while (c.moveToNext()) {
                        list.add(mapOf(
                            "id" to if (idIdx >= 0) c.getString(idIdx) else "",
                            "address" to if (addrIdx >= 0) c.getString(addrIdx) else "",
                            "body" to if (bodyIdx >= 0) c.getString(bodyIdx) else "",
                            "date" to if (dateIdx >= 0) c.getLong(dateIdx) else System.currentTimeMillis()
                        ))
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return gson.toJson(list)
        }

        @JavascriptInterface
        fun postLocalNotification(title: String, body: String) {
            showNotification(title, body)
        }


    }
}
