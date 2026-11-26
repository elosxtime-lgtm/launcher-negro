/*
 * Copyright (C) 2015 The Android Open Source Project
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

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.content.DialogInterface;
import java.util.List;
import android.provider.Settings;
import android.provider.Settings.System;

/**
 * Settings activity for Launcher. Currently implements the following setting: Allow rotation
 */
public class SettingsActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Display the fragment as the main content.
        getFragmentManager().beginTransaction()
                .replace(android.R.id.content, new LauncherSettingsFragment())
                .commit();
    }

    /**
     * This fragment shows the launcher preferences.
     */
    public static class LauncherSettingsFragment extends PreferenceFragment {

        private SystemDisplayRotationLockObserver mRotationLockObserver;

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            getPreferenceManager().setSharedPreferencesName(LauncherFiles.SHARED_PREFERENCES_KEY);
            addPreferencesFromResource(R.xml.launcher_preferences);

            Preference iconPackPref = findPreference("pref_icon_pack");
            iconPackPref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override public boolean onPreferenceClick(Preference preference) {
                    final IconPackManager manager = new IconPackManager(getActivity());
                    final List<IconPackManager.Pack> packs = manager.getInstalledPacks();
                    final String[] labels = new String[packs.size() + 1];
                    labels[0] = getString(R.string.icon_pack_default);
                    for (int i = 0; i < packs.size(); i++) labels[i + 1] = packs.get(i).label;
                    final android.content.SharedPreferences prefs = getActivity().getSharedPreferences(
                            LauncherFiles.SHARED_PREFERENCES_KEY, android.content.Context.MODE_PRIVATE);
                    final String selected = prefs.getString(IconPackManager.PREF_PACKAGE, "");
                    int checked = 0;
                    for (int i = 0; i < packs.size(); i++) if (packs.get(i).packageName.equals(selected)) checked = i + 1;
                    new AlertDialog.Builder(getActivity()).setTitle(R.string.icon_pack_title)
                            .setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
                                @Override public void onClick(DialogInterface dialog, int which) {
                                    android.content.SharedPreferences.Editor editor = prefs.edit();
                                    if (which == 0) editor.remove(IconPackManager.PREF_PACKAGE)
                                            .remove(IconPackManager.PREF_LABEL);
                                    else editor.putString(IconPackManager.PREF_PACKAGE, packs.get(which - 1).packageName)
                                            .putString(IconPackManager.PREF_LABEL, packs.get(which - 1).label);
                                    editor.apply();
                                    LauncherAppState state = LauncherAppState.getInstance();
                                    state.getIconCache().clearIconPackCache();
                                    state.getModel().forceReload();
                                    dialog.dismiss();
                                }
                            }).setNegativeButton(android.R.string.cancel, null).show();
                    return true;
                }
            });
            String currentPack = getActivity().getSharedPreferences(LauncherFiles.SHARED_PREFERENCES_KEY,
                    android.content.Context.MODE_PRIVATE).getString(IconPackManager.PREF_LABEL, null);
            iconPackPref.setSummary(currentPack == null ? R.string.icon_pack_default : currentPack);

            // Setup allow rotation preference
            Preference rotationPref = findPreference(Utilities.ALLOW_ROTATION_PREFERENCE_KEY);
            if (getResources().getBoolean(R.bool.allow_rotation)) {
                // Launcher supports rotation by default. No need to show this setting.
                getPreferenceScreen().removePreference(rotationPref);
            } else {
                ContentResolver resolver = getActivity().getContentResolver();
                mRotationLockObserver = new SystemDisplayRotationLockObserver(rotationPref, resolver);

                // Register a content observer to listen for system setting changes while
                // this UI is active.
                resolver.registerContentObserver(
                        Settings.System.getUriFor(System.ACCELEROMETER_ROTATION),
                        false, mRotationLockObserver);

                // Initialize the UI once
                mRotationLockObserver.onChange(true);
                rotationPref.setDefaultValue(Utilities.getAllowRotationDefaultValue(getActivity()));
            }
        }

        @Override
        public void onDestroy() {
            if (mRotationLockObserver != null) {
                getActivity().getContentResolver().unregisterContentObserver(mRotationLockObserver);
                mRotationLockObserver = null;
            }
            super.onDestroy();
        }
    }

    /**
     * Content observer which listens for system auto-rotate setting changes, and enables/disables
     * the launcher rotation setting accordingly.
     */
    private static class SystemDisplayRotationLockObserver extends ContentObserver {

        private final Preference mRotationPref;
        private final ContentResolver mResolver;

        public SystemDisplayRotationLockObserver(
                Preference rotationPref, ContentResolver resolver) {
            super(new Handler());
            mRotationPref = rotationPref;
            mResolver = resolver;
        }

        @Override
        public void onChange(boolean selfChange) {
            boolean enabled = Settings.System.getInt(mResolver,
                    Settings.System.ACCELEROMETER_ROTATION, 1) == 1;
            mRotationPref.setEnabled(enabled);
            mRotationPref.setSummary(enabled
                    ? R.string.allow_rotation_desc : R.string.allow_rotation_blocked_desc);
        }
    }
}
