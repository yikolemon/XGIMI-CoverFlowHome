package com.yikolemon.xgimihome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Large two-section application library, created on demand and fully released on dismissal. */
final class HomeLibraryDialog {
    private final Activity activity;
    private final Supplier<List<String>> order;
    private final Predicate<List<String>> saveOrder;
    private final Runnable changed, dismissed;
    private final float density;
    private final LinearLayout panel;
    private final PopupWindow popup;
    // 16 * 80 * 80 * 4 = 400 KiB. No original-size icon or portrait artwork is retained here.
    private final LruCache<String, Bitmap> icons = new LruCache<>(16);
    private final Map<String, ResolveInfo> installed = new LinkedHashMap<>();
    private final Map<String, String> labels = new HashMap<>();
    private final List<String> home = new ArrayList<>(), others = new ArrayList<>();
    private final Typeface font = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private RecyclerView homeList, otherGrid;
    private TextView hint;
    private PopupWindow actions;
    private String selected, moving;
    private boolean closing;
    private int columns;
    private final int width, height;

    HomeLibraryDialog(Activity activity, Supplier<List<String>> order,
                      Predicate<List<String>> saveOrder, Runnable changed, Runnable dismissed) {
        this.activity = activity;
        this.order = order;
        this.saveOrder = saveOrder;
        this.changed = changed;
        this.dismissed = dismissed;
        density = activity.getResources().getDisplayMetrics().density;
        View decor = activity.getWindow().getDecorView();
        width = Math.min(dp(1120), Math.max(dp(200), decor.getWidth() - dp(64)));
        height = Math.min(dp(660), Math.max(dp(240), decor.getHeight() - dp(48)));
        columns = Math.max(2, Math.min(6, (width - dp(32)) / dp(150)));
        panel = new LinearLayout(activity) {
            @Override public boolean dispatchKeyEvent(KeyEvent event) {
                if (moving != null && event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                    if (event.getAction() == KeyEvent.ACTION_UP) finishReorder(false);
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }
        };
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(12), dp(16), dp(12));
        panel.setBackground(surface(0xF0181C26, 24));
        popup = new PopupWindow(panel, width, height, true) {
            @Override public void dismiss() {
                // PopupWindow consumes Back before its child views. Cancel the draft without closing the library.
                if (moving != null && !closing) { finishReorder(false); return; }
                super.dismiss();
            }
        };
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setOnDismissListener(() -> {
            moving = null; // An unfinished draft was never written to disk.
            dismissActions();
            releaseLists();
            panel.removeAllViews();
            icons.evictAll();
            installed.clear();
            labels.clear();
            dismissed.run();
        });
    }

