package com.android.launcher3;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Minimal ADW/Nova-compatible icon pack reader. */
final class IconPackManager {
    static final String PREF_PACKAGE = "pref_icon_pack_package";
    static final String PREF_LABEL = "pref_icon_pack_label";
    private static final String APPFILTER = "appfilter";
    private final Context context;
    private final PackageManager pm;
    private String loadedPackage;
    private Map<String, String> icons = new HashMap<String, String>();

    IconPackManager(Context context) {
        this.context = context.getApplicationContext();
        pm = context.getPackageManager();
    }

    static final class Pack {
        final String packageName;
        final String label;
        Pack(String p, String l) { packageName = p; label = l; }
        @Override public String toString() { return label; }
    }

    List<Pack> getInstalledPacks() {
        List<Pack> result = new ArrayList<Pack>();
        for (ApplicationInfo app : pm.getInstalledApplications(0)) {
            try {
                Resources r = pm.getResourcesForApplication(app);
                if (r.getIdentifier(APPFILTER, "xml", app.packageName) != 0) {
                    CharSequence label = pm.getApplicationLabel(app);
                    result.add(new Pack(app.packageName, label == null ? app.packageName : label.toString()));
                }
            } catch (Exception ignored) { }
        }
        return result;
    }

    synchronized Drawable getIcon(ComponentName component) {
        String pack = context.getSharedPreferences(LauncherFiles.SHARED_PREFERENCES_KEY,
                Context.MODE_PRIVATE).getString(PREF_PACKAGE, "");
        if (TextUtils.isEmpty(pack) || component == null) return null;
        if (!pack.equals(loadedPackage)) loadPack(pack);
        String drawableName = icons.get(component.flattenToString());
        if (drawableName == null) drawableName = icons.get(component.getPackageName());
        if (drawableName == null) return null;
        try {
            Resources r = pm.getResourcesForApplication(pack);
            int id = r.getIdentifier(drawableName, "drawable", pack);
            if (id == 0) id = r.getIdentifier(drawableName, "mipmap", pack);
            return id == 0 ? null : r.getDrawable(id);
        } catch (Exception e) { return null; }
    }

    private void loadPack(String packageName) {
        loadedPackage = packageName;
        icons = new HashMap<String, String>();
        InputStream in = null;
        try {
            Resources r = pm.getResourcesForApplication(packageName);
            int id = r.getIdentifier(APPFILTER, "xml", packageName);
            if (id == 0) return;
            in = r.openRawResource(id);
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(in, "UTF-8");
            int event;
            while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (event != XmlPullParser.START_TAG || !"item".equals(parser.getName())) continue;
                String component = parser.getAttributeValue(null, "component");
                String drawable = parser.getAttributeValue(null, "drawable");
                if (component == null || drawable == null) continue;
                if (component.startsWith("ComponentInfo{") && component.endsWith("}")) {
                    component = component.substring(14, component.length() - 1);
                    int slash = component.indexOf('/');
                    if (slash > 0) {
                        String pkg = component.substring(0, slash);
                        String cls = component.substring(slash + 1);
                        if (cls.startsWith(".")) cls = pkg + cls;
                        icons.put(new ComponentName(pkg, cls).flattenToString(), drawable);
                        icons.put(pkg, drawable);
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) { }
        }
    }
}
