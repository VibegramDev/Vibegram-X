#!/usr/bin/env python3
"""Run the selection calculation used by the side-tab view against stale RecyclerView binds."""
import os
from pathlib import Path
import re
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
source = (root / 'app/src/main/java/org/thunderdog/challegram/widget/TopicSideTabsView.java').read_text()
calc, = re.findall(r'boolean (?:isSelected|selected) = ([^;\n]+);', source.replace("boolean isSelected = isSelected();", ""))
program = '''
class SideTabTest {
  static class Tab { long id; Tab(long id) { this.id = id; } }
  static class Parent { long selectedId; int selectedPosition; }
  static boolean drawn(Tab tab, Parent parent, int position) {
    return EXPRESSION;
  }
  static void check(String label, int expectedId, int[] oldIds, int[] currentIds) {
    Parent parent = new Parent();
    parent.selectedId = expectedId;
    parent.selectedPosition = java.util.stream.IntStream.range(0, currentIds.length)
      .filter(i -> currentIds[i] == expectedId).findFirst().orElse(-1);
    java.util.List<Integer> seen = new java.util.ArrayList<>();
    for (int i = 0; i < currentIds.length; i++) {
      final int id = currentIds[i];
      final int slot = i;
      int stalePosition = java.util.stream.IntStream.range(0, oldIds.length)
        .filter(j -> oldIds[j] == id).findFirst().orElse(slot);
      if (drawn(new Tab(id), parent, stalePosition)) seen.add(id);
    }
    java.util.List<Integer> expected = parent.selectedPosition < 0 ? java.util.List.of() : java.util.List.of(expectedId);
    if (!seen.equals(expected)) throw new AssertionError(label + ": expected " + expected + ", got " + seen);
    System.out.println("PASS " + label);
  }
  public static void main(String[] args) {
    check("insert before selected", 11, new int[]{0,11,22}, new int[]{0,33,11,22});
    check("move selected", 11, new int[]{0,11,22}, new int[]{0,22,11});
    check("remove before selected", 11, new int[]{0,33,11,22}, new int[]{0,11,22});
    check("remove selected", 11, new int[]{0,11,22}, new int[]{0,22});
    check("switch to All", 0, new int[]{0,11,22}, new int[]{0,11,22});
  }
}
'''.replace('EXPRESSION', calc)
with tempfile.TemporaryDirectory(dir=os.environ.get('TMPDIR'), prefix='vg-side-tabs-') as tmp:
    path = Path(tmp) / 'SideTabTest.java'
    path.write_text(program)
    subprocess.run(['java', str(path)], check=True)