    void show() {
        refresh();
        popup.showAtLocation(activity.getWindow().getDecorView(), Gravity.CENTER, 0, 0);
        View container = panel.getRootView();
        if (container.getLayoutParams() instanceof WindowManager.LayoutParams) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) container.getLayoutParams();
            params.flags |= WindowManager.LayoutParams.FLAG_DIM_BEHIND;
            params.dimAmount = 0.45f;
            activity.getWindowManager().updateViewLayout(container, params);
        }
        // No full-panel opacity animation: it can allocate a large temporary offscreen render target.
    }

    void close() {
        closing = true;
        moving = null;
        dismissActions();
        popup.dismiss();
    }

    void refresh() {
        moving = null;
        dismissActions();
        installed.clear();
        labels.clear();
        PackageManager pm = activity.getPackageManager();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo info : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                if (info.activityInfo == null || !info.activityInfo.exported) continue;
                String pkg = info.activityInfo.packageName;
                if (!pkg.equals(activity.getPackageName())) installed.putIfAbsent(pkg, info);
            }
        }
        home.clear();
        home.addAll(LibrarySections.home(installed.keySet(), order.get()));
        List<String> alphabetic = new ArrayList<>(installed.keySet());
        Collator collator = Collator.getInstance();
        alphabetic.sort((a, b) -> {
            int byLabel = collator.compare(label(a), label(b));
            return byLabel == 0 ? a.compareTo(b) : byLabel;
        });
        others.clear();
        others.addAll(LibrarySections.others(alphabetic, home));
        build();
    }

    private String label(String pkg) {
        String cached = labels.get(pkg);
        if (cached != null) return cached;
        String value = pkg;
        try { value = installed.get(pkg).loadLabel(activity.getPackageManager()).toString(); }
        catch (RuntimeException unavailable) { /* Package may have just been removed. */ }
        labels.put(pkg, value);
        return value;
    }

    private int dp(int value) { return Math.round(value * density); }

    private GradientDrawable surface(int color, int radius) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(radius));
        bg.setStroke(dp(1), 0x25FFFFFF);
        return bg;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(font);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private void build() {
        releaseLists();
        panel.removeAllViews();
        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(text(activity.getString(R.string.app_library), 24, Color.WHITE),
                new LinearLayout.LayoutParams(0, dp(38), 1));
        header.addView(text(activity.getResources().getQuantityString(R.plurals.library_app_count,
                installed.size(), installed.size()), 13, 0x99FFFFFF));
        panel.addView(header);
        hint = text(activity.getString(R.string.home_library_hint), 13, 0xB3FFFFFF);
        panel.addView(hint, new LinearLayout.LayoutParams(-1, dp(28)));
        section(R.string.home_library_home, home.size());
        if (home.isEmpty()) {
            panel.addView(text(activity.getString(R.string.home_library_empty), 15, 0x99FFFFFF),
                    new LinearLayout.LayoutParams(-1, dp(70)));
        } else {
            homeList = list(true);
            panel.addView(homeList, new LinearLayout.LayoutParams(-1, dp(146)));
        }
        section(R.string.home_library_others, others.size());
        if (others.isEmpty()) {
            panel.addView(text(activity.getString(R.string.home_library_all_added), 15, 0x99FFFFFF),
                    new LinearLayout.LayoutParams(-1, 0, 1));
        } else {
            otherGrid = list(false);
            panel.addView(otherGrid, new LinearLayout.LayoutParams(-1, 0, 1));
        }
        if (home.isEmpty() && others.isEmpty()) {
            panel.setFocusableInTouchMode(true);
            panel.requestFocus();
        } else {
            if (!home.contains(selected) && !others.contains(selected))
                selected = home.isEmpty() ? others.get(0) : home.get(0);
            focus(selected);
        }
    }

    private void section(int title, int count) {
        TextView label = text(activity.getString(title, count), 16, 0xE6FFFFFF);
        label.setPadding(dp(4), dp(4), 0, 0);
        panel.addView(label, new LinearLayout.LayoutParams(-1, dp(32)));
    }

    private RecyclerView list(boolean isHome) {
        RecyclerView list = new RecyclerView(activity);
        list.setLayoutManager(isHome ? new LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false)
                : new GridLayoutManager(activity, columns));
        list.getLayoutManager().setItemPrefetchEnabled(false);
        list.setItemAnimator(null);
        list.setItemViewCacheSize(0);
        list.getRecycledViewPool().setMaxRecycledViews(0, 3);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setPadding(dp(3), dp(4), dp(3), dp(4));
        list.setAdapter(new Tiles(isHome));
        return list;
    }

    private void release(RecyclerView list) {
        if (list == null) return;
        list.stopScroll();
        list.setAdapter(null);
        list.getRecycledViewPool().clear();
    }

    private void releaseLists() { release(homeList); release(otherGrid); homeList = null; otherGrid = null; }

    private void focus(String pkg) {
        boolean isHome = home.contains(pkg);
        RecyclerView list = isHome ? homeList : otherGrid;
        List<String> entries = isHome ? home : others;
        int position = entries.indexOf(pkg);
        if (list == null || position < 0) return;
        list.scrollToPosition(position);
        list.post(() -> {
            if (!popup.isShowing() || list.getAdapter() == null || actions != null) return;
            RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(position);
            if (holder != null) holder.itemView.requestFocus();
            else list.post(() -> {
                if (!popup.isShowing() || list.getAdapter() == null || actions != null) return;
                RecyclerView.ViewHolder next = list.findViewHolderForAdapterPosition(position);
                if (next != null) next.itemView.requestFocus();
            });
        });
    }

    private void changeHome(String pkg, boolean add) {
        if (!LibraryApps.save(activity, pkg, add)) {
            Toast.makeText(activity, R.string.library_save_failed, Toast.LENGTH_LONG).show();
            return;
        }
        selected = pkg;
        dismissActions();
        changed.run();
        refresh();
    }

    private void beginReorder(String pkg) {
        moving = pkg;
        selected = pkg;
        dismissActions();
        hint.setText(R.string.home_library_sort_hint);
        homeList.getAdapter().notifyDataSetChanged();
        focus(pkg);
    }

    private void shift(int direction) {
        int from = home.indexOf(moving), to = from + direction;
        if (from < 0 || to < 0 || to >= home.size()) return;
        Collections.swap(home, from, to);
        RecyclerView.Adapter<?> adapter = homeList.getAdapter();
        adapter.notifyItemMoved(from, to);
        adapter.notifyItemChanged(from);
        adapter.notifyItemChanged(to);
        focus(moving);
    }

    private void finishReorder(boolean save) {
        if (moving == null) return;
        selected = moving;
        if (save && !saveOrder.test(new ArrayList<>(home))) {
            Toast.makeText(activity, R.string.library_save_failed, Toast.LENGTH_LONG).show();
            return;
        }
        moving = null;
        refresh();
    }

    private void open(String pkg) {
        ResolveInfo info = installed.get(pkg);
        if (info == null) { refresh(); return; }
        Intent intent = new Intent(Intent.ACTION_MAIN).setComponent(
                new ComponentName(pkg, info.activityInfo.name))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        close();
        try { activity.startActivity(intent); }
        catch (ActivityNotFoundException | SecurityException unavailable) {
            Toast.makeText(activity, R.string.cover_flow_unavailable, Toast.LENGTH_SHORT).show();
        }
    }

    private void dismissActions() {
        PopupWindow current = actions;
        actions = null;
        if (current != null) current.dismiss();
    }

    private void showActions(String pkg, View anchor) {
        if (moving != null) return;
        selected = pkg;
        dismissActions();
        LinearLayout menu = new LinearLayout(activity);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(dp(6), dp(6), dp(6), dp(6));
        menu.setBackground(surface(0xFC252B37, 16));
        PopupWindow window = new PopupWindow(menu, dp(240), -2, true);
        actions = window;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setOutsideTouchable(true);
        window.setOnDismissListener(() -> {
            if (actions == window) actions = null;
            focus(selected);
        });
        action(menu, R.string.library_open, () -> open(pkg));
        if (home.contains(pkg)) {
            action(menu, R.string.home_library_sort, () -> beginReorder(pkg));
            action(menu, R.string.library_remove_shortcut, () -> changeHome(pkg, false));
        } else action(menu, R.string.library_add_home, () -> changeHome(pkg, true));
        action(menu, R.string.library_app_info, () -> {
            close();
            try { activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg))); }
            catch (ActivityNotFoundException | SecurityException unavailable) {
                Toast.makeText(activity, R.string.cover_flow_unavailable, Toast.LENGTH_SHORT).show();
            }
        });
        int[] location = new int[2], origin = new int[2];
        anchor.getLocationOnScreen(location);
        panel.getLocationOnScreen(origin);
        int menuHeight = menu.getChildCount() * dp(46) + dp(12);
        int x = Math.max(origin[0] + dp(8), Math.min(location[0], origin[0] + width - dp(248)));
        int y = Math.max(origin[1] + dp(8), Math.min(location[1], origin[1] + height - menuHeight - dp(8)));
        window.showAtLocation(activity.getWindow().getDecorView(), Gravity.TOP | Gravity.START, x, y);
        menu.getChildAt(0).requestFocus();
    }

    private void action(LinearLayout menu, int title, Runnable run) {
        TextView button = text(activity.getString(title), 15, Color.WHITE);
        button.setPadding(dp(14), 0, dp(14), 0);
        button.setFocusableInTouchMode(true);
        GradientDrawable bg = surface(Color.TRANSPARENT, 10);
        button.setBackground(bg);
        button.setOnFocusChangeListener((v, focused) -> bg.setColor(focused ? 0x44FFFFFF : Color.TRANSPARENT));
        button.setOnClickListener(v -> run.run());
        button.setOnKeyListener((v, code, event) -> event.getAction() == KeyEvent.ACTION_DOWN
                && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_UP && menu.indexOfChild(v) == 0
                || code == KeyEvent.KEYCODE_DPAD_DOWN && menu.indexOfChild(v) == menu.getChildCount() - 1));
        menu.addView(button, new LinearLayout.LayoutParams(-1, dp(46)));
    }

    private Bitmap icon(String pkg) {
        Bitmap cached = icons.get(pkg);
        if (cached != null) return cached;
        Bitmap bitmap = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888);
        Drawable original;
        try { original = installed.get(pkg).loadIcon(activity.getPackageManager()); }
        catch (RuntimeException unavailable) { original = activity.getDrawable(R.drawable.ic_apps); }
        if (original != null) {
            android.graphics.Rect previous = original.copyBounds();
            original.setBounds(0, 0, 80, 80);
            original.draw(new Canvas(bitmap));
            original.setBounds(previous);
        }
        icons.put(pkg, bitmap);
        return bitmap;
    }

    private final class Tile extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView name, detail, rank;
        final GradientDrawable background;
        Tile(FrameLayout frame, ImageView icon, TextView name, TextView detail, TextView rank, GradientDrawable background) {
            super(frame); this.icon = icon; this.name = name; this.detail = detail; this.rank = rank; this.background = background;
        }
        void style(boolean focused, boolean carried) {
            background.setColor(carried ? 0x443CA9B7 : focused ? 0x25FFFFFF : 0x0CFFFFFF);
            background.setStroke(dp(focused || carried ? 2 : 1), carried ? 0xFF8DE5EA : focused ? 0xEEFFFFFF : 0x22FFFFFF);
        }
    }

    private final class Tiles extends RecyclerView.Adapter<Tile> {
        final boolean isHome;
        Tiles(boolean isHome) { this.isHome = isHome; }
        List<String> entries() { return isHome ? home : others; }

        @NonNull @Override public Tile onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            FrameLayout frame = new FrameLayout(activity);
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(isHome ? dp(164) : (width - dp(38)) / columns - dp(10),
                    dp(isHome ? 134 : 123));
            params.setMargins(dp(5), dp(3), dp(5), dp(3));
            frame.setLayoutParams(params);
            frame.setFocusableInTouchMode(true);
            frame.setPadding(dp(10), dp(8), dp(10), dp(6));
            GradientDrawable background = surface(0x0CFFFFFF, 15);
            frame.setBackground(background);
            LinearLayout content = new LinearLayout(activity);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setGravity(Gravity.CENTER);
            frame.addView(content, new FrameLayout.LayoutParams(-1, -1));
            ImageView icon = new ImageView(activity);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            content.addView(icon, new LinearLayout.LayoutParams(dp(isHome ? 57 : 48), dp(isHome ? 57 : 48)));
            TextView name = text("", 14, Color.WHITE);
            name.setGravity(Gravity.CENTER);
            content.addView(name, new LinearLayout.LayoutParams(-1, dp(29)));
            TextView detail = text("", 11, 0x99FFFFFF);
            detail.setGravity(Gravity.CENTER);
            content.addView(detail, new LinearLayout.LayoutParams(-1, dp(18)));
            TextView rank = text("", 12, 0xDDFFFFFF);
            rank.setGravity(Gravity.CENTER);
            FrameLayout.LayoutParams rankParams = new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP | Gravity.START);
            frame.addView(rank, rankParams);
            return new Tile(frame, icon, name, detail, rank, background);
        }

        @Override public void onBindViewHolder(@NonNull Tile holder, int position) {
            String pkg = entries().get(position);
            holder.icon.setImageBitmap(icon(pkg));
            holder.name.setText(label(pkg));
            holder.rank.setText(isHome ? Integer.toString(position + 1) : "");
            holder.detail.setText(isHome ? moving != null && pkg.equals(moving) ? R.string.home_library_moving
                    : R.string.library_on_home : R.string.home_library_other_detail);
            holder.style(holder.itemView.hasFocus(), pkg.equals(moving));
            holder.itemView.setContentDescription((isHome ? (position + 1) + ". " : "") + label(pkg));
            holder.itemView.setOnFocusChangeListener((view, focused) -> {
                holder.style(focused, pkg.equals(moving));
                if (focused) selected = pkg;
            });
            holder.itemView.setOnClickListener(v -> {
                if (moving != null) finishReorder(true);
                else open(pkg);
            });
            holder.itemView.setOnLongClickListener(v -> { showActions(pkg, v); return true; });
            holder.itemView.setOnKeyListener((v, key, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                int index = entries().indexOf(pkg);
                if (index < 0) return true;
                if (isHome) {
                    if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        int delta = key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1;
                        if (moving != null) shift(delta);
                        else if (index + delta >= 0 && index + delta < home.size()) focus(home.get(index + delta));
                        return true;
                    }
                    if (key == KeyEvent.KEYCODE_DPAD_UP) return true;
                    if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
                        int target = LibrarySections.verticalTarget(index, others.size(), columns);
                        if (moving == null && target >= 0) focus(others.get(target));
                        return true;
                    }
                } else {
                    if (key == KeyEvent.KEYCODE_DPAD_UP) {
                        if (index >= columns) focus(others.get(index - columns));
                        else {
                            int target = LibrarySections.verticalTarget(index, home.size(), columns);
                            if (target >= 0) focus(home.get(target));
                        }
                        return true;
                    }
                    if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
                        if (index / columns < (others.size() - 1) / columns)
                            focus(others.get(Math.min(index + columns, others.size() - 1)));
                        return true;
                    }
                    if (key == KeyEvent.KEYCODE_DPAD_LEFT) {
                        if (index % columns != 0) focus(others.get(index - 1));
                        return true;
                    }
                    if (key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        if (index % columns != columns - 1 && index < others.size() - 1) focus(others.get(index + 1));
                        return true;
                    }
                }
                return false;
            });
        }

        @Override public void onViewRecycled(@NonNull Tile holder) { holder.icon.setImageDrawable(null); }
        @Override public int getItemCount() { return entries().size(); }
    }
}
