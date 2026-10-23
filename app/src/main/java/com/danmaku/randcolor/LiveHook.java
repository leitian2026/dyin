package com.danmaku.randcolor;

import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.os.SystemClock;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;

/**
 * 直播间显示开播时间：在直播间页面上叠一行文字，字号、颜色、格式在设置页调，位置（左上角占「剩余空间」的百分比）在抖音的直播间里用浮窗拖（见下面「位置调节」）。
 * 只在直播间页面里画：文字挂在每个直播间页面（LivePlayFragmentV2）自己的根控件的 ViewOverlay 上，页面没了文字就没了，别的界面碰不到。
 * 挂在页面自己身上还有一个好处：上下滑切直播间时，每个房间的文字跟着自己的页面走，不用判断「当前是哪个房间」。
 *
 * 依据（TextRec，抖音 40.7.0，textrec-tasks-20261022-073711，这台手机冷启动进直播间 / 切到另一个直播间 / 退出再进）：
 *  - 房间数据类 com.bytedance.android.livesdkapi.depend.model.live.Room，类名没混淆，TextRec 报「钩点可用」。另一个包名
 *    com.bytedance.android.live.base.model.live.Room 在 40.7.0 里不存在，不用管。
 *  - Room#getCreateTime() 被调用到，返回秒级时间戳（#13 / #14），和字段快照里的 this._createTime 一致（同一个房间都是 1792624951）。
 *  - 字段快照里同一个房间的时间字段（this 就是 Room）：
 *      this._createTime = 1792624951   （2026-10-22 07:22:31 +08:00）
 *      this.startTime   = 1792624956   （07:22:36，比创建时间晚 5 秒，只有这一个样本，别的房间差多少不知道）
 *      this._finishTime = 1792625786   this.nowTime = 1792625808   this._idStr = "7699265332413762338"
 *    没有 getStartTime 这样的 getter 的记录（这次只追踪了 getCreateTime），所以 startTime 是直接读字段。
 *  - 直播间页面：页面标签 LivePlayActivity > LivePlayFragmentV2；LivePlayFragmentV2#onViewCreated(View, Bundle) 在 #9 的调用栈里
 *    （LivePlayFragmentV2.onViewCreated ← Fragment.performViewCreated），类名方法名都没混淆，TextRec 标 [稳定]；#7 的栈
 *    LiveVerticalViewPager.finishUpdate → commitNowAllowingStateLoss → LivePlayFragmentV2.onCreate：上下滑的每个房间一个页面（fragment）。
 *  - #7：CommonRoomContext#<init> 里会 new 一个 Room（调用栈第 1 层），LivePlayFragmentV2.onCreate 创建的 RoomContext 就是它的子类，
 *    所以页面（fragment）的对象图里能走到它的 Room。
 *
 * 做法：
 *  - live.page：LivePlayFragmentV2#onViewCreated 之后，记下这个页面（弱引用）和它的根控件，请求刷新。
 *  - live.room：Room#getCreateTime 之后请求刷新（Room 数据来了 / 被读了；任意线程，回调里只置一个标志、往主线程发一条消息）。
 *  - 刷新（主线程）：对每个已记下的页面，从 fragment 出发在对象图里按类型找它的 Room（见 search），读 startTime / _createTime，
 *    套模板，设到叠在页面根控件上的 TextView 里，并按位置百分比放好。
 *
 * 没有证据的部分（所以做成「找不到就安静地不显示」，不乱猜）：
 *  ①fragment 到 Room 的字段路径没有记录，按类型（Room）在对象图里找（深度 ≤ 4、最多看 400 个对象，跳过 View / Context /
 *    Fragment 管理器 / java.* / android.*），路径找到后缓存；找到两个不同的 Room 就当「认不出」，不显示；
 *  ②页面根控件是不是 ViewGroup（不是就不显示）；叠在根控件上的文字会不会被页面里别的全屏控件盖住；
 *  ③Room 是原地填数据还是被换成新对象（换了的话靠下一次 getCreateTime 触发重新找）；
 *  ④startTime 是不是「观众看到的开播时间」——所以设置页里可以改成显示房间创建时间。
 *
 * 位置调节（1.24.0）：设置页不再调位置。设置页点「去抖音里调整」后配置里 liveAdjust = true，进直播间时这里照常放文字
 * （时间还没取到 / 开关没开也放一行示例文字），并把当前在屏幕上的页面交给 LivePosPanel：它在窗口最上层盖一层触摸层和一个小浮窗，
 * 拖动 = moveTo（改 cfg.liveX / liveY 再 placeAll），完成 / 取消 = 写回配置文件、撤掉浮窗。不加钩子，不加定时器。
 *
 * 没有定时器 / 轮询：文字只在上面的回调、回到前台、根控件尺寸变化这几个事件里更新；全量搜索每个页面最多 MAX_SCANS 次。
 * 钩子没挂上时功能安静失效，不改变抖音原有行为。
 */
