package com.danmaku.randcolor;

import android.app.Activity;
import android.graphics.Rect;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 把“点赞”改成“打开评论”。两个入口，各有开关：
 *  1) 右侧爱心按钮：TextRec 记录 #316 的栈是 View.performClick → onClick → FeedDiggPresenter#LJJJJJL → …（40.7.0，方法名混淆，类名没混淆）。
 *     拦掉 LJJJJJL（不让它发起点赞），改为点一下评论按钮。LJJLIIIJL 是点赞后刷新界面的那一步，拦它拦不住点赞请求，所以不挂它。
 *  2) 双击视频（实验）：入口还没抓到过，所以用通用办法——监听 GestureDetector 的双击回调，命中就拦下并打开评论。
 *     抖音如果没用 GestureDetector 做双击，这个开关不会起作用；“检查状态”里会列出每个触发过的双击监听类，用来定位。
 * 评论按钮的找法：当前界面里 contentDescription 形如“评论24，按钮”、可见面积最大的那个（TextRec 记录：LinearLayout id=e9l，
 * 父级 HPFrameLayout#comment_layout），然后对它（或最近有点击监听的父 / 子）performClick。
 * 找不到评论按钮时一律不拦截，原来的点赞照常，不会出现“点了什么都没发生”。
 */
final class CommentHook {
    private CommentHook() {}

    static volatile WeakReference<Activity> top = new WeakReference<>(null);

    private static final String DIGG = "com.ss.android.ugc.aweme.feed.quick.presenter.FeedDiggPresenter";
    /** 版本相关：40.7.0 里右侧爱心按钮 onClick 调到的方法。换版本后用 TextRec 重新记录一次，把新名字改在这里。 */
    private static final String DIGG_CLICK_METHOD = "LJJJJJL";

    /** 点赞后刷新界面那一步（#316 / #545 都经过它）。只用来诊断：双击之后它又被调用，就把栈记下来，找真正的点赞入口。 */
    private static final String DIGG_UI_METHOD = "LJJLIIIJL";

    /** 1.6 日志：双击后 FeedDiggPresenter$observeVM$2.LIZ 在 Handler 里被调用，它创建 X.1DAj 去执行点赞。类名没混淆，方法名混淆。 */
    private static final String DIGG_OBSERVER = DIGG + "$observeVM$2";
    private static final String DIGG_OBSERVER_METHOD = "LIZ";
    /** 双击之后多长时间内，把点赞观察者的调用当作“双击点赞”拦掉（毫秒）。 */
    private static final long DTAP_GUARD_MS = 1500;

    private static boolean installed;
    private static volatile boolean diggHooked;
    private static volatile boolean observerHooked;
    private static volatile boolean diggLayoutHooked;
    /** 在这个时间点之前，点赞观察者的调用一律拦掉（只在“双击→评论”开着时才会被设置）。 */
    private static volatile long dtapGuardUntil;
    /** 最近一次识别到双击的时间（不管开关开没开），用来诊断。 */
    private static volatile long lastDtapAt;
    /** 最近一次我们接管双击并打开评论的时间；同一次双击里多个回调 / 多个监听只打开一次。 */
    private static volatile long lastOpenAt;
    /** 本次双击的后续事件（移动、抬起）也要一起拦掉。 */
    private static volatile boolean dtapBlocking;
    private static final Set<Class<?>> TAP_HOOKED = Collections.synchronizedSet(new HashSet<Class<?>>());
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> LOGGED = new java.util.concurrent.ConcurrentHashMap<>();

