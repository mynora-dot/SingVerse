from dataclasses import replace
import unittest
from singverse_compute.domain.transcoding import Client, Media, Stream, decide

class PlaybackPolicyTest(unittest.TestCase):
    def test_cost_order_and_constraints(self):
        video=Stream('stream:0',0,'video','h264',width=1920,height=1080,fps=25)
        audio=Stream('stream:1',1,'audio','aac',channels=2)
        source=Media('mp4',120,4000000,(video,audio)); client=Client()
        self.assertEqual(decide(source,client).mode,'DIRECT')
        self.assertEqual(decide(replace(source,container='mkv'),client).mode,'REMUX')
        self.assertEqual(decide(replace(source,streams=(video,replace(audio,codec='dts'))),client).mode,'AUDIO_TRANSCODE')
        for modified in [replace(video,codec='hevc'),replace(video,bit_depth=10),replace(video,hdr='HDR10'),replace(video,width=3840),replace(video,fps=60)]:
            self.assertEqual(decide(replace(source,streams=(modified,audio)),client).mode,'TRANSCODE')
        self.assertEqual(decide(source,client,True).reasons,('SUBTITLE_BURN_IN',))
        self.assertEqual(decide(source,replace(client,max_bitrate=1000000)).mode,'TRANSCODE')

    def test_platform_asset_lock_covers_native_targets(self):
        import json
        from pathlib import Path
        lock=json.loads((Path(__file__).resolve().parents[2]/'tools/jellyfin-ffmpeg.lock.json').read_text())
        self.assertEqual(set(lock['assets']),{'Darwin-arm64','Darwin-x86_64','Linux-x86_64','Linux-aarch64','Windows-AMD64'})
        for name,digest in lock['assets'].values():
            self.assertIn(lock['version'],name)
            self.assertRegex(digest,r'^[a-f0-9]{64}$')

    def test_hdr_display_does_not_imply_dolby_vision_profile_support(self):
        video=Stream('stream:0',0,'video','hevc',width=1920,height=1080,fps=25,bit_depth=10,hdr='DOVI',dv_profile=5,dv_rpu=True)
        media=Media('mp4',120,4000000,(video,))
        client=Client(video=('hevc',),hdr=True,max_bit_depth=10)
        self.assertEqual(decide(media,client).mode,'TRANSCODE')
        self.assertEqual(decide(media,replace(client,dovi_profiles=(5,),dovi_compatibility=(0,))).mode,'DIRECT')
        self.assertEqual(decide(replace(media,streams=(replace(video,hdr='HLG'),)),replace(client,hdr_formats=('HDR10',))).mode,'TRANSCODE')
