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
import android.content.DialogInterface;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.provider.Settings;
import android.provider.Settings.System;
import android.text.TextUtils;

import java.util.List;

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

            setupIconPackPreference();
        }

        /**
         * Shows the selected icon pack and lets the user pick another one.
         */
        private void setupIconPackPreference() {
            final Preference iconPackPref = findPreference(IconPackManager.PREF_ICON_PACK);
            if (iconPackPref == null) {
                return;
            }
            updateIconPackSummary(iconPackPref);
            iconPackPref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    showIconPackDialog(iconPackPref);
                    return true;
                }
            });
        }

        private void updateIconPackSummary(Preference iconPackPref) {
            IconPackManager manager = IconPackManager.getInstance(getActivity());
            CharSequence label = manager.getPackLabel(manager.getSelectedPack());
            iconPackPref.setSummary(label != null
                    ? label : getString(R.string.icon_pack_summary_default));
        }

        private void showIconPackDialog(final Preference iconPackPref) {
            final Activity activity = getActivity();
            final IconPackManager manager = IconPackManager.getInstance(activity);
            final List<IconPackManager.IconPackInfo> packs = manager.getInstalledPacks();

            if (packs.isEmpty()) {
                new AlertDialog.Builder(activity)
                        .setTitle(R.string.icon_pack_title)
                        .setMessage(R.string.icon_pack_none_found)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                return;
            }

            // The first option restores the original icons of the launcher.
            CharSequence[] names = new CharSequence[packs.size() + 1];
            names[0] = getString(R.string.icon_pack_default_option);
            String selected = manager.getSelectedPack();
            int checkedItem = 0;
            for (int i = 0; i < packs.size(); i++) {
                names[i + 1] = packs.get(i).label;
                if (TextUtils.equals(packs.get(i).packageName, selected)) {
                    checkedItem = i + 1;
                }
            }

            new AlertDialog.Builder(activity)
                    .setTitle(R.string.icon_pack_dialog_title)
                    .setSingleChoiceItems(names, checkedItem,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    applyIconPack(iconPackPref,
                                            which == 0 ? "" : packs.get(which - 1).packageName);
                                    dialog.dismiss();
                                }
                            })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }

        /**
         * Stores the new icon pack and rebuilds the icons of the home screen and all apps.
         * The launcher picks the new icons up when the user goes back to it.
         */
        private void applyIconPack(Preference iconPackPref, String packageName) {
            IconPackManager manager = IconPackManager.getInstance(getActivity());
            if (TextUtils.equals(manager.getSelectedPack(), packageName)) {
                return;
            }
            manager.setSelectedPack(packageName);

            LauncherAppState app = LauncherAppState.getInstanceNoCreate();
            if (app != null) {
                app.getIconCache().clearAllIcons();
                app.getModel().forceReload();
            }
            updateIconPackSummary(iconPackPref);
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
