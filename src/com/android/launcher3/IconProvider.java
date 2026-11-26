package com.android.launcher3;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.Log;

import com.android.launcher3.compat.LauncherActivityInfoCompat;

import java.lang.reflect.InvocationTargetException;
import java.util.Locale;

public class IconProvider {

    private static final boolean DBG = false;
    private static final String TAG = "IconProvider";

    protected String mSystemState;

    // Used to look up the icons of the selected icon pack. Null if not available.
    private Context mContext;

    public IconProvider() {
        updateSystemStateString();
    }

    public IconProvider(Context context) {
        mContext = context.getApplicationContext();
        updateSystemStateString();
    }

    public static IconProvider loadByName(String className, Context context) {
        if (TextUtils.isEmpty(className)) return new IconProvider(context);
        if (DBG) Log.d(TAG, "Loading IconProvider: " + className);
        try {
            Class<?> cls = Class.forName(className);
            return (IconProvider) cls.getDeclaredConstructor(Context.class).newInstance(context);
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException
                | ClassCastException | NoSuchMethodException | InvocationTargetException e) {
            Log.e(TAG, "Bad IconProvider class", e);
            return new IconProvider(context);
        }
    }

    public void updateSystemStateString() {
        mSystemState = Locale.getDefault().toString();
        if (mContext != null) {
            // The selected icon pack is part of the state, so the icons stored in the icon
            // cache are rebuilt whenever the icon pack changes.
            IconPackManager iconPacks = IconPackManager.getInstance(mContext);
            iconPacks.invalidate();
            mSystemState += iconPacks.getStateString();
        }
    }

    public String getIconSystemState(String packageName) {
        return mSystemState;
    }


    public Drawable getIcon(LauncherActivityInfoCompat info, int iconDpi) {
        if (mContext != null) {
            Drawable packIcon = IconPackManager.getInstance(mContext)
                    .getIcon(info.getComponentName(), iconDpi);
            if (packIcon != null) {
                return packIcon;
            }
        }
        return info.getIcon(iconDpi);
    }
}
