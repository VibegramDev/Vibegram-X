#!/usr/bin/env python3
"""Exercise the shared forum list with real generated TdApi objects and an in-memory transport."""
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
cp = root / 'tdlib/build/intermediates/javac/latestRelease/compileLatestReleaseJavaWithJavac/classes'
assert (cp / 'org/drinkless/tdlib/TdApi.class').is_file(), 'Build latestRelease TDLib first; use the same JDK as Gradle.'
pkg = 'org.thunderdog.challegram.telegram'
base = root / 'app/src/main/java' / pkg.replace('.', '/')
files = {
    'androidx/annotation/Nullable.java': 'package androidx.annotation; public @interface Nullable {}',
    'org/thunderdog/challegram/tool/UI.java': 'package org.thunderdog.challegram.tool; public final class UI { public static void post(Runnable r) { r.run(); } }',
    'ChatListener.java': 'package ' + pkg + '; interface ChatListener extends ForumTopicInfoListener {}',
    'MessageListener.java': f'''package {pkg}; import org.drinkless.tdlib.TdApi;
    interface MessageListener {{
      void onNewMessage(TdApi.Message m);
      void onMessageSendSucceeded(TdApi.Message m, long old);
      void onMessageContentChanged(long chat, long mid, TdApi.MessageContent content);
      void onMessagesDeleted(long chat, long[] ids);
    }}''',
    'TdlibForumTopicManager.java': f'''package {pkg}; import org.drinkless.tdlib.TdApi.*;
    class TdlibForumTopicManager {{
      void putTopics(long id, ForumTopic[] topics) {{}}
      void putTopic(long id, ForumTopic topic) {{}}
      static long messageOrder(Message m) {{ return m != null ? ((long)m.date << 32) + (m.id >> 20) : 0; }}
      static long draftOrder(DraftMessage m) {{ return m != null ? (long)m.date << 32 : 0; }}
      static ForumTopic copyOf(ForumTopic t) {{ return new ForumTopic(t.info,t.lastMessage,t.order,t.isPinned,t.unreadCount,
        t.lastReadInboxMessageId,t.lastReadOutboxMessageId,t.unreadMentionCount,t.unreadReactionCount,t.unreadPollVoteCount,t.notificationSettings,t.draftMessage); }}
    }}''',
    'Tdlib.java': f'''package {pkg};
    import java.util.*; import java.util.function.Consumer; import org.drinkless.tdlib.TdApi;
    class Tdlib {{
      record Request(TdApi.Function<?> fn, Consumer<TdApi.Object> callback) {{}}
      final Queue<Request> requests = new ArrayDeque<>(); int listeners;
      Tdlib client() {{ return this; }}
      Tdlib listeners() {{ return this; }}
      TdlibForumTopicManager topics() {{ return new TdlibForumTopicManager(); }}
      boolean isBotForum(long chatId) {{ return chatId == 9; }}
      void awaitInitialization(Runnable callback) {{ callback.run(); }}
      boolean isAuthorized() {{ return true; }}
      void send(TdApi.Function<?> f, Consumer<TdApi.Object> callback) {{ requests.add(new Request(f, callback)); }}
      void subscribeToChatUpdates(long id, ChatListener l) {{ listeners++; }}
      void unsubscribeFromChatUpdates(long id, ChatListener l) {{ listeners--; }}
      void subscribeToMessageUpdates(long id, MessageListener l) {{ listeners++; }}
      void unsubscribeFromMessageUpdates(long id, MessageListener l) {{ listeners--; }}
    }}''',
    'ForumTopicListTest.java': f'''package {pkg};
    import java.util.*; import org.drinkless.tdlib.TdApi.*;
    class ForumTopicListTest {{
      static ForumTopic topic(int id, boolean pinned) {{
        ForumTopic t = new ForumTopic(); t.info = new ForumTopicInfo(); t.info.forumTopicId=id;
        t.info.name="topic " + id; t.order=100-id; t.isPinned=pinned; return t;
      }}
      static void answer(Tdlib d, int expected, int next, ForumTopic... topics) {{
        var r = d.requests.remove();
        GetForumTopics f = (GetForumTopics)r.fn(); check(f.offsetForumTopicId==expected,"cursor expected " + expected);
        ForumTopics p = new ForumTopics(); p.topics=topics; p.nextOffsetForumTopicId=next;
        r.callback().accept(p);
      }}
      static void check(boolean ok, String label) {{ if (!ok) throw new AssertionError(label); }}
      static java.util.List<Integer> ids(ForumTopicList l) {{ return l.items().stream().map(t -> t.info.forumTopicId).toList(); }}
      public static void main(String[] args) {{
        var d = new Tdlib(); var l = new ForumTopicList(d, 7); var other = new Tdlib(); var otherList = new ForumTopicList(other, 7);
        int[] calls = {{0}}; Runnable a = () -> {{}}, b = () -> calls[0]++;
        l.addListener(a); l.addListener(b); check(d.requests.size()==1 && d.listeners==2, "one request and one subscription per chat");
        answer(d, 0, 2, topic(1,false), topic(2,false));
        l.loadMore(); l.loadMore(); l.removeListener(a); check(d.requests.size()==1 && d.listeners==2, "page stays owned during screen handoff");
        l.refresh(); answer(d, 2, 4, topic(2,false), topic(3,false), topic(4,false));
        check(ids(l).equals(java.util.List.of(1,2,3,4)), "continuation deduplicates and reaches the live listener");
        answer(d, 0, 3, topic(3,true), topic(1,true));
        check(ids(l).equals(java.util.List.of(3,1,2,4)), "refresh keeps the tail and server order of pins");
        l.loadMore(); answer(d, 4, 4, topic(4,false));
        check(!l.canLoadMore() && calls[0]>=4, "stalled cursor stops and listener notified");
        l.removeListener(b); check(d.listeners==0, "unsubscribe on final destroy");
        otherList.addListener(a); answer(other,0,0,topic(21,false));
        check(ids(otherList).equals(java.util.List.of(21)) && ids(l).equals(java.util.List.of(3,1,2,4)), "account separation");
        var bot = new ForumTopicList(d,9); bot.addListener(a); var general=topic(5,false); general.info.isGeneral=true;
        answer(d,0,5,general); check(ids(bot).isEmpty() && bot.canLoadMore() && d.requests.size()==1, "hidden General automatically advances the cursor for both screens");
        d.requests.remove().callback().accept(new org.drinkless.tdlib.TdApi.Error());
        check(d.requests.isEmpty() && bot.canLoadMore(), "page error permits a retry without looping");
        bot.loadMore(); bot.remove(6); answer(d,5,0,topic(6,false),topic(7,false));
        check(ids(bot).equals(java.util.List.of(7)), "in-flight page cannot resurrect a removed topic");
        bot.refresh(); bot.resetAfterClientRestart();
        answer(d,0,0,topic(8,false));
        check(d.listeners==2 && ids(bot).isEmpty(), "client reset retains subscribers and ignores the old response");
        answer(d,0,0,topic(9,false));
        check(ids(bot).equals(java.util.List.of(9)), "active screen gets the restarted client’s data");
        bot.destroy(); check(d.listeners==0,"destroy unsubscribes");
        System.out.println("PASS shared list: handoff, dedup, pin refresh, tail, cursor, accounts, bot filter, deletion, reset");
      }}
    }}''',
}
with tempfile.TemporaryDirectory(dir=os.environ.get('TMPDIR'), prefix='vg-forum-list-') as tmp:
    tmp = Path(tmp)
    for name, text in files.items():
        f = tmp / name
        f.parent.mkdir(parents=True, exist_ok=True)
        f.write_text(text)
    subprocess.run(['javac', '-cp', str(cp), '-d', str(tmp), str(base / 'ForumTopicInfoListener.java'),
                    str(base / 'ForumTopicList.java'), *map(str, tmp.rglob('*.java'))], check=True)
    subprocess.run(['java', '-cp', str(tmp) + os.pathsep + str(cp), pkg + '.ForumTopicListTest'], check=True)
