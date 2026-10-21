package com.danmaku.randcolor;

import java.util.Calendar;

/**
 * 发布信息那一行的模板：把 {日期} {时间} {地点} {省} {市} 换成视频的发布日期、时间、地点，别的文字原样保留。
 * 设置页的预览和抖音里的钩子共用这一个类，所以只用 java.util，不依赖 Android / Xposed。
 *
 *  - {日期} = 2026-10-19，{时间} = 11:50:00（按手机时区），{省} = IP 属地，{市} = 城市简称。
 *  - {地点} = 省 + 空格 + 市；省和市一样或互相包含（北京 / 北京）只写一次；没有的就是空。
 *  - 模板里写两个字符 \ 和 n 表示换行。认不出的 {…} 原样显示。
 *  - 结果去掉首尾空白；模板为空用默认模板；结果是空返回 null。
 *  - 不限制长度，也不截断：一行放不下由显示的 TextView 自己换行。
 */
final class PubFormat {
    private PubFormat() {}

    static final String DEFAULT = "{日期} {时间} {地点}";

    static String render(String tpl, long sec, String prov, String city) {
        if (tpl == null || tpl.trim().isEmpty()) tpl = DEFAULT;
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(sec * 1000L);
        StringBuilder d = new StringBuilder(10);
        d.append(c.get(Calendar.YEAR)).append('-');
        two(d, c.get(Calendar.MONTH) + 1).append('-');
        two(d, c.get(Calendar.DAY_OF_MONTH));
        StringBuilder t = new StringBuilder(8);
        two(t, c.get(Calendar.HOUR_OF_DAY)).append(':');
        two(t, c.get(Calendar.MINUTE)).append(':');
        two(t, c.get(Calendar.SECOND));
        String p = prov == null ? "" : prov.trim();
        String ct = city == null ? "" : city.trim();
        if (!ct.isEmpty() && !p.isEmpty() && (p.contains(ct) || ct.contains(p))) ct = ""; // 北京 / 北京 只写一次
        String place = p.isEmpty() ? ct : (ct.isEmpty() ? p : p + " " + ct);

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
                    else if (key.equals("地点")) val = place;
                    else if (key.equals("省")) val = p;
                    else if (key.equals("市")) val = ct;
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
