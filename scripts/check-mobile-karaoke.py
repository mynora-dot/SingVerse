#!/usr/bin/env python3
"""UI acceptance using disposable NAS fixtures and an isolated emulator package.

A zero host microphone level remains a failure after the rest of the UI flow runs.
This script never injects PCM into the product AudioRecord input.
"""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import urllib.parse
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--suite', choices=('flow', 'visual'), default='flow')
parser.add_argument('--nas', default='http://10.0.2.2:18181')
parser.add_argument('--device', default='emulator-5554')
parser.add_argument('--voice-sample', type=Path, required=True)
args = parser.parse_args()
endpoint = urllib.parse.urlsplit(args.nas)
if endpoint.hostname != '10.0.2.2' or endpoint.port != 18181:
    parser.error('Use the disposable emulator host NAS on 10.0.2.2:18181, never a daily NAS.')
if not args.voice_sample.is_file():
    parser.error('Provide the original generated host voice sample.')
repo = Path(__file__).resolve().parents[1]
artifacts = repo / 'services/singverse-mobile/build/reports/karaoke-emulator'
if args.suite == 'visual': artifacts = artifacts / 'visual'
artifacts.mkdir(parents=True, exist_ok=True)
package = 'com.mynora.singverse.mobile.e2e'
video = None
video_index = 0
remote_video = f'/sdcard/singverse-karaoke-{os.getpid()}.mp4'
summary = {'suite': args.suite, 'uiFlowCompleted': False, 'hostMicrophonePeak': None}
original_settings = {}


def adb(*options, output=None, check=True):
    return subprocess.run(['adb', '-s', args.device, *options], check=check,
                          stdout=output or subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30)


def screenshot(name):
    with (artifacts / (name + '.png')).open('wb') as output:
        adb('exec-out', 'screencap', '-p', output=output)


def finish_video():
    global video
    if video is None:
        return
    if video.poll() is None:
        adb('shell', 'pkill', '-INT', '-f', remote_video, check=False)
    video.wait(timeout=15)
    name = 'flow.mp4' if video_index == 1 else f'flow-{video_index}.mp4'
    adb('pull', remote_video, str(artifacts / name))
    video = None


def check_video():
    global video, video_index
    if video is not None and video.poll() is not None:
        finish_video()
    if video is None:
        video_index += 1
        video = subprocess.Popen(['adb', '-s', args.device, 'shell', 'screenrecord', '--time-limit', '180',
                                  '--size', '720x1606', remote_video], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def export_document():
    end = time.monotonic() + 45
    while time.monotonic() < end:
        adb('shell', 'uiautomator', 'dump', '/sdcard/karaoke-export.xml', check=False)
        result = subprocess.run(['adb', '-s', args.device, 'exec-out', 'cat', '/sdcard/karaoke-export.xml'],
                                capture_output=True, timeout=10)
        try:
            tree = ET.fromstring(result.stdout)
        except ET.ParseError:
            time.sleep(1)
            continue
        buttons = [node for node in tree.iter('node') if node.get('text', '').lower() in ('save', '保存')
                   or 'action_menu_save' in node.get('resource-id', '')]
        if buttons:
            x1, y1, x2, y2 = map(int, re.findall(r'\d+', buttons[-1].get('bounds')))
            screenshot('13-system-export')
            adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))
            return
        time.sleep(1)
    raise RuntimeError('Android export Save button missing; export is unverified.')


def deny_microphone():
    end = time.monotonic() + 30
    while time.monotonic() < end:
        adb('shell', 'uiautomator', 'dump', '/sdcard/karaoke-permission.xml', check=False)
        result = subprocess.run(['adb', '-s', args.device, 'exec-out', 'cat', '/sdcard/karaoke-permission.xml'], capture_output=True, timeout=10)
        try:
            tree = ET.fromstring(result.stdout)
        except ET.ParseError:
            time.sleep(1)
            continue
        deny = next((node for node in tree.iter('node') if node.get('resource-id', '').endswith('permission_deny_button')), None)
        if deny is not None:
            x1, y1, x2, y2 = map(int, re.findall(r'\d+', deny.get('bounds')))
            screenshot('19-microphone-denial')
            adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
            return
        time.sleep(1)
    raise RuntimeError('Actual Android microphone denial dialog missing.')


adb('emu', 'avd', 'hostmicon')
adb('shell', 'am', 'force-stop', package, check=False)
adb('shell', 'pm', 'revoke', package, 'android.permission.RECORD_AUDIO', check=False)
adb('shell', 'run-as', package, 'rm', '-f', 'shared_prefs/mic-permission.xml', check=False)
environment = os.environ.copy()
environment['ORG_GRADLE_PROJECT_singverseE2e'] = 'true'
process = subprocess.Popen(['flutter', '--no-version-check', 'test', 'integration_test/karaoke_' + args.suite + '_test.dart',
                            '-d', args.device, '--dart-define=TEST_NAS=' + args.nas],
                           cwd=repo / 'services/singverse-mobile', env=environment, stdout=subprocess.PIPE,
                           stderr=subprocess.STDOUT, text=True, bufsize=1)
