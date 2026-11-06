package com.mynora.singverse.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val LoginInk = Ink
private val LoginPanel = Panel
private val Ivory = Paper
private val LoginMuted = Muted

@Composable internal fun TvLoginScreen(app: TvApp) {
    val state = app.login
    BackHandler(enabled = state.page != LoginPage.METHODS || state.error.isNotBlank() || app.error.isNotBlank()) {
        when {
            app.error.isNotBlank() -> app.error = ""
            state.error.isNotBlank() -> state.clearError()
            else -> state.back()
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val scale = minOf(maxWidth.value / 960f, maxHeight.value / 540f)
        CompositionLocalProvider(LocalDensity provides Density(density.density * scale, density.fontScale)) {
            Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xFF202020), LoginInk), radius = 650f))) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 56.dp, vertical = 36.dp), verticalAlignment = Alignment.CenterVertically) {
                    LoginIcon("brand", Modifier.size(18.dp), Primary); Spacer(Modifier.width(8.dp))
                    Text("SingVerse", color = Ivory, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Text(when (state.page) { LoginPage.METHODS -> "连接你的音乐 / 选择连接方式"; LoginPage.MANUAL -> "连接你的音乐 / 账号登录"; LoginPage.DISCOVERY -> "连接你的音乐 / 查找 NAS"; LoginPage.QR -> "连接你的音乐 / 手机扫码"; LoginPage.HISTORY -> "连接你的音乐 / 最近登录" }, color = LoginMuted, fontSize = 12.sp)
                }
                key(state.page, state.mfaRequired) {
                    when (state.page) {
                        LoginPage.METHODS -> LoginMethods(state)
                        LoginPage.MANUAL -> LoginForm(state)
                        LoginPage.DISCOVERY -> LoginDiscovery(state, app.discovery)
                        LoginPage.QR -> LoginQr(state)
                        LoginPage.HISTORY -> LoginHistory(state)
                    }
                }
                Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 30.dp), horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("方向键  切换", color = LoginMuted, fontSize = 11.sp)
                    Text("OK  确认选择", color = LoginMuted, fontSize = 11.sp)
                    Text("返回键  返回", color = LoginMuted, fontSize = 11.sp)
                }
                if (state.error.isNotBlank()) Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 58.dp).widthIn(max = 660.dp).background(Danger.copy(alpha = .14f), TvShape.card).padding(16.dp)) {
                    Text(state.error, color = Ivory, fontSize = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text("按返回键关闭提示", color = LoginMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

@Composable private fun LoginTitle(title: String, subtitle: String) {
    Text(title, color = Ivory, fontSize = 30.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold)
    Text(subtitle, color = LoginMuted, fontSize = 14.sp, lineHeight = 23.sp, modifier = Modifier.padding(top = 10.dp))
}

@Composable private fun LoginButton(modifier: Modifier = Modifier, primary: Boolean = false, enabled: Boolean = true, padding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 8.dp), onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Button(onClick = onClick, enabled = enabled, modifier = modifier.onFocusChanged { focused = it.isFocused }.tvFocus(focused, TvShape.control), contentPadding = padding,
        shape = ButtonDefaults.shape(TvShape.control), scale = ButtonDefaults.scale(focusedScale = 1f),
        colors = ButtonDefaults.colors(containerColor = if (primary) Primary else LoginPanel, contentColor = if (primary) LoginInk else Ivory,
            focusedContainerColor = if (primary) Primary else Elevated, focusedContentColor = if (primary) LoginInk else Ivory,
            disabledContainerColor = LoginPanel.copy(alpha = .5f), disabledContentColor = LoginMuted)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

@Composable private fun InitialFocus(requester: FocusRequester, ready: Boolean = true) {
    LaunchedEffect(requester, ready) { if (ready) requester.requestFocus() }
}

@Composable private fun BoxScope.LoginMethods(state: TvLogin) {
    val initial = remember { FocusRequester() }; InitialFocus(initial)
    Column(Modifier.align(Alignment.TopCenter).padding(top = 125.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        LoginTitle("让你的音乐，登上大屏", "连接你的音乐库，开启客厅 KTV")
        Row(Modifier.padding(top = 38.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            listOf(Triple("qr", "手机扫码", "在手机上轻松完成授权"), Triple("nas", "查找 NAS", "发现同一网络中的音乐库"), Triple("user", "手动登录", "输入服务器地址与账号")).forEachIndexed { index, item ->
                LoginButton(Modifier.width(192.dp).height(162.dp).then(if (index == 0) Modifier.focusRequester(initial) else Modifier), onClick = {
                    state.open(if (index == 1) LoginPage.DISCOVERY else LoginPage.MANUAL, forQr = index == 0)
                }) {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                        LoginIcon(item.first, Modifier.size(30.dp))
                        Column {
                            Text(item.second, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                            Text(item.third, fontSize = 11.sp, modifier = Modifier.padding(top = 7.dp))
                        }
                    }
                }
            }
        }
        if (state.recent.isNotEmpty()) LoginButton(Modifier.padding(top = 28.dp).width(608.dp), onClick = { state.open(LoginPage.HISTORY) }) {
            Text("最近登录", fontSize = 12.sp); Spacer(Modifier.width(12.dp))
            Text(state.recent.first().name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("查看全部（${state.recent.size}） ›", fontSize = 12.sp)
        }
    }
}

@Composable private fun BoxScope.LoginForm(state: TvLogin) {
    val initial = remember { FocusRequester() }; InitialFocus(initial, !state.busy)
    Column(Modifier.align(Alignment.TopCenter).padding(top = 95.dp).width(435.dp).heightIn(max = 382.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        LoginTitle(if (state.mfaRequired) "验证你的身份" else if (state.qrRequested) "连接扫码登录的 NAS" else "连接你的 NAS",
            if (state.mfaRequired) "输入认证器验证码或恢复码" else if (state.serverName.isNotBlank()) "${state.serverName} · 输入服务器地址和账号信息" else "输入服务器地址和账号信息")
        Spacer(Modifier.height(10.dp))
        if (!state.mfaRequired) {
            Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("连接协议", color = LoginMuted, fontSize = 13.sp); Spacer(Modifier.weight(1f))
                Row(Modifier.background(LoginPanel, RoundedCornerShape(8.dp)).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    listOf("http", "https").forEach { scheme ->
                        LoginButton(Modifier.width(75.dp).height(30.dp), primary = state.protocol == scheme, enabled = !state.busy, padding = PaddingValues(0.dp), onClick = { state.protocol = scheme }) {
                            Text(scheme.uppercase(Locale.ROOT), fontSize = 12.sp)
                        }
                    }
                }
            }
            LoginField("NAS 地址", state.host, "192.168.1.10:18080", Modifier.focusRequester(initial), enabled = !state.busy) { address ->
                if (address.contains("://")) runCatching { state.setAddress(address) }.onFailure { state.host = address } else state.host = address
            }
            if (!state.qrRequested) {
                LoginField("账号", state.username, "请输入账号", enabled = !state.busy) { state.username = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LoginField("密码", state.password, "请输入密码", Modifier.weight(1f), secret = !state.showPassword, enabled = !state.busy) { state.password = it }
                    LoginButton(Modifier.padding(start = 8.dp).size(48.dp), enabled = !state.busy, onClick = { state.showPassword = !state.showPassword }) {
                        LoginIcon(if (state.showPassword) "eyeOff" else "eye", Modifier.size(20.dp))
                    }
                }
            }
        } else LoginField("验证码", state.mfaCode, "验证码或恢复码", Modifier.focusRequester(initial), secret = true, enabled = !state.busy) { state.mfaCode = it }
        LoginButton(Modifier.fillMaxWidth().padding(top = 14.dp).height(43.dp), primary = true, enabled = !state.busy,
            onClick = { if (state.qrRequested) state.startQr() else state.submit() }) {
            Text(if (state.busy) "正在连接…" else if (state.qrRequested) "连接并生成二维码" else if (state.mfaRequired) "验证并登录" else "连接并登录", fontSize = 15.sp)
        }
        Text(if (state.qrRequested) "电视与手机需能访问同一 NAS 地址" else "请使用 NAS 管理员账号", color = LoginMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp))
    }
}

@Composable private fun LoginField(label: String, value: String, hint: String, modifier: Modifier = Modifier, secret: Boolean = false, enabled: Boolean = true, change: (String) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val returnFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LoginButton(modifier.fillMaxWidth().padding(top = 8.dp).height(48.dp).focusRequester(returnFocus), enabled = enabled, onClick = { draft = value; editing = true }) {
        Text(label, fontSize = 14.sp, modifier = Modifier.width(90.dp))
        Text(if (value.isBlank()) hint else if (secret) "•".repeat(value.length.coerceAtMost(16)) else value, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
    if (editing) Dialog(onDismissRequest = { editing = false; draft = ""; keyboard?.hide(); returnFocus.requestFocus() }) {
        Column(Modifier.width(450.dp).background(LoginPanel, RoundedCornerShape(18.dp)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(label, color = Ivory, fontSize = 23.sp)
            BasicTextField(draft, { draft = it.take(256) }, Modifier.fillMaxWidth().focusRequester(focus).background(LoginInk, RoundedCornerShape(10.dp)).padding(14.dp),
                textStyle = TextStyle(color = Ivory, fontSize = 19.sp), singleLine = true, cursorBrush = SolidColor(Ivory),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { change(draft); draft = ""; editing = false; keyboard?.hide(); returnFocus.requestFocus() }))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LoginButton(onClick = { draft = ""; editing = false; keyboard?.hide(); returnFocus.requestFocus() }) { Text("取消") }
                LoginButton(primary = true, onClick = { change(draft); draft = ""; editing = false; keyboard?.hide(); returnFocus.requestFocus() }) { Text("完成") }
            }
        }
        LaunchedEffect(Unit) { focus.requestFocus(); keyboard?.show() }
    }
}

@Composable private fun BoxScope.LoginDiscovery(state: TvLogin, discovery: NasDiscovery) {
    val initial = remember { FocusRequester() }
    val services = discovery.services
    LaunchedEffect(services.isNotEmpty()) { initial.requestFocus() }
    Column(Modifier.align(Alignment.TopCenter).padding(top = 110.dp).width(580.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        LoginTitle("选择你的音乐服务器", "确保电视与 NAS 连接在同一网络")
        Column(Modifier.padding(top = 25.dp).height(190.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (services.isEmpty()) Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                LoginIcon("nas", Modifier.size(40.dp), LoginMuted)
                Text(if (discovery.searching) "正在查找附近的 NAS…" else "暂未发现 NAS", color = Ivory, fontSize = 20.sp, modifier = Modifier.padding(top = 16.dp))
                Text(discovery.status, color = LoginMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            }
            services.forEachIndexed { index, (name, address) ->
                LoginButton(Modifier.fillMaxWidth().height(68.dp).then(if (index == 0) Modifier.focusRequester(initial) else Modifier), onClick = { state.select(name, address) }) {
                    LoginIcon("nas", Modifier.size(26.dp)); Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(NasAddress.authority(address), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                    Text("已发现  ›", fontSize = 12.sp)
                }
            }
        }
        Text("选择 NAS 后，继续填写账号和密码", color = LoginMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LoginButton(if (services.isEmpty()) Modifier.focusRequester(initial) else Modifier, onClick = { discovery.start() }) { Text("重新搜索", fontSize = 14.sp) }
            LoginButton(onClick = { state.open(LoginPage.MANUAL) }) { Text("手动添加", fontSize = 14.sp) }
        }
    }
}

@Composable private fun BoxScope.LoginHistory(state: TvLogin) {
    val initial = remember { FocusRequester() }; InitialFocus(initial)
    Column(Modifier.align(Alignment.TopCenter).padding(top = 110.dp).width(580.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        LoginTitle("最近登录的设备", "选择熟悉的 NAS，继续连接你的音乐库")
        Column(Modifier.padding(top = 24.dp).height(250.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.recent.forEachIndexed { index, device ->
                LoginButton(Modifier.fillMaxWidth().height(82.dp).then(if (index == 0) Modifier.focusRequester(initial) else Modifier), onClick = { state.select(device.name, device.address, device.username) }) {
                    LoginIcon("nas", Modifier.size(26.dp)); Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) {
                        Text(device.name, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(device.address, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(device.username.ifBlank { "手机授权" }, fontSize = 11.sp)
                    }
                    Text(SimpleDateFormat("MM/dd HH:mm", Locale.CHINA).format(Date(device.lastLogin)) + "  ›", fontSize = 12.sp)
                }
            }
            if (state.recent.isEmpty()) Text("暂无登录记录", color = LoginMuted, fontSize = 18.sp)
        }
        Text("选择记录后填写密码，完成登录", color = LoginMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp))
    }
}

@Composable private fun BoxScope.LoginQr(state: TvLogin) {
    val initial = remember { FocusRequester() }; InitialFocus(initial, !state.busy)
    Row(Modifier.align(Alignment.Center).width(720.dp), horizontalArrangement = Arrangement.spacedBy(60.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("音乐就绪，只差你了", color = LoginMuted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 15.dp))
            LoginTitle("手机扫码登录", "使用微信或手机相机扫码，\n在 NAS 管理页确认电视授权。")
            StatusPill(if (state.qrUrl.isNotBlank()) "NAS 已连接" else "正在核对 NAS", if (state.qrUrl.isNotBlank()) Success else LoginMuted,
                Modifier.padding(top = 24.dp))
            Text(state.serverName.ifBlank { "正在连接 NAS…" }, color = Ivory, fontSize = 18.sp, modifier = Modifier.padding(top = 8.dp))
            Text("核对授权码：${state.authorizationCode.ifBlank { "—" }}", color = LoginMuted, fontSize = 15.sp, modifier = Modifier.padding(top = 16.dp))
            LoginButton(Modifier.padding(top = 15.dp), onClick = { state.open(LoginPage.MANUAL, forQr = true) }) { Text("更换 NAS", fontSize = 13.sp) }
        }
        Column(Modifier.width(275.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(220.dp).clip(TvShape.card).background(Color.White).padding(8.dp), contentAlignment = Alignment.Center) {
                if (state.qrUrl.isNotBlank()) Qr(state.qrUrl, Modifier.fillMaxSize())
                else Text(if (state.busy) "正在生成二维码…" else "二维码不可用", color = LoginInk, fontSize = 16.sp)
            }
            Text(if (state.remaining > 0) "等待手机确认" else "等待生成或刷新", color = Ivory, fontSize = 16.sp, modifier = Modifier.padding(top = 15.dp))
            Text("有效期 ${state.remaining / 60}:${(state.remaining % 60).toString().padStart(2, '0')}", color = LoginMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 7.dp))
            LoginButton(Modifier.padding(top = 16.dp).focusRequester(initial), enabled = !state.busy, onClick = { state.startQr() }) { Text("刷新二维码", fontSize = 14.sp) }
        }
    }
}

@Composable private fun LoginIcon(kind: String, modifier: Modifier, color: Color = Color.Unspecified) {
    val tint = if (color == Color.Unspecified) androidx.tv.material3.LocalContentColor.current else color
    Canvas(modifier) {
        val unit = size.width / 24f
        fun line(x: Float, y: Float, a: Float, b: Float) = drawLine(tint, Offset(x * unit, y * unit), Offset(a * unit, b * unit), 1.4f * unit, StrokeCap.Round)
        when (kind) {
            "brand" -> (0..4).forEach { i -> val h = listOf(8f, 15f, 23f, 15f, 8f)[i]; line(3f + i * 4.5f, (24f - h) / 2, 3f + i * 4.5f, (24f + h) / 2) }
            "nas" -> listOf(3f, 14f).forEach { y -> drawRoundRect(tint, Offset(3 * unit, y * unit), Size(18 * unit, 7 * unit), androidx.compose.ui.geometry.CornerRadius(2 * unit), style = Stroke(1.4f * unit)); line(6f, y + 3.5f, 11f, y + 3.5f); drawCircle(tint, unit, Offset(17 * unit, (y + 3.5f) * unit)) }
            "user" -> { drawCircle(tint, 4 * unit, Offset(12 * unit, 7 * unit), style = Stroke(1.4f * unit)); drawArc(tint, 180f, 180f, false, Offset(4 * unit, 13 * unit), Size(16 * unit, 16 * unit), style = Stroke(1.4f * unit)) }
            "eye", "eyeOff" -> { drawOval(tint, Offset(2 * unit, 6 * unit), Size(20 * unit, 12 * unit), style = Stroke(1.4f * unit)); drawCircle(tint, 3 * unit, Offset(12 * unit, 12 * unit), style = Stroke(1.4f * unit)); if (kind == "eyeOff") line(3f, 3f, 21f, 21f) }
            else -> { listOf(Offset(3f, 3f), Offset(15f, 3f), Offset(3f, 15f)).forEach { drawRect(tint, Offset(it.x * unit, it.y * unit), Size(6 * unit, 6 * unit), style = Stroke(1.4f * unit)) }; line(16f, 15f, 16f, 21f); line(16f, 21f, 21f, 21f); line(20f, 15f, 21f, 15f) }
        }
    }
}
