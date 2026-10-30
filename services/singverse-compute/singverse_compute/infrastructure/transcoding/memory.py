"""Read only this service's child RSS; unavailable metrics are reported as unknown, never fabricated."""
import os
import platform
from pathlib import Path
import subprocess

def resident_bytes(pid):
    try:
        if os.name == 'nt':
            import ctypes
            from ctypes import wintypes
            class Counters(ctypes.Structure):
                _fields_ = [('cb',wintypes.DWORD),('faults',wintypes.DWORD)] + [(name,ctypes.c_size_t) for name in
                    ('peak','working','peakPaged','paged','peakNonPaged','nonPaged','pagefile','peakPagefile')]
            kernel=ctypes.WinDLL('kernel32',use_last_error=True)
            kernel.OpenProcess.argtypes=[wintypes.DWORD,wintypes.BOOL,wintypes.DWORD]
            kernel.OpenProcess.restype=wintypes.HANDLE
            kernel.CloseHandle.argtypes=[wintypes.HANDLE]
            handle=kernel.OpenProcess(0x0400 | 0x0010,False,pid)
            if not handle:return None
            try:
                psapi=ctypes.WinDLL('psapi',use_last_error=True)
                psapi.GetProcessMemoryInfo.argtypes=[wintypes.HANDLE,ctypes.POINTER(Counters),wintypes.DWORD]
                psapi.GetProcessMemoryInfo.restype=wintypes.BOOL
                value=Counters();value.cb=ctypes.sizeof(value)
                return int(value.working) if psapi.GetProcessMemoryInfo(handle,ctypes.byref(value),value.cb) else None
            finally:kernel.CloseHandle(handle)
        if platform.system() == 'Linux':
            with Path(f'/proc/{pid}/status').open() as status:
                for line in status:
                    if line.startswith('VmRSS:'):return int(line.split()[1])*1024
            return None
        if platform.system() == 'Darwin':
            result=subprocess.run(['ps','-o','rss=','-p',str(pid)],stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,timeout=.5)
            return int(result.stdout.strip())*1024 if result.returncode==0 and result.stdout.strip() else None
    except (OSError,ValueError,subprocess.TimeoutExpired):
        return None
    return None
