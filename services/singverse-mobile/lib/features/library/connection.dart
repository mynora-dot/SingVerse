import 'package:flutter/material.dart';

import '../../app/design.dart';
import '../../core/app_state.dart';

Future<void> openConnection(BuildContext context, AppState state) async {
  if (state.taking || state.singingPhase == 'RENDERING') {
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(const SnackBar(content: Text('请先结束录音或等待混音完成，再修改 NAS 连接。')));
    return;
  }
  await Navigator.push(
    context,
    MaterialPageRoute<void>(
      settings: const RouteSettings(name: 'connection'),
      builder: (_) => ConnectionPage(state: state),
    ),
  );
}

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
  bool checked = false;
  bool get locked =>
      widget.state.taking || widget.state.singingPhase == 'RENDERING';
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
    if (state.busy || locked) return;
    if (editing) {
      await state.connect(address.text);
      if (mounted && state.error == null) {
        setState(() {
          editing = false;
          checked = true;
        });
      }
    } else if (state.mfaRequired) {
      await state.verify(code.text.trim());
      code.clear();
      if (mounted && state.authenticated) Navigator.pop(context);
    } else {
      await state.login(password.text);
      password.clear();
      if (mounted && state.authenticated) Navigator.pop(context);
    }
  }

  Future<void> logout() async {
    if (locked) return;
    final yes = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('退出 NAS 登录？'),
        content: const Text('本机播放将停止。收藏、练唱记录和录音作品仍保留在手机上。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('退出登录'),
          ),
        ],
      ),
    );
    if (yes == true && mounted) await uiTask(context, widget.state.logout);
  }

  Widget detail(String label, Widget value) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 12),
    child: LayoutBuilder(
      builder: (context, size) {
        final caption = Text(
          label,
          style: Theme.of(context).textTheme.bodyMedium
              ?.copyWith(color: Theme.of(context).colorScheme.onSurfaceVariant),
        );
        final stacked =
            widget.state.senior ||
            MediaQuery.textScalerOf(context).scale(16) > 22 ||
            size.maxWidth < 280;
        if (stacked) {
          return Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [caption, const SizedBox(height: 8), value],
          );
        }
        return Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            SizedBox(width: 80, child: caption),
            const SizedBox(width: 12),
            Expanded(child: value),
          ],
        );
      },
    ),
  );

  Widget management(
    IconData icon,
    String title,
    String subtitle,
    VoidCallback? action,
  ) => ListTile(
    minTileHeight: widget.state.senior ? 88 : 72,
    leading: Icon(icon, size: 20),
    title: Text(
      title,
      style: Theme.of(context).textTheme.bodyMedium
          ?.copyWith(fontWeight: FontWeight.w500),
    ),
    subtitle: Text(subtitle),
    trailing: const Icon(Icons.chevron_right_rounded, size: 18),
    onTap: action,
  );

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: widget.state,
    builder: (context, _) {
      final state = widget.state;
      return Scaffold(
        appBar: AppBar(title: Text(editing ? '连接 NAS' : 'NAS 连接与账号')),
        body: SafeArea(
          child: ContentWidth(
            child: ListView(
              padding: const EdgeInsets.all(20),
              children: [
                if (editing) ...[
                  Text(
                    '连接你的家庭曲库',
                    style: Theme.of(context).textTheme.headlineSmall,
                  ),
                  const SizedBox(height: 12),
                  Text(
                    '歌曲和歌单都从这里读取。请输入手机可以访问的 NAS 根地址。',
                    style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                      color: Theme.of(context).colorScheme.onSurfaceVariant,
                    ),
                  ),
                  const SizedBox(height: 24),
                  TextField(
                    key: const ValueKey('nas-address-field'),
                    controller: address,
                    keyboardType: TextInputType.url,
                    autocorrect: false,
                    textInputAction: TextInputAction.go,
                    decoration: const InputDecoration(
                      labelText: 'NAS 地址',
                      hintText: 'http://nas.local:18080',
                    ),
                    onSubmitted: (_) => submit(),
                  ),
                  const SizedBox(height: 16),
                  FilledButton(
                    key: const ValueKey('nas-submit'),
                    onPressed: state.busy || locked ? null : submit,
                    child: Text(state.busy ? '正在连接…' : '连接曲库'),
                  ),
                  if (state.origin.isNotEmpty)
                    TextButton(
                      onPressed: () => setState(() {
                        editing = false;
                        address.text = state.origin;
                      }),
                      child: const Text('取消修改'),
                    ),
                ] else ...[
                  Card(
                    key: const ValueKey('nas-status-card'),
                    child: Padding(
                      padding: const EdgeInsets.fromLTRB(16, 16, 16, 4),
                      child: Column(
                        children: [
                          Row(
                            children: [
                              Icon(
                                Icons.dns_outlined,
                                size: 20,
                                color: Theme.of(context)
                                    .colorScheme
                                    .onSurfaceVariant,
                              ),
                              const SizedBox(width: 12),
                              Expanded(
                                child: Column(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    Text(
                                      '家里的曲库',
                                      style: Theme.of(context)
                                          .textTheme
                                          .titleMedium,
                                    ),
                                    const SizedBox(height: 4),
                                    Text(
                                      checked ? '连接检查通过' : 'NAS 地址已保存',
                                      style: Theme.of(context)
                                          .textTheme
                                          .bodySmall
                                          ?.copyWith(
                                            color: Theme.of(context)
                                                .colorScheme
                                                .onSurfaceVariant,
                                          ),
                                    ),
                                  ],
                                ),
                              ),
                            ],
                          ),
                          const SizedBox(height: 16),
                          const Divider(),
                          detail(
                            '曲库地址',
                            SelectableText(
                              state.origin,
                              style: Theme.of(context).textTheme.bodyMedium,
                            ),
                          ),
                          const Divider(),
                          detail(
                            '账号状态',
                            Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(
                                  state.authenticated ? '已登录家庭曲库' : '尚未登录',
                                  style: Theme.of(context).textTheme.bodyMedium,
                                ),
                                if (state.authenticated &&
                                    state.accountName.isNotEmpty)
                                  Text(
                                    state.accountName,
                                    style: Theme.of(context)
                                        .textTheme
                                        .bodySmall,
                                  ),
                              ],
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                  const SizedBox(height: 24),
                  if (state.authenticated) ...[
                    const SectionTitle('连接管理'),
                    const SizedBox(height: 12),
                    Card(
                      child: Column(
                        children: [
                          management(
                            Icons.refresh_rounded,
                            state.busy ? '检查连接中…' : '检查连接',
                            '确认家庭曲库可以正常访问',
                            state.busy || locked
                                ? null
                                : () async {
                                    await state.connect(state.origin);
                                    if (mounted) {
                                      setState(
                                        () => checked = state.error == null,
                                      );
                                    }
                                  },
                          ),
                          const Divider(indent: 16),
                          management(
                            Icons.edit_outlined,
                            '修改 NAS 地址',
                            '收藏和练唱记录按曲库分别保留',
                            state.busy || locked
                                ? null
                                : () => setState(() {
                                    editing = true;
                                    checked = false;
                                  }),
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(height: 24),
                    const SectionTitle('账号'),
                    const SizedBox(height: 12),
                    Card(
                      child: management(
                        Icons.logout_rounded,
                        '退出登录',
                        state.accountName.isEmpty
                            ? '账号已登录'
                            : '当前账号：${state.accountName}',
                        state.busy || locked ? null : logout,
                      ),
                    ),
                  ] else ...[
                    Card(
                      key: const ValueKey('nas-account-form'),
                      child: Padding(
                        padding: const EdgeInsets.all(16),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.stretch,
                          children: [
                            Text(
                              state.mfaRequired ? '验证身份' : '登录曲库',
                              style: Theme.of(context).textTheme.titleLarge,
                            ),
                            const SizedBox(height: 8),
                            Text(
                              state.mfaRequired
                                  ? '输入验证器中的动态码，或使用恢复码。'
                                  : '使用 NAS 管理员 admin 访问家里的歌曲。',
                              style: Theme.of(context).textTheme.bodyMedium
                                  ?.copyWith(
                                    color: Theme.of(context)
                                        .colorScheme
                                        .onSurfaceVariant,
                                  ),
                            ),
                            const SizedBox(height: 16),
                            AutofillGroup(
                              child: state.mfaRequired
                                  ? TextField(
                                      controller: code,
                                      autocorrect: false,
                                      decoration: const InputDecoration(
                                        labelText: '动态码或恢复码',
                                      ),
                                      onSubmitted: (_) => submit(),
                                    )
                                  : TextField(
                                      controller: password,
                                      obscureText: true,
                                      autofillHints: const [
                                        AutofillHints.password,
                                      ],
                                      decoration: const InputDecoration(
                                        labelText: '管理员密码',
                                      ),
                                      onSubmitted: (_) => submit(),
                                    ),
                            ),
                            const SizedBox(height: 16),
                            FilledButton(
                              key: const ValueKey('nas-submit'),
                              onPressed: state.busy || locked ? null : submit,
                              child: Text(
                                state.busy
                                    ? '正在登录…'
                                    : state.mfaRequired
                                    ? '验证并登录'
                                    : '登录',
                              ),
                            ),
                            TextButton(
                              onPressed: state.busy || locked
                                  ? null
                                  : () => setState(() => editing = true),
                              child: const Text('修改 NAS 地址'),
                            ),
                          ],
                        ),
                      ),
                    ),
                  ],
                ],
                if (state.error != null)
                  Notice(
                    '${state.error}${state.retryAfter?.isNotEmpty == true ? '，请在 ${state.retryAfter} 秒后重试' : ''}',
                  ),
                const SizedBox(height: 24),
                Text(
                  '作品和草稿保存在此手机。退出登录或修改地址后，仍可在原曲库的作品中继续回听。',
                  style: Theme.of(context).textTheme.bodySmall?.copyWith(
                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                  ),
                ),
              ],
            ),
          ),
        ),
      );
    },
  );
}
