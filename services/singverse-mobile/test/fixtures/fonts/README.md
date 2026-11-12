# 中文组件测试字体

`UiCapture.ttf` 是 Noto Sans SC 的测试字形子集，仅由测试代码通过 `FontLoader` 加载，不在 `pubspec.yaml` 注册，不随 Android 应用打包。

来源为本机已安装的 Noto Sans SC，字体内记录版本 `Version 0.00 July 30, 2017`、Adobe Systems Incorporated 版权及 SIL Open Font License 1.1。字体来源页记录为 `http://www.google.com/get/noto/`。子集重新命名为 `SingVerse Test CJK` / `UiCapture`，保留原字体版权、作者和授权记录，完整许可证见 `OFL.txt`。

子集包含生成时手机工程 `lib/`、`test/`、`integration_test/` 中的字符及 ASCII，使用 fontTools subset 生成。新增中文视觉用例需要新字形时，可从同许可证的完整 Noto Sans SC 重新提取。测试中统一注册到 `UiCapture`、Material 默认 `Roboto` 及测试引擎 fallback `Ahem`，以便布局和截图共享字形度量；不读取宿主苹果字体或使用绝对字体路径。

## 2026-11-12 补全记录

本轮按三个目录中全部 Dart 源码的非空白字符及可打印 ASCII（U+0020–U+007E）检查 `cmap`，发现原子集缺少 43 个字符：

```text
介冲则创制即反图外头封建必截控日昨月松框活然状白盛真简缓编至舞若观解让资转遍钮际隐须；
```

从本机同版本、同版权及 OFL 1.1 的完整 Noto Sans SC 重新提取，保留原子集已有的全部码位。更新后 `cmap` 从 551 个码位增加到 594 个，覆盖当前源码所需的 587 个码位，缺字为 0；包含 596 个字形，文件 106,532 字节。字体仅用于测试，没有修改应用字体或 `pubspec.yaml`。

本轮使用 fontTools 4.60.2。完整源文件 SHA-256 为 `a0af66489baf1f306732ff16da58d86c342e0b3b4b3674079ee2a9b0aee23258`；补全子集 SHA-256 为 `f5da99dcf7a836090f849ddb1d7dd2c6f591128d52b90de2bf4e119ab4fa7911`。源字体的版权、作者、厂商、来源与许可证 name records 均原样保留，包括 legacy records，`OFL.txt` 保持原内容。

## 重新生成

在手机工程目录执行，`SINGVERSE_TEST_FONT` 指向合法获得、许可证相同的完整源字体。命令只读取本机字体，不下载或安装字体；保留旧子集的码位，避免已有截图丢字。控制字符及换行没有视觉字形，不纳入覆盖要求。

```sh
SINGVERSE_TEST_FONT=/path/to/NotoSansSC.ttf python3 - <<'PY'
import os
from pathlib import Path
from fontTools import subset
from fontTools.ttLib import TTFont

target = Path('test/fixtures/fonts/UiCapture.ttf')
previous = TTFont(target, recalcTimestamp=False)
required = set(range(0x20, 0x7f))
for directory in ('lib', 'test', 'integration_test'):
    for path in Path(directory).rglob('*.dart'):
        required.update(ord(c) for c in path.read_text() if not c.isspace())
needed = required | set(previous.getBestCmap())
font = TTFont(os.environ['SINGVERSE_TEST_FONT'], recalcTimestamp=False)
assert needed <= font.getBestCmap().keys(), '完整源字体也缺少所需字形'
metadata_ids = {0, 8, 9, 11, 13, 14}
def metadata(face):
    return {
        (n.nameID, n.platformID, n.platEncID, n.langID): n.string
        for n in face['name'].names if n.nameID in metadata_ids
    }
original_metadata = metadata(font)
options = subset.Options()
options.name_IDs = ['*']
options.name_languages = ['*']
options.name_legacy = True
options.notdef_outline = True
options.recommended_glyphs = True
subsetter = subset.Subsetter(options=options)
subsetter.populate(unicodes=needed)
subsetter.subset(font)
names = {
    1: 'SingVerse Test CJK', 3: 'UiCapture:Version 0.00',
    4: 'SingVerse Test CJK', 6: 'UiCapture', 16: 'SingVerse Test CJK',
}
for record in font['name'].names:
    if record.nameID in names:
        record.string = names[record.nameID].encode(
            record.getEncoding(), errors='replace')
font.save(target)
updated = TTFont(target, recalcTimestamp=False)
assert needed <= updated.getBestCmap().keys()
assert metadata(updated) == original_metadata
print('required:', len(required), 'cmap:', len(updated.getBestCmap()),
      'missing:', len(required - updated.getBestCmap().keys()))
PY
```