    static synchronized void install() {
        if (installed) return;
        installed = true;
        try {
            XC_MethodHook scan = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        if (p.args != null) for (Object a : p.args) hookListener(a);
                    } catch (Throwable ignored) {
                    }
                }
            };
            for (Constructor<?> c : GestureDetector.class.getDeclaredConstructors()) XposedBridge.hookMethod(c, scan);
            XposedBridge.hookMethod(GestureDetector.class.getDeclaredMethod("setOnDoubleTapListener",
                    GestureDetector.OnDoubleTapListener.class), scan);
        } catch (Throwable t) {
            DanmakuHook.status("挂接 GestureDetector 失败：" + t);
        }
        hookHeartViews();
        hookHeartAnimations();
        hookTouchGuard();
    }

    // ------------------------------------------------------------ 双击红心动画

    private static final java.util.regex.Pattern HEART = java.util.regex.Pattern.compile(
            "heart|digg|like|praise|love|thumb|favor|burst", java.util.regex.Pattern.CASE_INSENSITIVE);
    /** 可见性变化 / 播放时再把 lottie 算进去（预加载时加进界面的 Lottie 不是红心，不能挡，否则会缺控件甚至闪退）。 */
    private static final java.util.regex.Pattern HEART_LOTTIE = java.util.regex.Pattern.compile(
            "heart|digg|like|praise|love|thumb|favor|burst|lottie", java.util.regex.Pattern.CASE_INSENSITIVE);

    /** 类名 + 资源 id 名，用来判断是不是红心动画相关的 View。 */
    private static String describe(View v) {
        String d = v.getClass().getName();
        try {
            if (v.getId() != View.NO_ID && v.getResources() != null) d += "#" + v.getResources().getResourceEntryName(v.getId());
        } catch (Throwable ignored) {
        }
        return d;
    }

    private static boolean guardActive() {
        return DanmakuHook.cfg.dtapToComment && android.os.SystemClock.uptimeMillis() <= dtapGuardUntil;
    }

    /**
     * 双击后的红心动画：双击后 DTAP_GUARD_MS 内，凡是“新加进界面 / 变成可见 / 开始播放”的红心类 View 一律挡掉。
     * 同时把窗口内出现的所有新 View 的类名记到状态里（每个类 2 分钟一条），方便第一版没挡住时精确定位。
     */
    private static void hookHeartViews() {
        try {
            XposedBridge.hookMethod(ViewGroup.class.getDeclaredMethod("addView", View.class, int.class, ViewGroup.LayoutParams.class),
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            try {
                                if (!guardActive() || p.args == null || !(p.args[0] instanceof View)) return;
                                String d = describe((View) p.args[0]);
                                if (inInflate()) return; // 布局预加载（LayoutInflater / X2C）不是红心：不记、更不能挡（挡了会缺控件）
                                if (fromDiggLayout()) {
                                    logOnce("hv-add:" + d, "已挡掉双击红心（addView）：" + d + " ← " + callers());
                                    p.setResult(null);
                                } else if (fromUserInput() && !fromCommentPanel()) {
                                    // 只记“由点击 / 触摸直接引起”的新增 View：红心如果是点一下生成一个，就会在这里；信息流预加载下一个视频的不记
                                    logOnce("hv-seen:" + d, "双击后由触摸新增 View：" + d + " ← " + callers());
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
        } catch (Throwable t) {
            DanmakuHook.status("挂接 ViewGroup.addView 失败：" + t);
        }
        try {
            XposedBridge.hookMethod(View.class.getDeclaredMethod("setVisibility", int.class), new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (!guardActive() || p.args == null || ((Integer) p.args[0]) != View.VISIBLE) return;
                        View v = (View) p.thisObject;
                        String d = describe(v);
                        if (fromDiggLayout()) {
                            logOnce("hv-vis:" + d, "已挡掉双击红心（setVisibility）：" + d + " ← " + callers());
                            p.args[0] = View.INVISIBLE;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable t) {
            DanmakuHook.status("挂接 View.setVisibility 失败：" + t);
        }
    }

    private static volatile long lastDownT;
    private static volatile float lastDownX, lastDownY;

    /**
     * 更早地开窗：抖音可能在 GestureDetector 的 onDoubleTap 之前，就在自己的 onTouch 里按“连点”生成红心（像直播点赞那样每点一下生成一个动画）。
     * 所以在 Activity.dispatchTouchEvent（所有触摸的最前面）里自己判断“第二次按下”：与上一次按下间隔小于系统双击超时、位置相近，就立刻开窗。
     */
    private static void hookTouchGuard() {
        try {
            XposedBridge.hookMethod(Activity.class.getDeclaredMethod("dispatchTouchEvent", MotionEvent.class), new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (!DanmakuHook.cfg.dtapToComment || p.args == null || !(p.args[0] instanceof MotionEvent)) return;
                        MotionEvent e = (MotionEvent) p.args[0];
                        if (e.getActionMasked() != MotionEvent.ACTION_DOWN || e.getPointerCount() != 1) return;
                        long t = e.getEventTime();
                        float dx = e.getX() - lastDownX, dy = e.getY() - lastDownY;
                        float lim = 100f * ((Activity) p.thisObject).getResources().getDisplayMetrics().density;
                        long gap = t - lastDownT;
                        if (gap >= 40 && gap <= android.view.ViewConfiguration.getDoubleTapTimeout() && dx * dx + dy * dy <= lim * lim) {
                            dtapGuardUntil = android.os.SystemClock.uptimeMillis() + DTAP_GUARD_MS;
                            logOnce("touch-guard", "第二次按下（触摸层）：已提前开窗");
                        }
                        lastDownT = t;
                        lastDownX = e.getX();
                        lastDownY = e.getY();
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable t) {
            DanmakuHook.status("挂接 Activity.dispatchTouchEvent 失败：" + t);
        }
    }

    /** 双击红心就是 common.widget.DiggLayout 生成的（1.9.3 日志：onTouch → BaseListFragmentPanel → DiggLayout.LIZJ → addView(ImageView) + startAnimation）。 */
    private static final String DIGG_LAYOUT = "com.ss.android.ugc.aweme.common.widget.DiggLayout";

    private static boolean fromDiggLayout() {
        for (StackTraceElement e : new Throwable().getStackTrace()) {
            if (e.getClassName().equals(DIGG_LAYOUT)) return true;
        }
        return false;
    }

    private static boolean fromCommentPanel() {
        for (StackTraceElement e : new Throwable().getStackTrace()) {
            String c = e.getClassName();
            if (c.contains(".comment.") || c.contains("comment_split") || c.contains("CommentPad") || c.contains("CommentList")) return true;
        }
        return false;
    }

    private static boolean inInflate() {
        for (StackTraceElement e : new Throwable().getStackTrace()) {
            String c = e.getClassName();
            if (c.equals("android.view.LayoutInflater") || c.startsWith("com.by.inflate_lib.") || c.startsWith("nita.inflate.")) return true;
        }
        return false;
    }

    /** 红心很可能是：ViewStub 第一次展开 / 属性动画 / 帧动画 / View 动画。窗口内都记下来（记下“谁在动”），名字像红心的直接挡。 */
    private static void hookHeartAnimations() {
        try {
            XposedBridge.hookMethod(android.view.ViewStub.class.getDeclaredMethod("inflate"), new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        if (!guardActive() || !(p.getResult() instanceof View)) return;
                        View v = (View) p.getResult();
                        String d = describe(v);
                        boolean h = fromDiggLayout();
                        logOnce("hv-stub:" + d, "双击后 ViewStub 展开：" + d + (h ? "（已隐藏）" : "") + " ← " + callers());
                        if (h) v.setVisibility(View.GONE);
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable t) {
            DanmakuHook.status("挂接 ViewStub.inflate 失败：" + t);
        }
        XC_MethodHook animHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    if (!guardActive()) return;
                    if (fromCommentPanel()) return; // 评论面板打开 / 收起的动画：不是红心，也不能挡
                    Object t = p.thisObject;
                    String d = t.getClass().getName();
                    if (t instanceof android.animation.ObjectAnimator) {
                        Object tg = ((android.animation.ObjectAnimator) t).getTarget();
                        if (tg instanceof View) d += " → " + describe((View) tg);
                        else if (tg != null) d += " → " + tg.getClass().getName();
                    }
                    boolean h = fromDiggLayout();
                    logOnce("hv-anim:" + d, "双击后动画开始：" + d + (h ? "（已挡掉）" : "") + " ← " + callers());
                    if (h) p.setResult(null);
                } catch (Throwable ignored) {
                }
            }
        };
        try {
            XposedBridge.hookMethod(android.animation.ValueAnimator.class.getDeclaredMethod("start"), animHook);
            XposedBridge.hookMethod(android.animation.ObjectAnimator.class.getDeclaredMethod("start"), animHook);
        } catch (Throwable t) {
            DanmakuHook.status("挂接属性动画失败：" + t);
        }
        try {
            XposedBridge.hookMethod(android.graphics.drawable.AnimationDrawable.class.getDeclaredMethod("start"), animHook);
        } catch (Throwable ignored) {
        }
        try {
            XposedBridge.hookMethod(View.class.getDeclaredMethod("startAnimation", android.view.animation.Animation.class), new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (!guardActive()) return;
                        String d = describe((View) p.thisObject);
                        boolean h = fromDiggLayout();
                        logOnce("hv-vanim:" + d, "双击后 View 动画：" + d + (h ? "（已挡掉）" : "") + " ← " + callers());
                        if (h) p.setResult(null);
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static final java.util.Set<String> LOTTIE_DONE = Collections.synchronizedSet(new HashSet<String>());

    /** 红心很可能是 Lottie 动画（抖音用的是字节自己的分支 com.bytedance.lottie，也可能是 airbnb 原版）：窗口内不让它开始播放。 */
    private static void hookLottie(ClassLoader cl) {
        if (cl == null) return;
        for (final String cn : new String[]{"com.bytedance.lottie.LottieAnimationView", "com.airbnb.lottie.LottieAnimationView"}) {
            if (LOTTIE_DONE.contains(cn)) continue;
            try {
                Class<?> k = Class.forName(cn, false, cl);
                int n = 0;
                for (Method m : k.getDeclaredMethods()) {
                    if (!m.getName().equals("playAnimation") && !m.getName().equals("resumeAnimation")) continue;
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            try {
                                if (!guardActive()) return;
                                String d = describe((View) p.thisObject);
                                boolean h = fromDiggLayout();
                                logOnce("hv-lottie:" + d, "双击后 Lottie 开始播放：" + d + (h ? "（已挡掉）" : "") + " ← " + callers());
                                if (h) p.setResult(null);
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                    n++;
                }
                LOTTIE_DONE.add(cn);
                DanmakuHook.status("已挂接 Lottie 播放：" + cn + "（" + n + " 个方法）");
            } catch (ClassNotFoundException e) {
                // 没有这个类或还没加载：下次回到前台再试
            } catch (Throwable t) {
                LOTTIE_DONE.add(cn);
                DanmakuHook.status("挂接 Lottie 失败：" + cn + " " + t);
            }
        }
    }

    // ------------------------------------------------------------ 1) 右侧爱心按钮

    /** 需要目标 App 的类加载器；启动时试一次，之后每次回到前台再试，直到挂上。 */
    static void tryHookDigg(ClassLoader cl) {
        if (cl == null) return;
        hookObserver(cl);
        hookDiggLayout(cl);
        hookLottie(cl);
        if (diggHooked) {
            hookDiag(cl);
            return;
        }
        try {
            Class<?> k = Class.forName(DIGG, false, cl);
            int n = 0;
            for (Method m : k.getDeclaredMethods()) {
                if (!m.getName().equals(DIGG_CLICK_METHOD)) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            if (!DanmakuHook.cfg.likeBtnToComment) return;
                            // 只拦“用户点出来的”：这个方法如果在切视频 / 绑定数据 / 收到服务器结果时也会被调用，
                            // 不加这个判断就会自己弹出评论。栈里没有点击或触摸事件的一律放行。
                            if (!fromUserInput()) return;
                            logOnce("btn", "点赞入口被触发：" + callers());
                            if (openComments()) p.setResult(defaultOf(((Method) p.method).getReturnType()));
                        } catch (Throwable ignored) {
                        }
                    }
                });
                n++;
            }
            for (Method m : k.getDeclaredMethods()) {
                if (!m.getName().equals(DIGG_UI_METHOD)) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            if (android.os.SystemClock.uptimeMillis() - lastDtapAt > 1500) return;
                            String c = callers();
                            logOnce("after-tap:" + c.hashCode(), "双击后点赞仍被触发：" + c);
                        } catch (Throwable ignored) {
                        }
                    }
                });
            }
            diggHooked = true;
            hookDiag(cl);
            DanmakuHook.status(n > 0 ? "已挂接 FeedDiggPresenter#" + DIGG_CLICK_METHOD + "（" + n + " 个重载）"
                    : "FeedDiggPresenter 里没有叫 " + DIGG_CLICK_METHOD + " 的方法：抖音版本变了，需要用 TextRec 重新记录点赞入口");
        } catch (ClassNotFoundException e) {
            // 类还没加载或这个版本没有：下次回到前台再试
        } catch (Throwable t) {
            diggHooked = true;
            DanmakuHook.status("挂接 FeedDiggPresenter 失败：" + t);
        }
    }

    /**
     * 拦双击点赞的真正入口：双击后 FeedDiggPresenter$observeVM$2.LIZ 被调用，它负责发起点赞并创建 X.1DAj 刷新界面。
     * 只在“双击→评论”开着、且刚刚发生过双击（DTAP_GUARD_MS 内）时跳过它；其它时候（按钮点赞、切视频）原样放行。
     */
    /**
     * 双击红心的真正来源：common.widget.DiggLayout.LIZJ（方法名混淆，类名没混淆）。窗口内直接跳过它，红心就不会生成。
     * 只拦 void / 基本类型返回值的重载；返回对象的重载只记不拦（返回 null 可能让调用方崩）。
     */
    private static void hookDiggLayout(ClassLoader cl) {
        if (diggLayoutHooked || cl == null) return;
        try {
            Class<?> k = Class.forName(DIGG_LAYOUT, false, cl);
            int n = 0;
            StringBuilder sig = new StringBuilder();
            for (Method m : k.getDeclaredMethods()) {
                if (!m.getName().equals("LIZJ")) continue;
                sig.append(m.getReturnType().getSimpleName()).append(java.util.Arrays.toString(m.getParameterTypes())).append(' ');
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            if (!guardActive()) return;
                            Class<?> rt = ((Method) p.method).getReturnType();
                            if (rt == void.class || rt.isPrimitive()) {
                                dtapGuardUntil = android.os.SystemClock.uptimeMillis() + DTAP_GUARD_MS; // 连点：每挡一次延长窗口
                                logOnce("digg-layout", "已挡掉双击红心（DiggLayout.LIZJ）");
                                p.setResult(defaultOf(rt));
                            } else {
                                logOnce("digg-layout-obj", "DiggLayout.LIZJ 返回对象，未拦：" + rt.getName());
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
                n++;
            }
            diggLayoutHooked = n > 0;
            DanmakuHook.status(n > 0 ? "已挂接 DiggLayout#LIZJ（" + n + " 个：" + sig.toString().trim() + "）"
                    : "DiggLayout 里没有叫 LIZJ 的方法：抖音版本变了，需要重新记录");
        } catch (ClassNotFoundException e) {
            // 类还没加载：下次回到前台再试
        } catch (Throwable t) {
            diggLayoutHooked = true;
            DanmakuHook.status("挂接 DiggLayout 失败：" + t);
        }
    }

    private static void hookObserver(ClassLoader cl) {
        if (observerHooked || cl == null) return;
        try {
            Class<?> k = Class.forName(DIGG_OBSERVER, false, cl);
            int n = 0;
            for (Method m : k.getDeclaredMethods()) {
                if (!m.getName().equals(DIGG_OBSERVER_METHOD) || m.isBridge() || m.isSynthetic()) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            if (!DanmakuHook.cfg.dtapToComment) return;
                            if (android.os.SystemClock.uptimeMillis() > dtapGuardUntil) return;
                            logOnce("guard", "已拦下双击点赞（FeedDiggPresenter$observeVM$2." + DIGG_OBSERVER_METHOD + "）");
                            p.setResult(defaultOf(((Method) p.method).getReturnType()));
                        } catch (Throwable ignored) {
                        }
                    }
                });
                n++;
            }
            observerHooked = n > 0;
            DanmakuHook.status(n > 0 ? "已挂接双击点赞拦截（" + n + " 个方法）"
                    : "observeVM$2 里没有叫 " + DIGG_OBSERVER_METHOD + " 的方法：抖音版本变了，需要用 TextRec 重新记录");
        } catch (ClassNotFoundException e) {
            // 类还没加载或这个版本没有：下次回到前台再试
        } catch (Throwable t) {
            observerHooked = true;
            DanmakuHook.status("挂接 observeVM$2 失败：" + t);
        }
    }

    private static volatile boolean diagHooked;

    /**
     * 诊断（不改任何行为）：1.4 的日志显示，拦住 onDoubleTap / onDoubleTapEvent 之后点赞仍然发生了——
     * LJJLIIIJL 是被 Handler 里的 X.1DAj.run 和 EventBus 的 subscribeDiggUpdateEvent 调用的，栈里看不到是谁发起的。
     * 这里记两样东西：谁发出了和点赞有关的 EventBus 事件；X.1DAj 是在哪里创建的。
     */
    private static void hookDiag(ClassLoader cl) {
        if (diagHooked || cl == null) return;
        try {
            Class<?> eb = Class.forName("org.greenrobot.eventbus.EventBus", false, cl);
            XposedBridge.hookMethod(eb.getDeclaredMethod("post", Object.class), new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (p.args == null || p.args.length == 0 || p.args[0] == null) return;
                        String n = p.args[0].getClass().getName();
                        String l = n.toLowerCase(java.util.Locale.ROOT);
                        if (!l.contains("digg") && !l.contains("like")) return;
                        String c = callers();
                        logOnce("evt:" + n + c.hashCode(), "点赞事件 " + n + " 由谁发出：" + c);
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (ClassNotFoundException e) {
            return; // EventBus 还没加载：下次回到前台再试
        } catch (Throwable t) {
            DanmakuHook.status("挂接 EventBus.post 失败：" + t);
        }
        diagHooked = true;
        try {
            Class<?> k = Class.forName("X.1DAj", false, cl);
            for (Constructor<?> c : k.getDeclaredConstructors()) {
                XposedBridge.hookMethod(c, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            String s = callers();
                            logOnce("mk:" + s.hashCode(), "X.1DAj 被创建：" + s);
                        } catch (Throwable ignored) {
                        }
                    }
                });
            }
        } catch (Throwable t) {
            DanmakuHook.status("找不到 X.1DAj（抖音版本变了？）：" + t);
        }
    }

    private static Object defaultOf(Class<?> t) {
        if (t == boolean.class) return Boolean.FALSE;
        if (t == int.class) return 0;
        if (t == long.class) return 0L;
        if (t == float.class) return 0f;
        if (t == double.class) return 0d;
        if (t == short.class) return (short) 0;
        if (t == byte.class) return (byte) 0;
        if (t == char.class) return (char) 0;
        return null; // void 和对象
    }

    // ------------------------------------------------------------ 2) 双击视频（实验）

    private static void hookListener(Object l) {
        if (!(l instanceof GestureDetector.OnDoubleTapListener)) return;
        Class<?> k = l.getClass();
        if (!TAP_HOOKED.add(k)) return;
        hookGestureLog(k);
        // 系统在第二次点按时依次回调：onDoubleTap(第一次点按的 DOWN) → onDoubleTapEvent(第二次的 DOWN) → … → onDoubleTapEvent(UP)。
        // 点赞可能写在其中任何一个里，所以两个都要接管。
        try {
            Method m = k.getMethod("onDoubleTap", MotionEvent.class);
            if (m.getDeclaringClass() != GestureDetector.SimpleOnGestureListener.class) { // 默认空实现不用管
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            onTap(p, true);
                        } catch (Throwable ignored) {
                        }
                    }
                });
            }
        } catch (Throwable ignored) {
        }
        try {
            Method m = k.getMethod("onDoubleTapEvent", MotionEvent.class);
            if (m.getDeclaringClass() != GestureDetector.SimpleOnGestureListener.class) {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            onTap(p, false);
                        } catch (Throwable ignored) {
                        }
                    }
                });
            }
        } catch (Throwable ignored) {
        }
    }

    /** 只记不拦：这个监听类还实现了 onSingleTapUp / onSingleTapConfirmed 的话，哪个被触发了。 */
    private static void hookGestureLog(Class<?> k) {
        for (final String name : new String[]{"onSingleTapUp", "onSingleTapConfirmed"}) {
            try {
                Method m = k.getMethod(name, MotionEvent.class);
                if (m.getDeclaringClass() == GestureDetector.SimpleOnGestureListener.class) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            String who = p.method.getDeclaringClass().getName() + "#" + name;
                            logOnce("g:" + who, "手势回调：" + who);
                        } catch (Throwable ignored) {
                        }
                    }
                });
            } catch (Throwable ignored) {
            }
        }
    }

    private static void onTap(XC_MethodHook.MethodHookParam p, boolean isDoubleTap) {
        long now = android.os.SystemClock.uptimeMillis();
        lastDtapAt = now;
        String who = p.method.getDeclaringClass().getName() + (isDoubleTap ? "#onDoubleTap" : "#onDoubleTapEvent");
        // 不管开没开关都记一次：看哪个类的哪个回调处理了双击
        logOnce("tap:" + who, "双击监听被触发：" + who);
        if (!DanmakuHook.cfg.dtapToComment) return;
        // 双击开着：不管后面能不能打开评论，先把点赞堵住（点赞观察者在 hookObserver 里按这个时间窗拦截）
        dtapGuardUntil = now + DTAP_GUARD_MS;
        int action = -1;
        if (!isDoubleTap && p.args != null && p.args.length > 0 && p.args[0] instanceof MotionEvent) {
            action = ((MotionEvent) p.args[0]).getActionMasked();
        }
        // 第二次点按之后的移动 / 抬起：本次双击已经接管就一起拦掉
        if (!isDoubleTap && action != MotionEvent.ACTION_DOWN) {
            if (dtapBlocking && now - lastOpenAt < 3000) {
                p.setResult(Boolean.TRUE);
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) dtapBlocking = false;
            } else {
                dtapBlocking = false;
            }
            return;
        }
        // 双击开始（onDoubleTap，或 onDoubleTapEvent 的 DOWN）：同一次双击里已经有别的回调处理过就只拦截
        if (now - lastOpenAt < 800) {
            dtapBlocking = true;
            p.setResult(Boolean.TRUE);
            return;
        }
        lastOpenAt = now;
        dtapBlocking = true;
        p.setResult(Boolean.TRUE); // 返回 true = 已处理，原来的点赞逻辑不再执行
        openComments(); // 找不到评论按钮也不放行点赞：双击不点赞
    }

    // ------------------------------------------------------------ 打开评论

    /** 找到当前页的评论按钮并点一下；找不到返回 false（调用方就不拦截，点赞照常）。 */
    static boolean openComments() {
        View btn = findCommentButton();
        if (btn == null) return false;
        final View target = clickable(btn);
        // 等当前这次点击 / 手势处理完再点，避免在别人的回调里重入
        target.post(new Runnable() {
            @Override
            public void run() {
                try {
                    target.performClick();
                } catch (Throwable ignored) {
                }
            }
        });
        return true;
    }

    /** contentDescription 形如“评论24，按钮”、可见、可见面积最大的那个（相邻几个视频的右侧栏可能同时挂在界面里）。 */
    private static View findCommentButton() {
        Activity a = top.get();
        if (a == null) return null;
        View root = a.getWindow().getDecorView();
        ArrayList<View> todo = new ArrayList<>();
        todo.add(root);
        View best = null;
        long bestArea = 0;
        Rect r = new Rect();
        int seen = 0;
        while (!todo.isEmpty() && seen < 8000) {
            View v = todo.remove(todo.size() - 1);
            seen++;
            if (v.getVisibility() != View.VISIBLE) continue;
            CharSequence d = v.getContentDescription();
            if (d != null && v.isShown()) {
                String s = d.toString();
                if (s.startsWith("评论") && s.endsWith("按钮") && v.getGlobalVisibleRect(r)) {
                    long area = (long) r.width() * r.height();
                    if (area > bestArea) {
                        bestArea = area;
                        best = v;
                    }
                }
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) todo.add(g.getChildAt(i));
            }
        }
        return best;
    }

    /** 自己 → 往上 4 层 → 往下一层，找第一个有点击监听的；都没有就点它自己。 */
    private static View clickable(View btn) {
        View v = btn;
        for (int i = 0; i < 4 && v != null; i++) {
            if (v.hasOnClickListeners()) return v;
            Object p = v.getParent();
            v = p instanceof View ? (View) p : null;
        }
        if (btn instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) btn;
            for (int i = 0; i < g.getChildCount(); i++) if (g.getChildAt(i).hasOnClickListeners()) return g.getChildAt(i);
        }
        return btn;
    }

    // ------------------------------------------------------------ 诊断

    private static void logOnce(String key, String msg) {
        long now = android.os.SystemClock.uptimeMillis();
        Long last = LOGGED.get(key);
        if (last != null && now - last < 120000) return; // 同一条 2 分钟内只记一次；之后再触发会再记，免得状态文件被截断后找不到
        if (LOGGED.size() > 200) LOGGED.clear();
        LOGGED.put(key, now);
        DanmakuHook.status(msg);
    }

    /** 当前调用栈里有点击 / 触摸 / 手势事件的帧，说明是用户操作直接引起的。 */
    private static boolean fromUserInput() {
        for (StackTraceElement e : new Throwable().getStackTrace()) {
            String m = e.getMethodName();
            if (m.equals("performClick") || m.equals("onClick") || m.equals("dispatchTouchEvent") || m.equals("onTouchEvent")
                    || m.equals("onDoubleTap") || m.equals("onSingleTapUp") || m.equals("onSingleTapConfirmed")) return true;
        }
        return false;
    }

    /** 当前调用栈里最近的 14 个帧（完整类名；跳过 LSPosed 的钩子帧、默认包下的代理类、java.* 和我们自己），用来定位点赞入口。 */
    private static String callers() {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (StackTraceElement e : new Throwable().getStackTrace()) {
            String c = e.getClassName();
            if (c.indexOf('.') < 0 || c.startsWith("de.robv.") || c.startsWith("org.lsposed.") || c.startsWith("com.danmaku.")
                    || c.startsWith("java.")) continue;
            if (c.startsWith("com.ss.android.ugc.aweme.")) c = c.substring(25);
            if (n > 0) sb.append(" < ");
            sb.append(c).append('.').append(e.getMethodName());
            if (++n >= 9) break;
        }
        return sb.toString();
    }
}
