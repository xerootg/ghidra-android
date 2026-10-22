/*
 * Copyright 2026 Ghidra for Android contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.xerootg.ghidroid;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * The window tray: a chip in the top-right corner, beside Ghidra's close
 * button, showing how many Ghidra windows are open and the active one.
 * Tapping it lists the windows; tapping one brings it to the front. The same
 * list, with a moving highlight, is the Alt+` switcher.
 */
final class WindowTray {

    /** One Ghidra window, as published by the toolkit's WindowList. */
    static final class Win {
        final int id;
        final char kind; // f frame, d dialog, m modal dialog
        final boolean active;
        final String title;
        /** Content area on Ghidra's screen. */
        final int x, y, w, h;

        Win(int id, char kind, boolean active, String title, int x, int y, int w, int h) {
            this.id = id;
            this.kind = kind;
            this.active = active;
            this.title = title;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
    }

    static List<Win> parse(String text) {
        List<Win> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        for (String line : text.split("\n")) {
            String[] f = line.split("\t", 5);
            if (f.length == 5) {
                try {
                    String[] r = f[3].split(",");
                    out.add(new Win(Integer.parseInt(f[0]), f[1].charAt(0), f[2].equals("1"), f[4],
                            Integer.parseInt(r[0]), Integer.parseInt(r[1]),
                            Integer.parseInt(r[2]), Integer.parseInt(r[3])));
                } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                    // Skip a malformed line.
                }
            }
        }
        return out;
    }

    private static final int BG = 0xE6202124;
    private static final int HIGHLIGHT = 0xFF2E436E;
    private static final int ACCENT = 0xFF8AB4F8;

    private final Context ctx;
    private final IntConsumer front;
    private final TextView chip;
    private List<Win> windows = new ArrayList<>();
    private PopupWindow popup;
    private LinearLayout list;
    private int selected = -1;
    /** Separate windows: Android lists the windows, and the chip would cover the menu bar. */
    private boolean chipHidden;

    WindowTray(Context ctx, IntConsumer front) {
        this.ctx = ctx;
        this.front = front;
        chip = new TextView(ctx);
        chip.setTextColor(Color.WHITE);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        chip.setSingleLine(true);
        chip.setEllipsize(TextUtils.TruncateAt.END);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(12), 0, dp(12), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BG);
        bg.setCornerRadius(dp(14));
        chip.setBackground(bg);
        chip.setOnClickListener(v -> toggle());
        chip.setVisibility(View.GONE);
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics()));
    }

    /**
     * Adds the chip to the activity's root, aligned with Ghidra's title bar:
     * {@code barPx} tall, {@code rightPx} in from the right (Ghidra's close
     * button).
     */
    void attach(FrameLayout root, int barPx, int rightPx) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Math.max(barPx - dp(6), dp(28)), Gravity.TOP | Gravity.END);
        lp.setMargins(0, dp(3), rightPx, 0);
        chip.setMaxWidth(ctx.getResources().getDisplayMetrics().widthPixels * 2 / 5);
        root.addView(chip, lp);
    }

    void setWindows(List<Win> w) {
        windows = w;
        Win active = null;
        for (Win x : w) {
            if (x.active) {
                active = x;
            }
        }
        if (w.isEmpty() || chipHidden) {
            chip.setVisibility(View.GONE);
        } else {
            chip.setText("⧉ " + w.size() + (active != null ? "   " + active.title : "") + "  ▾");
            chip.setVisibility(View.VISIBLE);
        }
        if (popup != null && popup.isShowing()) {
            fill();
        }
    }

    void setChipHidden(boolean hidden) {
        chipHidden = hidden;
        setWindows(windows);
    }

    boolean isShowing() {
        return popup != null && popup.isShowing();
    }

    void toggle() {
        if (isShowing()) {
            dismiss();
        } else {
            selected = -1;
            show();
        }
    }

    void dismiss() {
        if (popup != null) {
            popup.dismiss();
        }
        selected = -1;
    }

    private int activeIndex() {
        for (int i = 0; i < windows.size(); i++) {
            if (windows.get(i).active) {
                return i;
            }
        }
        return 0;
    }

    /** Alt+`: open with the next (or previous) window highlighted, or move the highlight. */
    void cycle(boolean back) {
        if (windows.isEmpty()) {
            return;
        }
        int n = windows.size();
        int from = selected >= 0 ? selected : activeIndex();
        selected = ((from + (back ? -1 : 1)) % n + n) % n;
        if (isShowing()) {
            fill();
        } else {
            show();
        }
    }

    /** Alt released: bring the highlighted window forward. Returns false if nothing was highlighted. */
    boolean commit() {
        if (selected < 0 || selected >= windows.size()) {
            dismiss();
            return false;
        }
        int id = windows.get(selected).id;
        dismiss();
        front.accept(id);
        return true;
    }

    private void show() {
        list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(6), dp(6), dp(6), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF0202124);
        bg.setCornerRadius(dp(14));
        list.setBackground(bg);
        list.setElevation(dp(8));
        fill();
        popup = new PopupWindow(list, dp(440), ViewGroup.LayoutParams.WRAP_CONTENT, false);
        popup.setOutsideTouchable(true);
        popup.setElevation(dp(8));
        popup.setOnDismissListener(() -> selected = -1);
        popup.showAsDropDown(chip, 0, dp(6), Gravity.END);
    }

    private void fill() {
        list.removeAllViews();
        TextView head = new TextView(ctx);
        head.setText(R.string.tray_heading);
        head.setTextColor(0xFFB0B4BA);
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        head.setPadding(dp(12), dp(6), dp(12), dp(6));
        list.addView(head);
        for (int i = 0; i < windows.size(); i++) {
            Win w = windows.get(i);
            TextView row = new TextView(ctx);
            String icon = w.kind == 'f' ? "▢" : w.kind == 'm' ? "◆" : "◇";
            row.setText(icon + "   " + w.title);
            row.setTextColor(w.active ? ACCENT : Color.WHITE);
            row.setTypeface(w.active ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            row.setSingleLine(true);
            row.setEllipsize(TextUtils.TruncateAt.END);
            row.setMinHeight(dp(48));
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), 0, dp(12), 0);
            GradientDrawable rbg = new GradientDrawable();
            rbg.setCornerRadius(dp(10));
            rbg.setColor(i == selected ? HIGHLIGHT : Color.TRANSPARENT);
            row.setBackground(rbg);
            int id = w.id;
            row.setOnClickListener(v -> {
                dismiss();
                front.accept(id);
            });
            list.addView(row);
        }
        if (selected >= 0) {
            TextView hint = new TextView(ctx);
            hint.setText(R.string.tray_cycle_hint);
            hint.setTextColor(0xFFB0B4BA);
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            hint.setPadding(dp(12), dp(6), dp(12), dp(4));
            list.addView(hint);
        }
    }
}
