import json
import os
from pathlib import Path
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
from singverse_compute.core.errors import ComputeError
from singverse_compute.infrastructure.transcoding.probe import MediaProbe


class AudioProbeTest(unittest.TestCase):
    def test_audio_projection_keeps_source_indexes_and_reads_timeline_once(self):
        value={'format':{'format_name':'mov,mp4','duration':'12.5','start_time':'0.02','bit_rate':'192000'},
               'streams':[{'index':0,'codec_type':'video','side_data_list':[{'side_data_type':'DOVI'}]},
                          {'index':2,'codec_type':'audio','codec_name':'aac','channels':2,'tags':{'title':'伴奏','language':'chi'}}]}
        probe=MediaProbe('ffprobe');cancel=threading.Event()
        with patch('singverse_compute.infrastructure.transcoding.probe.run',return_value=json.dumps(value).encode()) as run, \
                patch.object(probe,'_has_dovi_rpu') as video_probe:
            result=probe.read_audio('input',True,cancel=cancel)
        self.assertEqual((result.duration,result.start_time),(12.5,.02))
        self.assertEqual([(s.id,s.index,s.title) for s in result.streams],[('stream:2',2,'伴奏')])
        run.assert_called_once();video_probe.assert_not_called()
        self.assertIn('-select_streams',run.call_args.args[0]);self.assertIs(run.call_args.kwargs['cancel'],cancel)

    @unittest.skipIf(os.name=='nt','POSIX executable fixture')
    def test_cancel_interrupts_stalled_probe_process_promptly(self):
        with tempfile.TemporaryDirectory() as temporary:
            executable=Path(temporary)/'probe'
            executable.write_text(f'#!{sys.executable}\nimport time\ntime.sleep(30)\n')
            executable.chmod(0o700)
            cancel=threading.Event();timer=threading.Timer(.15,cancel.set)
            timer.start()
            try:
                started=time.monotonic()
                with self.assertRaises(ComputeError) as failure:MediaProbe(str(executable)).read_audio('input',cancel=cancel)
                self.assertEqual(failure.exception.status,409)
                self.assertLess(time.monotonic()-started,2)
            finally:timer.cancel()
