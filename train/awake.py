"""Keep Windows from sleeping while a training run goes on (as keep-awake programs do: no setting
is changed; the request ends with this process). python awake.py <log> <last line to wait for>"""
import ctypes
import os
import sys
import time

ES_CONTINUOUS, ES_SYSTEM_REQUIRED = 0x80000000, 0x00000001


def hold():
    if os.name == "nt":
        ctypes.windll.kernel32.SetThreadExecutionState(ES_CONTINUOUS | ES_SYSTEM_REQUIRED)


if __name__ == "__main__":
    log, until = sys.argv[1], sys.argv[2]
    hold()
    while True:
        try:
            if until in open(log, encoding="utf-8").read():
                break
        except OSError:
            pass
        time.sleep(60)
