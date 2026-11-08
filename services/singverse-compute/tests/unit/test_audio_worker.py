"""Real subprocess lifecycle tests without requiring a GPU or loading a model."""
import os
import sys
import threading
import time
import unittest
from unittest.mock import patch
from singverse_compute.core.errors import ComputeError
from singverse_compute.infrastructure.audio_worker import AudioWorker

SCRIPT = '''
import json, os, sys, time
for line in sys.stdin:
    request=json.loads(line)
    if request.get('hang'): time.sleep(30)
    if request.get('crash'): os._exit(1)
    if request.get('finalError'):
        print(json.dumps({'id':request['id'],'ok':False,'error':'HIP error: device failure'}),flush=True)
        sys.exit(1)
    if request.get('error'):
        print(json.dumps({'id':request['id'],'ok':False,'error':'inference failed'}),flush=True)
        continue
    print(json.dumps({'id':request['id'],'event':'progress','frames':1}),flush=True)
    print(json.dumps({'id':request['id'],'ok':True,'result':{'pid':os.getpid(),'key':sys.argv[1]}}),flush=True)
    if request.get('finalResult'): sys.exit(0)
'''


class AudioWorkerTest(unittest.TestCase):
    def worker(self, idle=60, memory=1024**3):
        worker=AudioWorker(lambda key:[sys.executable,'-u','-c',SCRIPT,str(key)],env=os.environ.copy(),
                           memory_limit=memory,idle_seconds=idle)
        self.addCleanup(worker.close)
        return worker

    def test_reuses_process_and_replaces_it_when_model_or_backend_identity_changes(self):
        worker=self.worker();events=[]
        first=worker.request('model-a',{},event=events.append)
        second=worker.request('model-a',{})
        third=worker.request('model-b',{})
        self.assertEqual(first['pid'],second['pid']);self.assertNotEqual(first['pid'],third['pid'])
        self.assertEqual(events[0]['event'],'progress')

    def test_cancel_kills_blocked_process_then_next_job_starts_clean(self):
        worker=self.worker();cancel=threading.Event()
        timer=threading.Timer(.15,cancel.set);timer.start();self.addCleanup(timer.cancel)
        started=time.monotonic()
        with self.assertRaises(ComputeError) as failure:
            worker.request('model',{'hang':True},cancel=cancel)
        self.assertEqual(failure.exception.status,409);self.assertLess(time.monotonic()-started,2)
        self.assertIsNone(worker.handle)
        self.assertIn('pid',worker.request('model',{}))

    def test_crash_and_inference_error_do_not_poison_future_requests(self):
        worker=self.worker()
        for request in ({'crash':True},{'error':True}):
            with self.subTest(request=request),self.assertRaises(ComputeError):worker.request('model',request)
            self.assertIsNone(worker.handle)
            self.assertIn('pid',worker.request('model',{}))

    def test_preemption_terminates_an_active_or_idle_process(self):
        worker=self.worker();first=worker.request('model',{})
        worker.interrupt();self.assertIsNone(worker.handle)
        self.assertNotEqual(first['pid'],worker.request('model',{})['pid'])
        timer=threading.Timer(.15,worker.interrupt);timer.start();self.addCleanup(timer.cancel)
        with self.assertRaises(ComputeError):worker.request('model',{'hang':True})
        self.assertIsNone(worker.handle)

    def test_final_response_is_consumed_when_process_exits_and_errors_restart_cleanly(self):
        worker=self.worker()
        self.assertIn('pid',worker.request('model',{'finalResult':True}))
        # The next request is made after the intentionally short-lived server exits.
        self.assertEqual(worker.handle['process'].wait(timeout=2),0)
        with self.assertRaisesRegex(ComputeError,'Hardware accelerator execution failed'):
            worker.request('model',{'finalError':True})
        self.assertIsNone(worker.handle)
        self.assertIn('pid',worker.request('model',{}))

    def test_memory_and_idle_budgets_release_the_process(self):
        worker=self.worker(idle=.05,memory=100)
        with patch('singverse_compute.infrastructure.audio_worker.resident_bytes',return_value=101):
            with self.assertRaisesRegex(ComputeError,'memory budget'):worker.request('model',{})
        self.assertIsNone(worker.handle)
        worker.memory_limit=1024**3;worker.request('model',{})
        deadline=time.monotonic()+2
        while worker.handle is not None and time.monotonic()<deadline:time.sleep(.02)
        self.assertIsNone(worker.handle)

    def test_overlapping_operations_fail_fast_and_close_prevents_restart(self):
        worker=self.worker();worker.operation.acquire()
        with self.assertRaises(ComputeError) as failure:worker.request('model',{})
        self.assertEqual(failure.exception.status,429)
        worker.operation.release();worker.close()
        with self.assertRaises(ComputeError):worker.request('model',{})
