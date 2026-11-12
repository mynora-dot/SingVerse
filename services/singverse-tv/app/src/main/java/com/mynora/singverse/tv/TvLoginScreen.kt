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
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
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
            Box(Modifier.fillMaxSize().background(LoginInk)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    LoginIcon("brand", Modifier.size(24.dp), Primary); Spacer(Modifier.width(8.dp))
                    Text("SingVerse", color = Ivory, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.weight(1f))
                    Text(when (state.page) {
                        LoginPage.METHODS -> "连接你的音乐 / 选择连接方式"
                        LoginPage.MANUAL -> if (state.mfaRequired) "连接你的音乐 / 身份验证" else if (state.qrRequested) "连接你的音乐 / 手机扫码" else "连接你的音乐 / 账号登录"
                        LoginPage.DISCOVERY -> "连接你的音乐 / 查找 NAS"
                        LoginPage.QR -> "连接你的音乐 / 手机扫码"
                        LoginPage.HISTORY -> "连接你的音乐 / 最近连接"
                    }, color = LoginMuted, fontSize = 12.sp, lineHeight = 18.sp)
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
                Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("方向键选择", color = LoginMuted, fontSize = 12.sp)
                    Text("确认键进入", color = LoginMuted, fontSize = 12.sp)
                    Text("返回键返回", color = LoginMuted, fontSize = 12.sp)
                }
                if (state.error.isNotBlank()) Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 58.dp).widthIn(max = 660.dp).background(Danger.copy(alpha = .14f), TvShape.card).padding(16.dp)) {
                    Text(state.error, color = Ivory, fontSize = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text("按返回键关闭提示", color = LoginMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}

@Composable private fun LoginTitle(title: String, subtitle: String) {
    Text(title, color = Ivory, fontSize = 32.sp, lineHeight = 44.sp, fontWeight = FontWeight.Medium)
    Text(subtitle, color = LoginMuted, fontSize = 14.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 8.dp))
}

@Composable private fun LoginButton(modifier: Modifier = Modifier, primary: Boolean = false, enabled: Boolean = true, padding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 8.dp), onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Button(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 36.dp).onFocusChanged { focused = it.isFocused }.tvFocus(focused, TvShape.control), contentPadding = padding,
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
    Column(Modifier.align(Alignment.Center).width(620.dp).padding(bottom = 8.dp)) {
        LoginTitle("让你的音乐，登上大屏", "连接家里的 NAS，开始客厅 KTV")
        Row(Modifier.padding(top = 28.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            listOf(Triple("qr", "手机扫码", "在手机上完成授权"), Triple("nas", "查找 NAS", "发现同一网络中的音乐库"), Triple("user", "手动登录", "输入服务器地址与账号")).forEachIndexed { index, item ->
                LoginButton(Modifier.weight(1f).height(128.dp).then(if (index == 0) Modifier.focusRequester(initial) else Modifier), padding = PaddingValues(16.dp), onClick = {
                    state.open(if (index == 1) LoginPage.DISCOVERY else LoginPage.MANUAL, forQr = index == 0)
                }) {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                        LoginIcon(item.first, Modifier.size(24.dp), LoginMuted)
                        Column {
                            Text(item.second, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
                            Text(item.third, color = LoginMuted, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }
        if (state.recent.isNotEmpty()) LoginButton(Modifier.padding(top = 16.dp).fillMaxWidth().height(40.dp), onClick = { state.open(LoginPage.HISTORY) }) {
            Text("最近连接", fontSize = 14.sp); Spacer(Modifier.width(16.dp))
            Text(state.recent.first().name, color = LoginMuted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("查看全部 · ${state.recent.size} 台 NAS", color = LoginMuted, fontSize = 12.sp)
        }
    }
}

@Composable private fun BoxScope.LoginForm(state: TvLogin) {
    val initial = remember { FocusRequester() }; InitialFocus(initial, !state.busy)
    Row(Modifier.align(Alignment.Center).width(760.dp).padding(top = 12.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(60.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            LoginIcon("brand", Modifier.size(52.dp), Primary)
            Spacer(Modifier.height(20.dp))
            LoginTitle(if (state.mfaRequired) "验证你的身份" else if (state.qrRequested) "先选择你的 NAS" else "连接你的 NAS",
                if (state.mfaRequired) "使用认证器验证码或恢复码" else if (state.qrRequested) "电视与手机需要能访问同一个 NAS 地址" else "使用 NAS 管理员账号\n让这台电视连接家里的曲库")
            LoginButton(Modifier.padding(top = 16.dp), enabled = !state.busy, onClick = { state.back() }) { Text("返回连接方式", fontSize = 14.sp) }
        }
        Column(Modifier.width(360.dp).heightIn(max = 400.dp).background(LoginPanel, TvShape.card).padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (state.mfaRequired) "身份验证" else if (state.qrRequested) "服务器地址" else "账号登录", color = Ivory, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
            if (state.serverName.isNotBlank()) Text(state.serverName, color = LoginMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!state.mfaRequired) {
                Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("连接协议", color = LoginMuted, fontSize = 12.sp); Spacer(Modifier.weight(1f))
                    Row(Modifier.background(LoginInk, TvShape.control).padding(2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("http", "https").forEach { scheme ->
                            LoginButton(Modifier.width(64.dp).height(36.dp), primary = state.protocol == scheme, enabled = !state.busy, padding = PaddingValues(0.dp), onClick = { state.protocol = scheme }) {
                                Text(scheme.uppercase(Locale.ROOT), fontSize = 12.sp)
                            }
                        }
                    }
                }
                LoginField("NAS 地址", state.host, "输入 NAS 地址与端口", Modifier.focusRequester(initial), enabled = !state.busy) { address ->
                    if (address.contains("://")) runCatching { state.setAddress(address) }.onFailure { state.host = address } else state.host = address
                }
                if (!state.qrRequested) {
                    LoginField("账号", state.username, "请输入账号", enabled = !state.busy) { state.username = it }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LoginField("密码", state.password, "请输入密码", Modifier.weight(1f), secret = !state.showPassword, enabled = !state.busy) { state.password = it }
                        LoginButton(Modifier.padding(start = 8.dp, top = 22.dp).size(40.dp), padding = PaddingValues(8.dp), enabled = !state.busy, onClick = { state.showPassword = !state.showPassword }) {
                            LoginIcon(if (state.showPassword) "eyeOff" else "eye", Modifier.size(18.dp))
                        }
                    }
                }
            } else LoginField("验证码或恢复码", state.mfaCode, "输入验证码", Modifier.focusRequester(initial), secret = true, enabled = !state.busy) { state.mfaCode = it }
            LoginButton(Modifier.fillMaxWidth().padding(top = 4.dp).height(36.dp), primary = true, enabled = !state.busy,
                onClick = { if (state.qrRequested) state.startQr() else state.submit() }) {
                Text(if (state.busy) "正在连接…" else if (state.qrRequested) "连接并生成二维码" else if (state.mfaRequired) "验证并登录" else "连接并登录", fontSize = 14.sp)
            }
            Text(if (state.qrRequested) "手机需能访问所填 NAS 地址" else "请使用 NAS 管理员账号", color = LoginMuted, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable private fun LoginField(label: String, value: String, hint: String, modifier: Modifier = Modifier, secret: Boolean = false, enabled: Boolean = true, change: (String) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val returnFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = LoginMuted, fontSize = 12.sp, lineHeight = 18.sp)
        LoginButton(Modifier.fillMaxWidth().height(40.dp).focusRequester(returnFocus), enabled = enabled, padding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), onClick = { draft = value; editing = true }) {
            Text(if (value.isBlank()) hint else if (secret) "•".repeat(value.length.coerceAtMost(16)) else value, color = if (value.isBlank()) LoginMuted else Ivory, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
    }
    val canvasDensity = LocalDensity.current
    if (editing) Dialog(onDismissRequest = { editing = false; draft = ""; keyboard?.hide(); returnFocus.requestFocus() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CompositionLocalProvider(LocalDensity provides canvasDensity) {
            Column(Modifier.width(450.dp).background(LoginPanel, TvShape.dialog).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(label, color = Ivory, fontSize = 24.sp, lineHeight = 32.sp)
                BasicTextField(draft, { draft = it.take(256) }, Modifier.fillMaxWidth().focusRequester(focus).background(LoginInk, RoundedCornerShape(10.dp)).padding(14.dp),
                    textStyle = TextStyle(color = Ivory, fontSize = 19.sp), singleLine = true, cursorBrush = SolidColor(Ivory),
                    visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { change(draft); draft = ""; editing = false; keyboard?.hide(); returnFocus.requestFocus() }))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LoginButton(Modifier.weight(1f), onClick = { draft = ""; editing = false; keyboard?.hide(); returnFocus.requestFocus() }) { Text("取消", fontSize = 14.sp) }
                    LoginButton(Modifier.weight(1f), primary = true, onClick = { change(draft); draft = ""; editing = false; keyboard?.hide(); returnFocus.requestFocus() }) { Text("完成", fontSize = 14.sp) }
                }
            }
            LaunchedEffect(Unit) { focus.requestFocus(); keyboard?.show() }
        }
    }
}

@Composable private fun BoxScope.LoginDiscovery(state: TvLogin, discovery: NasDiscovery) {
    val initial = remember { FocusRequester() }
    val services = discovery.services
    LaunchedEffect(services.isNotEmpty()) { initial.requestFocus() }
    Column(Modifier.align(Alignment.Center).width(560.dp)) {
        LoginTitle("查找同一网络的 NAS", "电视与 NAS 需要连接在同一网络")
        Column(Modifier.padding(top = 20.dp).height(216.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (services.isEmpty()) Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                LoginIcon("nas", Modifier.size(40.dp), LoginMuted)
                Text(if (discovery.searching) "正在查找附近的 NAS…" else "暂未发现 NAS", color = Ivory, fontSize = 20.sp, modifier = Modifier.padding(top = 16.dp))
                Text(discovery.status, color = LoginMuted, fontSize = 14.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 8.dp))
            }
            services.forEachIndexed { index, (name, address) ->
                LoginButton(Modifier.fillMaxWidth().height(72.dp).then(if (index == 0) Modifier.focusRequester(initial) else Modifier), onClick = { state.select(name, address) }) {
                    LoginIcon("nas", Modifier.size(26.dp)); Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name, fontSize = 16.sp, lineHeight = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(NasAddress.authority(address), color = LoginMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text("已发现  ›", fontSize = 12.sp)
                }
            }
        }
        Text("发现服务器后仍需完成电视授权", color = LoginMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LoginButton(if (services.isEmpty()) Modifier.focusRequester(initial) else Modifier, onClick = { discovery.start() }) { Text("重新搜索", fontSize = 14.sp) }
            LoginButton(onClick = { state.open(LoginPage.MANUAL) }) { Text("手动添加", fontSize = 14.sp) }
        }
    }
}

@Composable private fun BoxScope.LoginHistory(state: TvLogin) {
    val initial = remember { FocusRequester() }; InitialFocus(initial)
    Column(Modifier.align(Alignment.Center).width(560.dp)) {
        LoginTitle("最近连接的 NAS", "选择熟悉的音乐库，重新完成电视授权")
        Column(Modifier.padding(top = 20.dp).height(232.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.recent.forEachIndexed { index, device ->
                LoginButton(Modifier.fillMaxWidth().height(84.dp).then(if (index == 0) Modifier.focusRequester(initial) else Modifier), onClick = { state.select(device.name, device.address, device.username) }) {
                    LoginIcon("nas", Modifier.size(26.dp)); Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) {
                        Text(device.name, fontSize = 16.sp, lineHeight = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(device.address, color = LoginMuted, fontSize = 12.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(device.username.ifBlank { "手机授权" }, color = LoginMuted, fontSize = 12.sp)
                    }
                    Text(SimpleDateFormat("MM/dd HH:mm", Locale.CHINA).format(Date(device.lastLogin)) + "  ›", fontSize = 12.sp)
                }
            }
            if (state.recent.isEmpty()) Text("暂无登录记录", color = LoginMuted, fontSize = 18.sp)
        }
        Text("只保存连接记录，不保存密码", color = LoginMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
        LoginButton(Modifier.padding(top = 12.dp).then(if (state.recent.isEmpty()) Modifier.focusRequester(initial) else Modifier), onClick = { state.back() }) { Text("返回连接方式", fontSize = 14.sp) }
    }
}

@Composable private fun BoxScope.LoginQr(state: TvLogin) {
    val initial = remember { FocusRequester() }; InitialFocus(initial, !state.busy)
    Row(Modifier.align(Alignment.Center).width(760.dp), horizontalArrangement = Arrangement.spacedBy(60.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            LoginIcon("brand", Modifier.size(52.dp), Primary)
            Spacer(Modifier.height(20.dp))
            LoginTitle("手机扫码授权", "使用微信或手机相机扫码\n由管理员在 NAS 确认电视授权")
            StatusPill(if (state.qrUrl.isNotBlank()) "NAS 已连接" else "正在核对 NAS", if (state.qrUrl.isNotBlank()) Success else LoginMuted,
                Modifier.padding(top = 24.dp))
            Text(state.serverName.ifBlank { "正在连接 NAS…" }, color = Ivory, fontSize = 18.sp, lineHeight = 24.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
            Text("核对授权码", color = LoginMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 16.dp))
            Text(state.authorizationCode.ifBlank { "—" }, color = Ivory, fontSize = 28.sp, lineHeight = 38.sp, letterSpacing = 4.sp)
            LoginButton(Modifier.padding(top = 16.dp), onClick = { state.open(LoginPage.MANUAL, forQr = true) }) { Text("更换 NAS", fontSize = 14.sp) }
        }
        Column(Modifier.width(300.dp).background(LoginPanel, TvShape.card).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(204.dp).clip(TvShape.control).background(Color.White).padding(8.dp), contentAlignment = Alignment.Center) {
                if (state.qrUrl.isNotBlank()) Qr(state.qrUrl, Modifier.fillMaxSize())
                else Text(if (state.busy) "正在生成二维码…" else "二维码不可用", color = LoginInk, fontSize = 16.sp)
            }
            Text(if (state.remaining > 0) "等待手机确认" else "等待生成或刷新", color = Ivory, fontSize = 16.sp, modifier = Modifier.padding(top = 16.dp))
            Text("有效期 ${state.remaining / 60}:${(state.remaining % 60).toString().padStart(2, '0')}", color = LoginMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            LoginButton(Modifier.padding(top = 16.dp).focusRequester(initial), enabled = !state.busy, onClick = { state.startQr() }) { Text("刷新二维码", fontSize = 14.sp) }
        }
    }
}

@Composable private fun LoginIcon(kind: String, modifier: Modifier, color: Color = Color.Unspecified) {
    val tint = if (color == Color.Unspecified) androidx.tv.material3.LocalContentColor.current else color
    if (kind == "brand") {
        HomeGlyph(kind, modifier, tint)
        return
    }
    Canvas(modifier) {
        val unit = size.width / 24f
        fun line(x: Float, y: Float, a: Float, b: Float) = drawLine(tint, Offset(x * unit, y * unit), Offset(a * unit, b * unit), 1.4f * unit, StrokeCap.Round)
        when (kind) {
            "nas" -> listOf(3f, 14f).forEach { y -> drawRoundRect(tint, Offset(3 * unit, y * unit), Size(18 * unit, 7 * unit), androidx.compose.ui.geometry.CornerRadius(2 * unit), style = Stroke(1.4f * unit)); line(6f, y + 3.5f, 11f, y + 3.5f); drawCircle(tint, unit, Offset(17 * unit, (y + 3.5f) * unit)) }
            "user" -> { drawCircle(tint, 4 * unit, Offset(12 * unit, 7 * unit), style = Stroke(1.4f * unit)); drawArc(tint, 180f, 180f, false, Offset(4 * unit, 13 * unit), Size(16 * unit, 16 * unit), style = Stroke(1.4f * unit)) }
            "eye", "eyeOff" -> { drawOval(tint, Offset(2 * unit, 6 * unit), Size(20 * unit, 12 * unit), style = Stroke(1.4f * unit)); drawCircle(tint, 3 * unit, Offset(12 * unit, 12 * unit), style = Stroke(1.4f * unit)); if (kind == "eyeOff") line(3f, 3f, 21f, 21f) }
            else -> { listOf(Offset(3f, 3f), Offset(15f, 3f), Offset(3f, 15f)).forEach { drawRect(tint, Offset(it.x * unit, it.y * unit), Size(6 * unit, 6 * unit), style = Stroke(1.4f * unit)) }; line(16f, 15f, 16f, 21f); line(16f, 21f, 21f, 21f); line(20f, 15f, 21f, 15f) }
        }
    }
}