final class LiveHook {
    private LiveHook() {}

    /** 版本相关（40.7.0），类名都没混淆，依据见文件头。 */
    private static final String FRAG = "com.bytedance.android.livesdk.chatroom.LivePlayFragmentV2";
    private static final String ROOM = "com.bytedance.android.livesdkapi.depend.model.live.Room";

    private static final int MAX_PAGES = 6;       // 同时记住的直播间页面（上下滑时前后几个页面都在）
    private static final int MAX_SCANS = 30;      // 每个页面最多做几次全量搜索
    private static final int MAX_DEPTH = 4, MAX_NODES = 400;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean PENDING = new AtomicBoolean();
    private static final Runnable REFRESH = new Runnable() {
        @Override
        public void run() {
            PENDING.set(false);
            try {
                refreshAll();
            } catch (Throwable ignored) {
            }
        }
    };

    private static final XC_MethodHook PAGE_HOOK = new PageHook();
    private static final XC_MethodHook ROOM_HOOK = new RoomHook();

    private static final HookGuard.Filter VIEW_CREATED = new HookGuard.Filter() {
        @Override
        public boolean ok(Method m) {
            if (!m.getName().equals("onViewCreated")) return false;
            Class<?>[] t = m.getParameterTypes();
            return t.length == 2 && t[0] == View.class;
        }
    };

    /** Room 类和两个时间字段：安装阶段按名字找好并缓存，回调里只做取值。 */
    private static Class<?> roomCls;
    private static Field fStart, fCreate;

    /** 已记下的直播间页面。只在主线程用。 */
    private static final ArrayList<Page> PAGES = new ArrayList<>();

    private static final class Page {
        final WeakReference<Object> frag;
        // 都用弱引用：页面根控件（onViewCreated 的参数）和叠在它上面的文字。强引用会把已经退出的直播间界面（连带 Activity）留在内存里。
        WeakReference<ViewGroup> rootRef;
        WeakReference<TextView> tvRef;
        View.OnLayoutChangeListener ll;
        Field[] path;                         // fragment → Room 的字段链（找到后缓存）
        int scans;                            // 已做的全量搜索次数
        int lx = Integer.MIN_VALUE, ly, lw, lh;   // 上次放的位置
        int size = -1, color = Integer.MIN_VALUE; // 上次套的字号 / 颜色
        boolean waiting;                          // 页面在、开关开着，但还没取到时间：页面布局一变就再试一次
        long lastScan;                            // 上次全量搜索的时刻（节流）
        boolean adj;                              // 文字上现在是不是带着「调节模式」的底色

        Page(Object frag) {
            this.frag = new WeakReference<>(frag);
        }
    }

    // ------------------------------------------------------------ 安装

    /** 启动时试一次，之后每次回到前台再试，直到有结论（见 HookGuard）。 */
    static void tryHook(ClassLoader cl) {
        if (cl == null) return;
        hookRoom(cl);
        HookGuard.hook(HookGuard.LIVE_PAGE, FRAG, VIEW_CREATED, PAGE_HOOK,
                "LivePlayFragmentV2 里没有 onViewCreated(View, Bundle)（直播间页面改了，需要重新追踪页面和调用栈）", cl);
    }

