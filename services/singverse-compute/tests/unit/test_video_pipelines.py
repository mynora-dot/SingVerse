"""Pipeline contracts: CPU boundaries, unsafe HDR rejection and fixed hardware graphs."""
from dataclasses import replace
import unittest
from singverse_compute.domain.transcoding import Stream, Target
from singverse_compute.infrastructure.transcoding.pipelines import candidates, copyback_candidate, dimensions, software_filters
from singverse_compute.core.errors import ComputeError

FEATURES={name:True for name in ('tonemapx','zscale','tonemap','scale_cuda','tonemap_cuda','yadif_cuda',
 'scale_vt','tonemap_videotoolbox','bwdif_videotoolbox','scale_vaapi','tonemap_vaapi','deinterlace_vaapi',
 'vpp_qsv','tonemap_opencl','scale_d3d11','scale_rkrga')}
class PipelinePolicyTest(unittest.TestCase):
    def setUp(self):
        self.video=Stream('stream:0',0,'video','hevc',width=3840,height=2160,fps=60,bit_depth=10)
        self.target=Target(width=1920,height=1080)
    def test_all_vendor_graphs_and_subtitle_cpu_boundary(self):
        for backend in ('qsv','vaapi','nvenc','amf','videotoolbox','rkmpp'):
            with self.subTest(backend=backend):
                plans=candidates(backend,self.video,self.target,FEATURES)
                self.assertTrue(plans)
                for plan in plans:
                    self.assertTrue(plan.hardware_decode);self.assertTrue(plan.zero_copy)
                    self.assertNotIn('hwdownload',','.join(plan.filters))
                self.assertEqual(candidates(backend,self.video,replace(self.target,burn_subtitle=True),FEATURES),[])
                self.assertFalse(copyback_candidate(backend,self.video).zero_copy)
    def test_hdr_and_dovi_are_never_treated_as_ordinary_sdr(self):
        hdr=replace(self.video,hdr='HDR10')
        for backend in ('qsv','vaapi','nvenc','amf','videotoolbox','rkmpp'):
            self.assertTrue(any(p.gpu_tone_map for p in candidates(backend,hdr,self.target,FEATURES)))
        dovi=replace(hdr,hdr='DOVI',dv_profile=5,dv_rpu=True)
        self.assertEqual(candidates('qsv',dovi,self.target,FEATURES),[])
        self.assertIsNone(copyback_candidate('qsv',dovi))
        self.assertTrue(any('apply_dovi=true' in f for f in software_filters(dovi,self.target,FEATURES)))
        for invalid in (replace(dovi,dv_profile=7),replace(dovi,dv_el=True),replace(dovi,dv_rpu=False)):
            with self.assertRaises(ComputeError):software_filters(invalid,self.target,FEATURES)
        with self.assertRaises(ComputeError):software_filters(dovi,self.target,{'zscale':True,'tonemap':True})
    def test_h264_high10_deinterlace_and_size(self):
        high10=replace(self.video,codec='h264')
        self.assertEqual(candidates('nvenc',high10,self.target,FEATURES),[])
        self.assertIsNone(copyback_candidate('nvenc',high10))
        self.assertTrue(candidates('videotoolbox',high10,self.target,FEATURES))
        interlaced=replace(self.video,interlaced=True)
        self.assertIn('bwdif',','.join(candidates('videotoolbox',interlaced,self.target,FEATURES)[0].filters))
        self.assertEqual(dimensions(self.video,self.target),(1920,1080))
        self.assertEqual(dimensions(replace(self.video,width=160,height=90),self.target),(160,90))
        self.assertEqual(candidates('videotoolbox',replace(self.video,rotation=90),self.target,FEATURES),[])

    def test_child_memory_budget_stops_only_the_media_process(self):
        import sys
        from singverse_compute.infrastructure.transcoding.process import run
        observed=[]
        with self.assertRaises(ComputeError) as caught:
            run([sys.executable,'-c','import time; payload=bytearray(96*1024*1024); time.sleep(8)'],
                timeout=6,memory_limit=32*1024*1024,metrics=observed.append)
        self.assertIn('memory budget',caught.exception.message)
        self.assertTrue(observed)
        self.assertGreater(max(observed),32*1024*1024)

    def test_actual_source_trial_rejects_gpu_claims_when_execution_fails(self):
        import threading
        from unittest.mock import patch
        from singverse_compute.domain.transcoding import Media
        from singverse_compute.infrastructure.transcoding.pipelines import PipelineSelector
        class Adapter:
            def features(self):return FEATURES
            def prefix(self,backend):return []
            def upload(self,backend):return ''
        selector=PipelineSelector('ffmpeg',Adapter())
        with patch('singverse_compute.infrastructure.transcoding.pipelines.run',side_effect=ComputeError(422,'Device unavailable')):
            plan=selector.choose('fixture.mkv',False,Media('mkv',10,100000,(self.video,)),self.target,'videotoolbox',threading.Event())
        self.assertFalse(plan.hardware_decode);self.assertFalse(plan.zero_copy)
        self.assertEqual(selector.verified('videotoolbox'),[])
