"""Exercise cancellation and priority using an intentionally blocked execution port."""
from dataclasses import replace
from pathlib import Path
import tempfile
import threading
import time
import unittest
from singverse_compute.application.transcoding import Transcoding
from singverse_compute.core.config import Settings
from singverse_compute.core.errors import ComputeError
from singverse_compute.domain.transcoding import Target,Media,Stream
from singverse_compute.infrastructure.filesystem import RootedMediaPaths
from singverse_compute.infrastructure.security import InternalAuth

class LifecycleTest(unittest.TestCase):
    def test_preemption_lease_storage_and_restart(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);media=root/'media';media.mkdir();source=media/'source.mkv';source.write_bytes(b'fixture')
            auth=InternalAuth(root/'token');auth.initialize()
            entered=threading.Event()
            class Probe:
                def read(self,*args):return Media('mkv',100,10000,(Stream('stream:0',0,'video','h264',width=16,height=16),))
            class Backends:
                def select(self, codec='h264'):return 'software'
            class Encoder:
                def execute(self,source,network,media,target,directory,cancel,progress,backend):
                    entered.set();cancel.wait(10)
                    if cancel.is_set():raise ComputeError(409,'Cancelled')
                    return []
            config=Settings((media,),root/'token',transcode_root=root/'cache',minimum_free_bytes=0)
            service=Transcoding(config,RootedMediaPaths((media,)),auth,Probe(),Encoder(),Backends(),None)
            try:
                offline='00000000-0000-0000-0000-000000000001'
                live='00000000-0000-0000-0000-000000000002'
                service.create(offline,str(source),None,'f',Target(purpose='OFFLINE'))
                self.assertTrue(entered.wait(2))
                with self.assertRaises(ComputeError) as caught:service.create(live,str(source),None,'f',Target())
                self.assertEqual(caught.exception.status,429)
                for _ in range(100):
                    if service.view(offline)['state']=='CANCELLED':break
                    time.sleep(.02)
                self.assertEqual(service.view(offline)['state'],'CANCELLED')
                service.create(live,str(source),None,'f',Target())
                service.get(live).renewed=time.time()-121
                service.collect()
                self.assertTrue(service.get(live).cancel.is_set())
            finally:service.close()
            restarted=Transcoding(config,RootedMediaPaths((media,)),auth,Probe(),Encoder(),Backends(),None)
            self.assertEqual(restarted.view(live)['state'],'CANCELLED')
            task=restarted.get(live);task.finished=task.renewed=time.time()-601
            restarted.collect()
            self.assertFalse((root/'cache'/live).exists())
            self.assertTrue((root/'cache'/offline).exists())
            restarted.close()