    private static void hookRoom(ClassLoader cl) {
        if (HookGuard.settled(HookGuard.LIVE_ROOM)) return;
        if (!DanmakuHook.cfg.liveTime && !DanmakuHook.cfg.liveAdjust) return; // 开播时间没开：不挂 Room.getCreateTime（没有结论，打开后回到前台再挂）
        Class<?> rc = HookGuard.find(HookGuard.LIVE_ROOM, ROOM, cl);
        if (rc == null) return;
        Field fc = null, fs = null;
        try {
            fc = timeField(rc, "_createTime");
            fs = timeField(rc, "startTime");
        } catch (Throwable ignored) {
        }
        if (fc == null && fs == null) {
            HookGuard.miss(HookGuard.LIVE_ROOM, "Room 里没有 _createTime / startTime 这两个时间字段（房间数据类改了，需要重新追踪字段快照）");
            return;
        }
        roomCls = rc;
        fCreate = fc;
        fStart = fs;
        HookGuard.hook(HookGuard.LIVE_ROOM, ROOM, HookGuard.named("getCreateTime"), ROOM_HOOK,
                "Room 里没有 getCreateTime（房间数据类改了，需要重新追踪）", cl);
    }

    /** 按名字在类（和父类）里找一个数值字段；找不到或不是数值返回 null。 */
    private static Field timeField(Class<?> c, String name) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                Class<?> t = f.getType();
                if (t != long.class && t != int.class && !Number.class.isAssignableFrom(t)) return null;
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    // ------------------------------------------------------------ 钩子

