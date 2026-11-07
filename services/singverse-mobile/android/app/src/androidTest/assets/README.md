# 受控播放样本

`controlled-tone.aac` 只用于 Android 仪器测试，不随正式/普通 debug 应用打包。它是 2 秒、440Hz、48kHz 单声道的程序生成音调，无用户媒体或第三方录音。

从仓库根目录用既有工具重建：

```sh
.tools/jellyfin-ffmpeg/ffmpeg -nostdin -hide_banner -loglevel error \
  -f lavfi -i sine=frequency=440:sample_rate=48000:duration=2 \
  -c:a aac -b:a 32k -f adts \
  services/singverse-mobile/android/app/src/androidTest/assets/controlled-tone.aac
```

测试服务器把它作为 EVENT HLS 的三个受控片段，并逐请求检查 NAS Cookie。WAV 与不支持内容在测试内存中生成。
