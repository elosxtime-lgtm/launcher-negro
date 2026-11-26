/*
 * Copyright (C) 2017 The Android Open Source Project
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
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.Log;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/**
 * Detects the installed third party icon packs (ADW / Nova / Apex / GO compatible), remembers
 * which one the user selected and resolves the icon an icon pack provides for a component.
 *
 * Icon packs describe their icons in an "appfilter.xml" file, which maps a component to a
 * drawable of the icon pack:
 *   <item component="ComponentInfo{com.example/com.example.Main}" drawable="example" />
 *
 * Components that the selected icon pack does not theme keep their original launcher icon.
 */
public class IconPackManager {

    private static final String TAG = "IconPackManager";

    /** Key, in the launcher preferences, of the selected icon pack ("" = original icons). */
    public static final String PREF_ICON_PACK = "pref_iconPack";

    /** Actions an app declares on an activity to announce itself as an icon pack. */
    private static final String[] ICON_PACK_ACTIONS = {
            "org.adw.launcher.THEMES",
            "com.gau.go.launcherex.theme",
            "com.novalauncher.THEME",
            "com.anddoes.launcher.THEME",
            "com.fede.launcher.THEME_ICONPACK",
            "com.dlto.atom.launcher.THEME",
            "ginlemon.smartlauncher.THEMES"
    };

    /** Basic description of an installed icon pack. */
    public static class IconPackInfo {
        public final String packageName;
        public final CharSequence label;

        IconPackInfo(String packageName, CharSequence label) {
            this.packageName = packageName;
            this.label = label;
        }
    }

    private static IconPackManager sInstance;