    /** 直播间页面的视图建好了：记下页面，请求刷新。 */
    private static final class PageHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam p) {
            try {
                if (Looper.myLooper() != Looper.getMainLooper()) return;
                Object[] a = p.args;
                if (p.thisObject == null || a == null || a.length < 1 || !(a[0] instanceof ViewGroup)) return;
                onPage(p.thisObject, (ViewGroup) a[0]);
            } catch (Throwable ignored) {
            }
        }
    }

    /** Room 的数据被读了：请求刷新。回调里不分配对象、不反射，只发一条（合并过的）主线程消息。 */
    private static final class RoomHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam p) {
            try {
                if (!DanmakuHook.cfg.liveTime) return;
                if (PENDING.compareAndSet(false, true)) MAIN.post(REFRESH);
            } catch (Throwable ignored) {
            }
        }
    }

    static void requestRefresh() {
        if (PENDING.compareAndSet(false, true)) MAIN.post(REFRESH);
    }

    // ------------------------------------------------------------ 页面

    private static void onPage(Object frag, ViewGroup root) {
        Page pg = null;
        for (Iterator<Page> it = PAGES.iterator(); it.hasNext(); ) {
            Page q = it.next();
            Object f = q.frag.get();
            if (f == null) {
                dispose(q);
                it.remove();
            } else if (f == frag) {
                pg = q;
            }
        }
        if (pg == null) {
            if (PAGES.size() >= MAX_PAGES) dispose(PAGES.remove(0)); // 最早记下的那个
            pg = new Page(frag);
            PAGES.add(pg);
        } else {
            dispose(pg); // 同一个页面重建了视图：旧根控件上的文字撤掉
        }
        pg.rootRef = new WeakReference<>(root);
        pg.scans = 0;
        requestRefresh();
    }

    /** 回到前台（配置刚重读过）：按新的开关 / 位置 / 字号 / 颜色 / 格式重新套一遍。 */
    static void onResume() {
        try {
            if (Looper.myLooper() != Looper.getMainLooper()) return;
            refreshAll();
        } catch (Throwable ignored) {
        }
    }

    private static void refreshAll() {
        if (PAGES.isEmpty()) {
            LivePosPanel.end();
            return;
        }
        DanmakuConfig c = DanmakuHook.cfg;
        boolean adj = c.liveAdjust;
        ViewGroup best = null;
        int bestArea = 0;
        for (Iterator<Page> it = PAGES.iterator(); it.hasNext(); ) {
            Page pg = it.next();
            Object frag = pg.frag.get();
            ViewGroup root = frag == null ? null : rootOf(pg);
            if (root == null) {
                dispose(pg);
                it.remove();
                continue;
            }
            if (!c.liveTime && !adj) {
                hide(pg);
                continue;
            }
            ensureListener(pg, root);
            long sec = timeOf(pg, frag, c.liveSrc);
            String text = sec > 0 ? LiveFormat.render(c.liveFormat, sec) : null;
            pg.waiting = text == null;
            if (text == null && adj) text = LiveFormat.render(c.liveFormat, System.currentTimeMillis() / 1000L); // 调位置时没取到时间也放一行示例
            if (text == null) {
                hide(pg);
                continue;
            }
            show(pg, text, c);
            if (adj) {
                int a = visibleArea(root);
                if (a > bestArea) {
                    best = root;
                    bestArea = a;
                }
            }
        }
        if (adj) LivePosPanel.sync(best);
        else LivePosPanel.end();
    }

    private static final Rect TMP = new Rect();

    /** 页面根控件在屏幕上露出来的面积；不在屏幕上 / 被隐藏返回 0。 */
    private static int visibleArea(ViewGroup r) {
        if (!r.isShown() || !r.getGlobalVisibleRect(TMP)) return 0;
        return TMP.width() * TMP.height();
    }

    // ------------------------------------------------------------ 找 Room、读时间

    /** 这个页面的开播时间（秒）；找不到 Room / 数据还没来 / 数据不合理都返回 0。 */
    private static long timeOf(Page pg, Object frag, int src) {
        Object room = pg.path != null ? walk(frag, pg.path) : null;
        long st = 0, ct = 0;
        if (room != null) {
            st = secOf(fStart, room);
            ct = secOf(fCreate, room);
        }
        long now = SystemClock.uptimeMillis();
        if (st <= 0 && ct <= 0 && pg.scans < MAX_SCANS && now - pg.lastScan >= 300) { // 还没找到，或者找到的是还没填数据的那个；两次搜索至少隔 300ms
            pg.scans++;
            pg.lastScan = now;
            Field[] np = search(frag);
            if (np != null) {
                pg.path = np;
                room = walk(frag, np);
                if (room != null) {
                    st = secOf(fStart, room);
                    ct = secOf(fCreate, room);
                }
            }
        }
        if (src == 1) return ct;
        return st > 0 ? st : ct;
    }

    /** 读一个时间字段，换算成秒；读不到、不是合理的时间戳（2001 年到 2065 年之外）返回 0。 */
    private static long secOf(Field f, Object room) {
        if (f == null) return 0;
        try {
            Object v = f.get(room);
            if (!(v instanceof Number)) return 0;
            long s = ((Number) v).longValue();
            if (s > 100000000000L) s /= 1000L; // 毫秒
            return s >= 1000000000L && s <= 3000000000L ? s : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static Object walk(Object start, Field[] path) {
        Object o = start;
        try {
            for (Field f : path) {
                if (o == null) return null;
                o = f.get(o);
            }
        } catch (Throwable t) {
            return null;
        }
        return roomCls != null && roomCls.isInstance(o) ? o : null;
    }

    private static final class Node {
        final Object obj;
        final Node parent;
        final Field via;

        Node(Object obj, Node parent, Field via) {
            this.obj = obj;
            this.parent = parent;
            this.via = via;
        }
    }

    /**
     * 从 fragment 出发按层往下找 Room 类型的字段值，返回 fragment → Room 的字段链。
     * 最浅的一层里只有一个不同的 Room 才算找到；有两个以上不同的就是认不出，返回 null（不猜）。
     */
    private static Field[] search(Object frag) {
        Class<?> rc = roomCls;
        if (rc == null) return null;
        try {
            IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
            ArrayList<Node> cur = new ArrayList<>();
            cur.add(new Node(frag, null, null));
            seen.put(frag, Boolean.TRUE);
            for (int depth = 0; depth < MAX_DEPTH && !cur.isEmpty(); depth++) {
                ArrayList<Node> next = new ArrayList<>();
                Node hit = null;
                Object hitRoom = null;
                for (Node n : cur) {
                    for (Class<?> k = n.obj.getClass(); k != null && k != Object.class && !skipClass(k); k = k.getSuperclass()) {
                        Field[] fs;
                        try {
                            fs = k.getDeclaredFields();
                        } catch (Throwable t) {
                            break;
                        }
                        for (Field f : fs) {
                            if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                            Object v;
                            try {
                                f.setAccessible(true);
                                v = f.get(n.obj);
                            } catch (Throwable t) {
                                continue;
                            }
                            if (v == null) continue;
                            if (rc.isInstance(v)) {
                                if (hit == null) {
                                    hit = new Node(v, n, f);
                                    hitRoom = v;
                                } else if (hitRoom != v) {
                                    return null; // 同一层里有两个不同的 Room：认不出
                                }
                                continue;
                            }
                            if (skipObj(v) || seen.size() >= MAX_NODES || seen.put(v, Boolean.TRUE) != null) continue;
                            next.add(new Node(v, n, f));
                        }
                    }
                }
                if (hit != null) {
                    ArrayList<Field> rev = new ArrayList<>();
                    for (Node n = hit; n != null && n.via != null; n = n.parent) rev.add(n.via);
                    Field[] path = new Field[rev.size()];
                    for (int i = 0; i < path.length; i++) path[i] = rev.get(rev.size() - 1 - i);
                    return path;
                }
                cur = next;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 不进这些类的字段：框架类、Fragment 管理器（里面有别的页面的 Room）。 */
    private static boolean skipClass(Class<?> k) {
        String n = k.getName();
        return n.startsWith("java.") || n.startsWith("javax.") || n.startsWith("android.") || n.startsWith("dalvik.")
                || n.startsWith("libcore.") || n.startsWith("sun.") || n.startsWith("androidx.fragment.")
                || n.startsWith("androidx.lifecycle.Lifecycle") || n.startsWith("androidx.viewpager");
    }

    private static boolean skipObj(Object v) {
        if (v instanceof View || v instanceof Context || v instanceof Handler || v instanceof Class || v instanceof Thread) return true;
        String n = v.getClass().getName();
        return n.startsWith("java.") || n.startsWith("javax.") || n.startsWith("android.") || n.startsWith("dalvik.")
                || n.startsWith("libcore.") || n.startsWith("sun.") || n.startsWith("androidx.fragment.")
                || n.startsWith("androidx.lifecycle.Lifecycle") || n.startsWith("androidx.viewpager") || n.startsWith("kotlinx.");
    }

    // ------------------------------------------------------------ 显示

    private static ViewGroup rootOf(Page pg) {
        return pg.rootRef == null ? null : pg.rootRef.get();
    }

    private static TextView tvOf(Page pg) {
        return pg.tvRef == null ? null : pg.tvRef.get();
    }

    private static void show(Page pg, String text, DanmakuConfig c) {
        ViewGroup root = rootOf(pg);
        if (root == null) return;
        TextView t = tvOf(pg);
        if (t == null) {
            t = new LineBg.Row(root.getContext()); // 带文字灰色底（lineBg）；四边的内边距给底留地方
            t.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)); // TextView 重新布局时会读它
            t.setIncludeFontPadding(false);
            t.setShadowLayer(dp(root, 3), 0f, 0f, 0xFF000000); // 一圈黑边，亮背景上也看得清
            pg.tvRef = new WeakReference<>(t);
            root.getOverlay().add(t);
        }
        if (pg.size != c.liveSize) {
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, c.liveSize);
            pg.size = c.liveSize;
        }
        int col = c.liveColor != 0 ? c.liveColor : 0xFFFFFFFF;
        if (pg.color != col) {
            t.setTextColor(col);
            pg.color = col;
        }
        if (pg.adj != c.liveAdjust) {
            t.setBackgroundColor(c.liveAdjust ? 0x66000000 : 0); // 调位置时给文字一块底色，看得出它有多大
            pg.adj = c.liveAdjust;
        }
        if (!text.contentEquals(t.getText())) t.setText(text);
        if (t.getVisibility() != View.VISIBLE) t.setVisibility(View.VISIBLE);
        place(pg);
    }

    /** 页面根控件的布局监听：页面尺寸变了重放文字；还没取到时间时（房间数据后到），页面布局一变就再试一次。第一次见到页面就注册，不等文字显示。 */
    private static void ensureListener(final Page pg, ViewGroup root) {
        if (pg.ll != null) return;
        pg.ll = new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int tp, int r, int b, int ol, int ot, int or, int ob) {
                try {
                    place(pg);
                    if (pg.waiting || DanmakuHook.cfg.liveAdjust) requestRefresh();
                } catch (Throwable ignored) {
                }
            }
        };
        root.addOnLayoutChangeListener(pg.ll);
    }

    private static void hide(Page pg) {
        TextView t = tvOf(pg);
        if (t != null && t.getVisibility() != View.GONE) t.setVisibility(View.GONE);
    }

    /** 撤掉这个页面上的文字和监听。 */
    private static void dispose(Page pg) {
        try {
            ViewGroup root = rootOf(pg);
            TextView t = tvOf(pg);
            if (root != null) {
                if (t != null) root.getOverlay().remove(t);
                if (pg.ll != null) root.removeOnLayoutChangeListener(pg.ll);
            }
        } catch (Throwable ignored) {
        }
        pg.tvRef = null;
        pg.ll = null;
        pg.lx = Integer.MIN_VALUE;
        pg.size = -1;
        pg.color = Integer.MIN_VALUE;
        pg.adj = false;
    }

    /**
     * 按位置百分比放好：左 = (页面宽 − 文字宽) × x%，上 = (页面高 − 文字高) × y%，所以 0～100% 文字都完整在页面里。
     * 叠在 ViewOverlay 上的控件系统不会替它测量 / 布局，这里自己做；页面尺寸变了由根控件的布局监听再放一次。
     */
    private static void place(Page pg) {
        ViewGroup root = rootOf(pg);
        TextView t = tvOf(pg);
        if (root == null || t == null || t.getVisibility() != View.VISIBLE) return;
        int w = root.getWidth(), h = root.getHeight();
        if (w <= 0 || h <= 0) return;
        t.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.AT_MOST));
        int tw = t.getMeasuredWidth(), th = t.getMeasuredHeight();
        DanmakuConfig c = DanmakuHook.cfg;
        int l = Math.max(0, (int) ((w - tw) * (c.liveX / 100f)));
        int tp = Math.max(0, (int) ((h - th) * (c.liveY / 100f)));
        t.layout(l, tp, l + tw, tp + th);
        if (l != pg.lx || tp != pg.ly || tw != pg.lw || th != pg.lh) {
            pg.lx = l;
            pg.ly = tp;
            pg.lw = tw;
            pg.lh = th;
            root.invalidate();
        }
    }

    /** 位置变了（浮窗里拖动 / 微调）：每个页面上的文字重新放一遍。 */
    static void placeAll() {
        for (Page pg : PAGES) place(pg);
    }

    /**
     * 浮窗里拖动：文字从 (sx%, sy%) 起跟着手指移动 (dx, dy) 像素，换算回百分比写进 cfg 再放。
     * 位置公式是 左 = (页面宽 − 文字宽) × x%，所以手指移动 dx 像素 = 百分比变 dx / (页面宽 − 文字宽) × 100，文字正好跟手；
     * 文字比页面还宽 / 高的那个方向不动。
     */
    static void moveTo(ViewGroup root, float sx, float sy, float dx, float dy) {
        for (Page pg : PAGES) {
            if (rootOf(pg) != root) continue;
            TextView t = tvOf(pg);
            if (t == null) return;
            float rx = root.getWidth() - t.getMeasuredWidth(), ry = root.getHeight() - t.getMeasuredHeight();
            DanmakuConfig c = DanmakuHook.cfg;
            if (rx > 0) c.liveX = Math.max(0, Math.min(100, Math.round(sx + dx * 100f / rx)));
            if (ry > 0) c.liveY = Math.max(0, Math.min(100, Math.round(sy + dy * 100f / ry)));
            break;
        }
        placeAll();
    }

    private static float dp(View v, int n) {
        return n * v.getResources().getDisplayMetrics().density;
    }
}
