#!/usr/bin/env python3
"""Exercise the production update dispatcher without Android or network access."""
from pathlib import Path
import re
import subprocess
import tempfile

source = (Path(__file__).resolve().parents[1] / 'app/src/main/java/org/thunderdog/challegram/util/AppUpdater.java').read_text()
method = re.search(r'  public void checkForUpdates \(\) \{.*?\n  }', source, re.S)
assert method
flag = re.search(r'public static final boolean ENABLED = (?:true|false);', source)
probe = '''class Probe {
  FLAG
  interface State { int NONE=0, AVAILABLE=2; }
  int state, requests; boolean channel;
  boolean preferTelegramChannelFlow() { return channel; }
  void checkForTelegramChannelUpdates() { requests++; }
  void checkForGooglePlayUpdates() { requests++; }
  void offerUpdate() { requests++; }
  METHOD
  public static void main(String[] args) {
    Probe p = new Probe();
    for (boolean channel : new boolean[]{false,true}) {
      p.channel=channel;
      for (int state : new int[]{State.NONE,State.AVAILABLE}) {
        p.state=state; p.checkForUpdates();
        if (p.requests!=0 || p.state!=state) throw new AssertionError("updater still active");
      }
    }
  }
}'''.replace('FLAG', flag.group() if flag else '').replace('METHOD', method.group())
with tempfile.TemporaryDirectory() as tmp:
    f = Path(tmp) / 'Probe.java'; f.write_text(probe)
    subprocess.run(['javac', str(f)], check=True)
    subprocess.run(['java', '-cp', tmp, 'Probe'], check=True)
print('PASS: no Play or channel checks and no update offer')
