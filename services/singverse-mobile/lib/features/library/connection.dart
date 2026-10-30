import 'package:flutter/material.dart';

import '../../app/design.dart';
import '../../core/app_state.dart';

class ConnectionPage extends StatefulWidget {
  const ConnectionPage({super.key, required this.state});
  final AppState state;
  @override
  State<ConnectionPage> createState() => _ConnectionPageState();
}

class _ConnectionPageState extends State<ConnectionPage> {
  late final address = TextEditingController(text: widget.state.origin);
  final password = TextEditingController(), code = TextEditingController();
  bool editing = false;
  @override
  void initState() {
    super.initState();
    editing = widget.state.origin.isEmpty;
  }

  @override
  void dispose() {
    address.dispose();
    password.dispose();
    code.dispose();
    super.dispose();
  }

  Future<void> submit() async {
    final state = widget.state;
    if (state.busy) return;
    if (editing) {
      await state.connect(address.text);
      if (state.error == null) setState(() => editing = false);
    } else if (state.mfaRequired) {
      await state.verify(code.text.trim());
      code.clear();
    } else {
      await state.login(password.text);
      password.clear();
    }
    if (mounted && state.authenticated) Navigator.pop(context);
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: widget.state,
    builder: (context, _) {
      final state = widget.state;
      return Scaffold(
        appBar: AppBar(title: const Text('连接 NAS')),
        body: SafeArea(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(20),
            child: AutofillGroup(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  const SizedBox(height: 36),
                  const Icon(Icons.graphic_eq_rounded, size: 64, color: mint),
                  const SizedBox(height: 20),
                  Text(
                    '好歌，就在家里。',
                    style: Theme.of(context).textTheme.headlineLarge,
                  ),
                  const SizedBox(height: 12),
                  const Text('连接你的 SingVerse NAS，把喜欢的歌唱成自己的作品。'),
                  const SizedBox(height: 36),
                  if (editing) ...[
                    TextField(
                      controller: address,
                      keyboardType: TextInputType.url,
                      autocorrect: false,
                      decoration: const InputDecoration(
                        labelText: 'NAS 地址',
                        hintText: 'http://nas.local:18080',
                      ),
                      onSubmitted: (_) => submit(),
                    ),
                    const SizedBox(height: 12),
                    const Text('请输入手机可以访问的 NAS 根地址。'),
                  ] else ...[
                    Card(
                      child: ListTile(
                        leading: const Icon(Icons.dns_outlined),
                        title: const Text('SingVerse · admin'),
                        subtitle: Text(state.origin),
                        trailing: IconButton(
                          tooltip: '修改地址',
                          icon: const Icon(Icons.edit_outlined),
                          onPressed: state.busy
                              ? null
                              : () => setState(() => editing = true),
                        ),
                      ),
                    ),
                    const SizedBox(height: 16),
                    if (state.mfaRequired)
                      TextField(
                        controller: code,
                        autofocus: true,
                        autocorrect: false,
                        decoration: const InputDecoration(labelText: '动态码或恢复码'),
                        onSubmitted: (_) => submit(),
                      )
                    else
                      TextField(
                        controller: password,
                        obscureText: true,
                        autofillHints: const [AutofillHints.password],
                        decoration: const InputDecoration(labelText: '管理员密码'),
                        onSubmitted: (_) => submit(),
                      ),
                  ],
                  if (state.error != null)
                    Notice(
                      '${state.error}${state.retryAfter?.isNotEmpty == true ? '，请在 ${state.retryAfter} 秒后重试' : ''}',
                    ),
                  const SizedBox(height: 24),
                  FilledButton(
                    onPressed: state.busy ? null : submit,
                    child: Text(
                      state.busy
                          ? '正在连接…'
                          : editing
                          ? '验证 NAS'
                          : state.mfaRequired
                          ? '验证并登录'
                          : '登录',
                    ),
                  ),
                  const SizedBox(height: 20),
                  const Text(
                    '歌曲从 NAS 获取，收藏和播放历史保存在此手机。',
                    textAlign: TextAlign.center,
                  ),
                ],
              ),
            ),
          ),
        ),
      );
    },
  );
}