    public static synchronized IconPackManager getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new IconPackManager(context.getApplicationContext());
        }
        return sInstance;
    }

    private final Context mContext;
    private final PackageManager mPackageManager;

    // State of the loaded icon pack. All guarded by "this".
    private boolean mLoaded = false;
    private String mLoadedPack = "";
    private Resources mPackResources;
    private final HashMap<String, String> mComponentToDrawable = new HashMap<String, String>();

    private IconPackManager(Context context) {
        mContext = context;
        mPackageManager = context.getPackageManager();
    }

    /**
     * Returns the icon packs currently installed on the device, sorted by name.
     */
    public List<IconPackInfo> getInstalledPacks() {
        HashSet<String> seen = new HashSet<String>();
        ArrayList<IconPackInfo> result = new ArrayList<IconPackInfo>();
        for (String action : ICON_PACK_ACTIONS) {
            List<ResolveInfo> matches =
                    mPackageManager.queryIntentActivities(new Intent(action), 0);
            if (matches == null) {
                continue;
            }
            for (ResolveInfo match : matches) {
                if (match.activityInfo == null) {
                    continue;
                }
                String packageName = match.activityInfo.packageName;
                if (packageName.equals(mContext.getPackageName()) || !seen.add(packageName)) {
                    continue;
                }
                result.add(new IconPackInfo(packageName,
                        match.activityInfo.applicationInfo.loadLabel(mPackageManager)));
            }
        }
        Collections.sort(result, new Comparator<IconPackInfo>() {
            @Override
            public int compare(IconPackInfo a, IconPackInfo b) {
                return a.label.toString().compareToIgnoreCase(b.label.toString());
            }
        });
        return result;
    }

    /**
     * @return the package name of the selected icon pack, or "" if the original icons are used.
     */
    public String getSelectedPack() {
        return Utilities.getPrefs(mContext).getString(PREF_ICON_PACK, "");
    }

    /**
     * Selects an icon pack. Pass "" to go back to the original launcher icons.
     * This only stores the choice; the caller has to rebuild the icons afterwards.
     */
    public void setSelectedPack(String packageName) {
        Utilities.getPrefs(mContext).edit()
                .putString(PREF_ICON_PACK, packageName == null ? "" : packageName)
                .commit();
        invalidate();
    }

    /**
     * @return the label of an installed icon pack, or null if it is not installed.
     */
    public CharSequence getPackLabel(String packageName) {
        if (TextUtils.isEmpty(packageName)) {
            return null;
        }
        try {
            return mPackageManager.getApplicationInfo(packageName, 0).loadLabel(mPackageManager);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /**
     * Forgets the loaded icon pack, so it is read again the next time an icon is requested.
     */
    public synchronized void invalidate() {
        mLoaded = false;
    }

    /**
     * A string describing the icon pack in use, it is part of the state used to decide when the
     * icons stored in the icon cache are outdated. It is empty when the original icons are used
     * (or when the selected icon pack is no longer installed), and it changes when the icon pack
     * is replaced or updated.
     */
    public String getStateString() {
        String pack = getSelectedPack();
        if (TextUtils.isEmpty(pack)) {
            return "";
        }
        try {
            PackageInfo info = mPackageManager.getPackageInfo(pack, 0);
            return "," + pack + ":" + info.lastUpdateTime;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    /**
     * Returns the icon the selected icon pack provides for the component, or null if no icon
     * pack is selected or it does not theme this component.
     */
    public synchronized Drawable getIcon(ComponentName component, int density) {
        if (component == null) {
            return null;
        }
        ensureLoaded();
        if (mPackResources == null) {
            return null;
        }
        String drawableName = mComponentToDrawable.get(component.flattenToString());
        if (drawableName == null) {
            return null;
        }
        return loadDrawable(drawableName, density);
    }

    private void ensureLoaded() {
        String pack = getSelectedPack();
        if (mLoaded && TextUtils.equals(pack, mLoadedPack)) {
            return;
        }
        mLoaded = true;
        mLoadedPack = pack;
        mPackResources = null;
        mComponentToDrawable.clear();
        if (TextUtils.isEmpty(pack)) {
            return;
        }
        try {
            mPackResources = mPackageManager.getResourcesForApplication(pack);
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Icon pack not installed: " + pack);
            return;
        }
        parseAppFilter(pack, mPackResources);
    }

    private void parseAppFilter(String pack, Resources resources) {
        InputStream in = null;
        try {
            XmlPullParser parser;
            int xmlId = resources.getIdentifier("appfilter", "xml", pack);
            if (xmlId != 0) {
                parser = resources.getXml(xmlId);
            } else {
                // Some icon packs ship the file as an asset instead of a compiled xml resource.
                in = resources.getAssets().open("appfilter.xml");
                parser = XmlPullParserFactory.newInstance().newPullParser();
                parser.setInput(in, "UTF-8");
            }
            int event = parser.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && "item".equals(parser.getName())) {
                    addMapping(parser.getAttributeValue(null, "component"),
                            parser.getAttributeValue(null, "drawable"));
                }
                event = parser.next();
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not read the appfilter of " + pack, e);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
        }
    }

    private void addMapping(String component, String drawableName) {
        if (component == null || drawableName == null
                || !component.startsWith("ComponentInfo{")) {
            return;
        }
        int start = component.indexOf('{');
        int end = component.lastIndexOf('}');
        if (end <= start) {
            return;
        }
        ComponentName name = ComponentName.unflattenFromString(component.substring(start + 1, end));
        if (name == null) {
            return;
        }
        String key = name.flattenToString();
        // If several entries target the same component, the first one wins.
        if (!mComponentToDrawable.containsKey(key)) {
            mComponentToDrawable.put(key, drawableName);
        }
    }

    private Drawable loadDrawable(String drawableName, int density) {
        int id = mPackResources.getIdentifier(drawableName, "drawable", mLoadedPack);
        if (id == 0) {
            id = mPackResources.getIdentifier(drawableName, "mipmap", mLoadedPack);
        }
        if (id == 0) {
            return null;
        }
        try {
            return mPackResources.getDrawableForDensity(id, density);
        } catch (Resources.NotFoundException e) {
            return null;
        }
    }
}
