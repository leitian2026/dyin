package com.danmaku.randcolor;

import java.util.Calendar;

/**
 * 直播间开播时间那一行的模板：把 {日期} {时间} {月日} 换成开播时间，别的文字原样保留。
 * 设置页的预览和抖音里的钩子共用这一个类，所以只用 java.util，不依赖 Android / Xposed。
 *
 *  - {日期} = 2026-10-22，{时间} = 07:22:36，{月日} = 10-22（按手机时区）。
 *  - 模板里写两个字符 \ 和 n 表示换行。认不出的 {…} 原样显示。
 *  - 模板为空用默认模板；结果去掉首尾空白，是空返回 null。
 *  - 没有「已开播多久」这一项：钩子没有定时器，那个数字不会自己走，显示出来会是错的。
 */
final class LiveFormat {
    private LiveFormat() {}

    static final String DEFAULT = "开播 {日期} {时间}";

    static String render(String tpl, long sec) {
        if (tpl == null || tpl.trim().isEmpty()) tpl = DEFAULT;
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(sec * 1000L);
        StringBuilder md = new StringBuilder(5);
        two(md, c.get(Calendar.MONTH) + 1).append('-');
        two(md, c.get(Calendar.DAY_OF_MONTH));
        StringBuilder d = new StringBuilder(10);
        d.append(c.get(Calendar.YEAR)).append('-').append(md);
        StringBuilder t = new StringBuilder(8);
        two(t, c.get(Calendar.HOUR_OF_DAY)).append(':');
        two(t, c.get(Calendar.MINUTE)).append(':');
        two(t, c.get(Calendar.SECOND));

        int n = tpl.length();
        StringBuilder out = new StringBuilder(n + 24);
        for (int i = 0; i < n; ) {
            char ch = tpl.charAt(i);
            if (ch == '{') {
                int e = tpl.indexOf('}', i + 1);
                if (e > i) {
                    String key = tpl.substring(i + 1, e);
                    String val = null;
                    if (key.equals("日期")) val = d.toString();
                    else if (key.equals("时间")) val = t.toString();
                    else if (key.equals("月日")) val = md.toString();
                    if (val != null) {
                        out.append(val);
                        i = e + 1;
                        continue;
                    }
                }
            } else if (ch == '\\' && i + 1 < n && tpl.charAt(i + 1) == 'n') {
                out.append('\n');
                i += 2;
                continue;
            }
            out.append(ch);
            i++;
        }
        String s = out.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static StringBuilder two(StringBuilder b, int v) {
        if (v < 10) b.append('0');
        return b.append(v);
    }
}
