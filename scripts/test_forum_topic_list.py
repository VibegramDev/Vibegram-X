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
        check(d.requests.isEmpty() && bot.canLoadMore() && bot.hasLoadError() && !bot.isLoading(), "hidden-only continuation reports a retryable error");
        bot.loadMore(); check(d.requests.isEmpty(), "scrolling cannot loop on a failed cursor");
        bot.retry(); bot.retry(); check(bot.isLoading() && !bot.hasLoadError() && d.requests.size() == 1, "explicit retry keeps a single request");
        bot.remove(6); answer(d,5,0,topic(6,false),topic(7,false));
        check(ids(bot).equals(java.util.List.of(7)), "in-flight page cannot resurrect a removed topic");
        bot.refresh(); bot.resetAfterClientRestart();
        answer(d,0,0,topic(8,false));
        check(d.listeners==2 && ids(bot).isEmpty(), "client reset retains subscribers and ignores the old response");
        answer(d,0,0,topic(9,false));
        check(ids(bot).equals(java.util.List.of(9)), "active screen gets the restarted client’s data");
        bot.destroy(); check(d.listeners==0,"destroy unsubscribes");
        checkReopen();
        checkDraftOrder();
        checkPartialRead();
        checkPageSideUpdate();
        checkDeletedPreview();
        checkExactCountAfterIncomingMessage();
        checkStaleReplyPreservesLiveFields();
        checkConflictingCountRetriesOnce();
        checkInitialRetry();
        System.out.println("PASS shared list: handoff, dedup, pin refresh, tail, cursor, accounts, bot filter, deletion, reset, reopen, draft, unread, retry");
      }}
      static void checkReopen() {{
        var d = new Tdlib(); var l = new ForumTopicList(d, 7); Runnable a = () -> {{}};
        l.addListener(a); answer(d, 0, 2, topic(1,false), topic(2,false), topic(3,false), topic(4,false));
        l.loadMore(); answer(d, 2, 0);
        l.removeListener(a); l.addListener(a); answer(d, 0, 2, topic(2,false));
        check(ids(l).containsAll(List.of(1,2,3,4)), "partial refresh does not remove old tail");
        var changed = new ForumTopicInfo(); changed.chatId=7; changed.forumTopicId=4; changed.name="live";
        l.onForumTopicInfoChanged(changed);
        answer(d, 2, 3, topic(3,false));
        l.loadMore(); answer(d, 3, 0);
        check(ids(l).equals(List.of(2,3,4)), "complete refresh prunes only missing unchanged rows");
        l.removeListener(a); l.addListener(a); answer(d, 0, 2, topic(2,false));
        answer(d, 2, 2, topic(2,false));
        check(ids(l).equals(List.of(2,3,4)), "stalled cursor does not prove absence");
      }}
      static UpdateForumTopic update(long read, DraftMessage draft) {{
        var u=new UpdateForumTopic(); u.chatId=7; u.forumTopicId=1; u.lastReadInboxMessageId=read;
        u.unreadPollVoteCount=6; u.draftMessage=draft; return u;
      }}
      static Message message(int date, long sequence) {{
        var m=new Message(); m.chatId=7; m.date=date; m.id=sequence << 20; m.topicId=new MessageTopicForum(1); return m;
      }}
      static void checkDraftOrder() {{
        var d = new Tdlib(); var l = new ForumTopicList(d, 7); l.addListener(() -> {{}});
        var a=topic(1,false); a.lastMessage=message(10,1); a.draftMessage=new DraftMessage(); a.draftMessage.date=30; a.order=30L<<32;
        var b=topic(2,false); b.lastMessage=message(20,2); b.order=(20L<<32)+2;
        answer(d,0,0,a,b); check(ids(l).equals(List.of(1,2)), "draft determines sort order");
        l.onForumTopicUpdated(update(0,null));
        check(ids(l).equals(List.of(2,1)), "clearing draft restores message order");
        check(l.items().get(1).unreadPollVoteCount == 6 && a.unreadPollVoteCount == 0, "full DTO reduced without mutating the old snapshot");
      }}
      static void checkPartialRead() {{
        var d=new Tdlib(); var l=new ForumTopicList(d,7); l.addListener(() -> {{}});
        var a=topic(1,false); a.lastMessage=message(30,15); a.unreadCount=9;
        answer(d,0,0,a); l.onForumTopicUpdated(update(8L<<20,null));
        var renamed=new ForumTopicInfo(); renamed.chatId=7; renamed.forumTopicId=1; renamed.name="renamed";
        l.onForumTopicInfoChanged(renamed);
        var fresh=topic(1,false); fresh.lastMessage=a.lastMessage; fresh.lastReadInboxMessageId=8L<<20; fresh.unreadCount=3;
        d.requests.remove().callback().accept(fresh);
        check(l.items().get(0).info == renamed && l.items().get(0).unreadCount == 3, "rename must not overwrite exact unread count or vice versa");
        l.onForumTopicUpdated(update(9L<<20,null)); l.onForumTopicUpdated(update(10L<<20,null));
        d.requests.remove().callback().accept(fresh);
        check(l.items().get(0).lastReadInboxMessageId == 10L<<20 && d.requests.size()==1, "new partial read supersedes in-flight request");
        var latest=topic(1,false); latest.lastMessage=a.lastMessage; latest.lastReadInboxMessageId=10L<<20; latest.unreadCount=1; latest.info=renamed;
        d.requests.remove().callback().accept(latest);
        check(l.items().get(0).unreadCount == 1 && d.requests.isEmpty(), "new partial read receives its exact count");
      }}
      static ForumTopic latest(ForumTopic old, int date, int sequence, int count) {{
        var topic = ForumTopicState.copyOf(old);
        topic.lastMessage = message(date, sequence);
        topic.order = ((long) date << 32) + sequence;
        topic.unreadCount = count;
        return topic;
      }}
      static void checkPageSideUpdate() {{
        var d=new Tdlib(); var l=new ForumTopicList(d,7); Runnable a=() -> {{}};
        l.addListener(a);
        var old=latest(topic(1,false),30,15,9); answer(d,0,0,old);
        l.removeListener(a); l.addListener(a);
        l.onForumTopicUpdated(update(0,null));
        var fresh=latest(old,31,16,10); fresh.unreadPollVoteCount=6;
        answer(d,0,0,fresh);
        var row=l.items().get(0);
        check(row.lastMessage.id==fresh.lastMessage.id && row.unreadCount==10 && row.order==fresh.order,
          "the update accompanying a page must not hide its exact preview, count and order");
      }}
      static void checkDeletedPreview() {{
        for (boolean rename : new boolean[] {{false, true}}) {{
          var d=new Tdlib(); var l=new ForumTopicList(d,7); l.addListener(() -> {{}});
          var old=latest(topic(1,false),30,15,9); answer(d,0,0,old);
          l.onMessagesDeleted(7,new long[] {{old.lastMessage.id}});
          var point=d.requests.remove(); check(point.fn() instanceof GetForumTopic,"delete requests exact preview");
          l.onForumTopicUpdated(update(0,null));
          var name=new ForumTopicInfo(); name.chatId=7; name.forumTopicId=1; name.name="live rename";
          if (rename) l.onForumTopicInfoChanged(name);
          var fresh=latest(old,29,14,8); fresh.unreadPollVoteCount=6;
          point.callback().accept(fresh);
          var row=l.items().get(0);
          check(row.lastMessage.id==fresh.lastMessage.id && row.unreadCount==8 && row.order==fresh.order,
            "point response replaces a deleted preview even with its accompanying update");
          check(!rename || row.info==name,"the point response does not undo a live rename");
          check(d.requests.isEmpty(),"no repeated request due to an accompanying update");
        }}
      }}
      static void checkExactCountAfterIncomingMessage() {{
        var d=new Tdlib(); var l=new ForumTopicList(d,7); l.addListener(() -> {{}});
        var old=latest(topic(1,false),30,15,9); answer(d,0,0,old);
        l.onForumTopicUpdated(update(8L<<20,null));
        var point=d.requests.remove();
        var fresh=latest(old,31,16,4); fresh.lastReadInboxMessageId=8L<<20;
        l.onNewMessage(fresh.lastMessage);
        point.callback().accept(fresh);
        check(l.items().get(0).unreadCount==4 && d.requests.isEmpty(),
          "count applies when current preview and read marker match the response, not the old snapshot");
      }}
      static void checkStaleReplyPreservesLiveFields() {{
        var d=new Tdlib(); var l=new ForumTopicList(d,7); l.addListener(() -> {{}});
        var old=latest(topic(1,false),30,15,9); answer(d,0,0,old); l.refresh();
        var name=new ForumTopicInfo(); name.chatId=7; name.forumTopicId=1; name.name="live rename";
        l.onForumTopicInfoChanged(name);
        var draft=new DraftMessage(); draft.date=40;
        var event=update(16L<<20,draft); event.isPinned=true; event.lastReadOutboxMessageId=12L<<20;
        l.onNewMessage(message(31,16)); l.onForumTopicUpdated(event);
        answer(d,0,0,old);
        var row=l.items().get(0);
        check(row.lastMessage.id==16L<<20 && row.unreadCount==0 && row.lastReadInboxMessageId==16L<<20,
          "stale reply must not restore an older preview, read marker or unread count");
        check(row.info==name && row.isPinned && row.draftMessage==draft && row.order==40L<<32 &&
          row.lastReadOutboxMessageId==12L<<20 && row.unreadPollVoteCount==6,
          "stale reply preserves newer metadata, pin, draft and secondary counters");
      }}
      static void checkConflictingCountRetriesOnce() {{
        var d=new Tdlib(); var l=new ForumTopicList(d,7); l.addListener(() -> {{}});
        var old=latest(topic(1,false),30,15,9); answer(d,0,0,old);
        l.onForumTopicUpdated(update(8L<<20,null));
        var point=d.requests.remove();
        l.onNewMessage(message(31,16));
        point.callback().accept(old);
        check(l.items().get(0).lastMessage.id==16L<<20 && d.requests.size()==1,
          "conflicting point result protects the current preview and retries once");
        d.requests.remove().callback().accept(old);
        check(l.items().get(0).lastMessage.id==16L<<20 && l.items().get(0).lastReadInboxMessageId==8L<<20 &&
          d.requests.isEmpty(), "another stale point result neither rolls back the preview nor repeats the request");
      }}
      static void checkInitialRetry() {{
        var d=new Tdlib(); var l=new ForumTopicList(d,7); l.addListener(() -> {{}});
        check(l.isLoading() && !l.hasLoadError(), "initial loading is distinct from an empty result");
        d.requests.remove().callback().accept(new org.drinkless.tdlib.TdApi.Error(500,"fixture"));
        check(!l.isLoading() && l.hasLoadError() && l.items().isEmpty(), "first-page error is visible without a cursor");
        l.retry(); answer(d,0,0);
        check(!l.isLoading() && !l.hasLoadError() && l.items().isEmpty(), "confirmed empty result clears error");
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
                    str(base / 'ForumTopicList.java'), str(base / 'ForumTopicState.java'), *map(str, tmp.rglob('*.java'))], check=True)
    subprocess.run(['java', '-cp', str(tmp) + os.pathsep + str(cp), pkg + '.ForumTopicListTest'], check=True)
