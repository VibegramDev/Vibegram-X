#!/usr/bin/env python3
"""Direct sticker links have no settings owner; exercise the real preload selection on a JVM."""
from pathlib import Path
import re, subprocess, tempfile

root = Path(__file__).resolve().parents[1]
source = (root / 'app/src/main/java/org/thunderdog/challegram/ui/SettingsStickersController.java').read_text()
setter_match = re.search(r'  public void setArguments \([^)]*SettingsController args\) \{.*?\n  }', source, re.S)
policy_match = re.search(r'new StickersController.Args\(StickersController.MODE_STICKERS, isEmoji\(\), (.*?)\)\.setStickerSets', source)
assert setter_match and policy_match, 'preload selection not found'
setter = setter_match.group().replace('@Nullable ', '')
policy = policy_match.group(1)
probe = '''import java.util.ArrayList;
class TGStickerSetInfo {}
class SettingsController {
  int listeners; ArrayList<TGStickerSetInfo> sets;
  ArrayList<TGStickerSetInfo> getStickerSets(boolean emoji) {return sets;}
  void addStickerSetListener(boolean emoji, Probe listener) {listeners++;}
}
class Base {
  SettingsController owner;
  void setArguments(SettingsController value) {owner=value;}
  SettingsController getArguments() {return owner;}
}
class Probe extends Base {
  ArrayList<TGStickerSetInfo> received;
  boolean isEmoji(){return true;}
  void setStickers(ArrayList<TGStickerSetInfo> s){received=s;}
  SETTER
  boolean doNotLoad(){return POLICY;}
  public static void main(String[] a){
    Probe p=new Probe(); p.setArguments(null); if(p.doNotLoad()) throw new AssertionError("direct link never loads installed sets");
    SettingsController owner=new SettingsController(); p.setArguments(owner);
    if(!p.doNotLoad() || owner.listeners!=1) throw new AssertionError("duplicate request or missing listener");
    owner.sets=new ArrayList<>(); p.setArguments(owner);
    if(p.received!=owner.sets || owner.listeners!=1) throw new AssertionError("cached list not reused");
  }
}'''.replace('SETTER', setter).replace('POLICY', policy)
with tempfile.TemporaryDirectory() as d:
    f = Path(d) / 'Probe.java'; f.write_text(probe)
    subprocess.run(['javac', str(f)], check=True)
    subprocess.run(['java', '-cp', d, 'Probe'], check=True)
print('PASS: direct links load independently; preloaded and pending settings paths reuse the owner')
