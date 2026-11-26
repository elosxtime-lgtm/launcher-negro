/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.launcher3;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.Log;

import org.xmlpull.v1.XmlPullParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Manages the detection and application of icon packs.
 *
 * An icon pack is an app that exposes a "theme" activity (intent MAIN + category
 * {@code com.fede.launcher.THEME_ICONPACK} or {@code org.adw.launcher.THEME_ICONPACK})
 * and provides an {@code appfilter.xml} mapping components to drawables.
 */
public class IconPackManager {

    private static final String TAG = "IconPackManager";

    public static final String ICON_PACK_PREF_KEY = "pref_icon_pack";
    public static final String DEFAULT_ICON_PACK = "";

    private static final String CATEGORY_ICON_PACK_FEDE = "com.fede.launcher.THEME_ICONPACK";
    private static final String CATEGORY_ICON_PACK_ADW = "org.adw.launcher.THEME_ICONPACK";
    private static final String[] ICON_PACK_CATEGORIES = {
            CATEGORY_ICON_PACK_FEDE, CATEGORY_ICON_PACK_ADW
    };
    private static final String[] ICON_PACK_METADATA_KEYS = {
            CATEGORY_ICON_PACK_FEDE, CATEGORY_ICON_PACK_ADW
    };

    private final Context mContext;
    private final PackageManager mPackageManager;

    // Cached state for the currently selected icon pack.
    private String mCurrentIconPack;
    private Resources mIconPackResources;
    private HashMap<String, String> mComponentToDrawable;

    public IconPackManager(Context context) {
        mContext = context;
        mPackageManager = context.getPackageManager();
    }

    private SharedPreferences getPrefs() {
        return mContext.getSharedPreferences(
                LauncherFiles.SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE);
    }

    /**
     * @return the package name of the selected icon pack, or an empty string for default icons.
     */
    public String getSelectedIconPack() {
        return getPrefs().getString(ICON_PACK_PREF_KEY, DEFAULT_ICON_PACK);
    }

    /**
     * @return the list of installed icon packs.
     */
    public List<IconPackInfo> getInstalledIconPacks() {
        List<IconPackInfo> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String category : ICON_PACK_CATEGORIES) {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(category);
            List<ResolveInfo> resolveInfos =
                    mPackageManager.queryIntentActivities(intent, 0);
            if (resolveInfos == null) {
                continue;
            }
            for (ResolveInfo ri : resolveInfos) {
                if (ri.activityInfo == null) {
                    continue;
                }
                String pkg = ri.activityInfo.packageName;
                if (seen.add(pkg)) {
                    result.add(new IconPackInfo(ri, mPackageManager));
                }
            }
        }
        return result;
    }

    /**
     * Returns the icon pack drawable for the given component, or null if none is available.
     */
    public Drawable getDrawableForComponent(ComponentName component, int iconDpi) {
        String selected = getSelectedIconPack();
        if (TextUtils.isEmpty(selected)) {
            return null;
        }
        if (!selected.equals(mCurrentIconPack)) {
            loadIconPack(selected);
        }
        if (mComponentToDrawable == null || mIconPackResources == null) {
            return null;
        }
        String drawableName = mComponentToDrawable.get(component.flattenToShortString());
        if (drawableName == null) {
            return null;
        }
        int id = mIconPackResources.getIdentifier(drawableName, "drawable", selected);
        if (id == 0) {
            return null;
        }
        try {
            return mIconPackResources.getDrawableForDensity(id, iconDpi);
        } catch (Exception e) {
            Log.e(TAG, "Error loading drawable " + drawableName, e);
            return null;
        }
    }

    private void loadIconPack(String packageName) {
        mCurrentIconPack = packageName;
        mComponentToDrawable = null;
        mIconPackResources = null;

        try {
            mIconPackResources = mPackageManager.getResourcesForApplication(packageName);
        } catch (NameNotFoundException e) {
            Log.d(TAG, "Icon pack not found: " + packageName);
            return;
        }

        int filterResId = findAppFilterResource(packageName);
        if (filterResId != 0) {
            parseAppFilter(filterResId);
        }
    }

    private int findAppFilterResource(String packageName) {
        ActivityInfo ai = getIconPackActivity(packageName);
        if (ai != null && ai.metaData != null) {
            for (String key : ICON_PACK_METADATA_KEYS) {
                int resId = ai.metaData.getInt(key, 0);
                if (resId != 0) {
                    return resId;
                }
            }
        }
        // Fallback: look for res/xml/appfilter.xml
        return mIconPackResources.getIdentifier("appfilter", "xml", packageName);
    }

    private ActivityInfo getIconPackActivity(String packageName) {
        for (String category : ICON_PACK_CATEGORIES) {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.setPackage(packageName);
            intent.addCategory(category);
            List<ResolveInfo> resolveInfos =
                    mPackageManager.queryIntentActivities(intent, PackageManager.GET_META_DATA);
            if (resolveInfos != null && !resolveInfos.isEmpty()
                    && resolveInfos.get(0).activityInfo != null) {
                return resolveInfos.get(0).activityInfo;
            }
        }
        return null;
    }

    private void parseAppFilter(int resId) {
        XmlResourceParser parser = null;
        try {
            parser = mIconPackResources.getXml(resId);
            int type;
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (type == XmlPullParser.START_TAG && "item".equals(parser.getName())) {
                    String component = parser.getAttributeValue(null, "component");
                    String drawable = parser.getAttributeValue(null, "drawable");
                    if (component != null && drawable != null) {
                        int start = component.indexOf('{');
                        int end = component.indexOf('}');
                        if (start >= 0 && end > start) {
                            component = component.substring(start + 1, end);
                        }
                        if (mComponentToDrawable == null) {
                            mComponentToDrawable = new HashMap<>();
                        }
                        mComponentToDrawable.put(component, drawable);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing appfilter", e);
        } finally {
            if (parser != null) {
                parser.close();
            }
        }
    }

    /**
     * Represents a single installed icon pack.
     */
    public static class IconPackInfo {
        public final String packageName;
        public final CharSequence label;
        public final Drawable icon;

        public IconPackInfo(ResolveInfo resolveInfo, PackageManager pm) {
            packageName = resolveInfo.activityInfo.packageName;
            label = resolveInfo.loadLabel(pm);
            icon = resolveInfo.loadIcon(pm);
        }
    }
}