try:
    with (artifacts / 'integration.log').open('w') as log:
        for line in process.stdout:
            log.write(line)
            log.flush()
            if 'KARAOKE_SCREEN:' in line:
                check_video()
                screenshot(re.search(r'KARAOKE_SCREEN:([\w-]+)', line).group(1))
            elif 'KARAOKE_DENY_MIC' in line:
                deny_microphone()
            elif 'KARAOKE_MIC_GRANT' in line:
                adb('shell', 'pm', 'grant', package, 'android.permission.RECORD_AUDIO')
                adb('shell', 'pm', 'grant', package, 'android.permission.POST_NOTIFICATIONS')
            elif 'KARAOKE_HOST_MIC_PEAK:' in line:
                summary['hostMicrophonePeak'] = float(line.split('KARAOKE_HOST_MIC_PEAK:')[-1].strip())
            elif 'KARAOKE_HOST_VOICE' in line and shutil.which('afplay'):
                subprocess.Popen(['afplay', str(args.voice_sample.resolve())], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            elif any(marker in line for marker in ('KARAOKE_LARGE_FONT', 'KARAOKE_NORMAL_FONT', 'KARAOKE_LANDSCAPE', 'KARAOKE_PORTRAIT')):
                for key in ('font_scale', 'accelerometer_rotation', 'user_rotation'):
                    if key not in original_settings:
                        original_settings[key] = subprocess.check_output(['adb', '-s', args.device, 'shell', 'settings', 'get', 'system', key], text=True).strip()
                if 'KARAOKE_LARGE_FONT' in line: adb('shell', 'settings', 'put', 'system', 'font_scale', '1.8')
                if 'KARAOKE_NORMAL_FONT' in line: adb('shell', 'settings', 'put', 'system', 'font_scale', '1.0')
                if 'KARAOKE_LANDSCAPE' in line or 'KARAOKE_PORTRAIT' in line:
                    adb('shell', 'settings', 'put', 'system', 'accelerometer_rotation', '0')
                    adb('shell', 'settings', 'put', 'system', 'user_rotation', '1' if 'KARAOKE_LANDSCAPE' in line else '0')
            elif 'KARAOKE_RETURN_NOTIFICATION' in line:
                adb('shell', 'input', 'keyevent', '3')
                adb('shell', 'cmd', 'statusbar', 'expand-notifications')
                end = time.monotonic() + 30
                found = False
                while time.monotonic() < end:
                    adb('shell', 'uiautomator', 'dump', '/sdcard/karaoke-notification.xml', check=False)
                    raw = subprocess.run(['adb', '-s', args.device, 'exec-out', 'cat', '/sdcard/karaoke-notification.xml'], capture_output=True, timeout=10)
                    try:
                        tree = ET.fromstring(raw.stdout)
                    except ET.ParseError:
                        time.sleep(1)
                        continue
                    node = next((node for node in tree.iter('node') if 'SingVerse' in node.get('text', '') and '录唱' in node.get('text', '')), None)
                    if node is not None:
                        screenshot('20-background-recording-notification')
                        x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
                        adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
                        found = True
                        break
                    time.sleep(1)
                if not found:
                    raise RuntimeError('Microphone foreground notification missing.')
            elif 'KARAOKE_LOCK' in line:
                adb('shell', 'input', 'keyevent', '26')
                time.sleep(3)
                screenshot('14-locked-recording')
                adb('shell', 'input', 'keyevent', '26')
                adb('shell', 'input', 'keyevent', '82')
            elif 'KARAOKE_WORK:' in line:
                identifier = re.search(r'KARAOKE_WORK:([a-f0-9-]{36})', line).group(1)
                for name in ('mix.m4a', 'voice.wav'):
                    with (artifacts / name).open('wb') as output:
                        adb('exec-out', 'run-as', package, 'cat', f'files/karaoke/{identifier}/{name}', output=output)
            elif 'KARAOKE_EXPORT' in line:
                export_document()
            elif 'KARAOKE_UI_FLOW_PASS:' in line:
                summary['uiFlowCompleted'] = True
    summary['exitCode'] = process.wait()
finally:
    if process.poll() is None:
        process.terminate()
        process.wait(timeout=15)
        # Only the dedicated disposable package is stopped; in-progress test files remain recoverable.
        adb('shell', 'am', 'force-stop', package, check=False)
    finish_video()
    for key, value in original_settings.items():
        if value == 'null': adb('shell', 'settings', 'delete', 'system', key, check=False)
        else: adb('shell', 'settings', 'put', 'system', key, value, check=False)
    (artifacts / 'acceptance.json').write_text(json.dumps(summary, indent=2))
print(json.dumps(summary))
raise SystemExit(summary.get('exitCode', 1))
