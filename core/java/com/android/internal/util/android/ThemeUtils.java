/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.internal.util.android;

import static android.os.UserHandle.USER_SYSTEM;

import android.content.ContentResolver;
import android.content.Context;
import android.content.om.IOverlayManager;
import android.content.om.OverlayInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.PathShape;
import android.net.Uri;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.PathParser;

import org.json.JSONException;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class ThemeUtils {

    public static final String TAG = "ThemeUtils";
    public static final String FONT_KEY = "android.theme.customization.font";
    public static final String ICON_SHAPE_KEY = "android.theme.customization.adaptive_icon_shape";

    private static final Comparator<OverlayInfo> OVERLAY_INFO_COMPARATOR =
            Comparator.comparingInt(a -> a.priority);

    private static ThemeUtils instance;

    private final WeakReference<Context> mContext;
    private final IOverlayManager mOverlayManager;
    private final PackageManager pm;

    private ThemeUtils(Context context) {
        mContext = new WeakReference<>(context);
        mOverlayManager = IOverlayManager.Stub.asInterface(ServiceManager.getService(Context.OVERLAY_SERVICE));
        pm = context.getPackageManager();
    }
    
    public static ThemeUtils getInstance(Context context) {
        if (instance == null) {
            synchronized (ThemeUtils.class) {
                if (instance == null) {
                    instance = new ThemeUtils(context);
                }
            }
        }
        return instance;
    }

    public void setOverlayEnabled(String category, String packageName, String target) {
        final String currentPackageName = getOverlayInfos(category, target).stream()
                .filter(OverlayInfo::isEnabled)
                .map(info -> info.packageName)
                .findFirst()
                .orElse(null);
        try {
            if (target.equals(packageName)) {
                mOverlayManager.setEnabled(currentPackageName, false, USER_SYSTEM);
            } else {
                mOverlayManager.setEnabledExclusiveInCategory(packageName, USER_SYSTEM);
            }
            writeSettings(category, packageName, target.equals(packageName));
        } catch (RemoteException e) {
            Log.e(TAG, "Error enabling overlay", e);
        }
    }

    public void writeSettings(String category, String packageName, boolean disable) {
        Context context = mContext.get();
        if (context == null) return;
        final String overlayPackageJson = Settings.Secure.getStringForUser(
                context.getContentResolver(),
                Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES,
                UserHandle.USER_CURRENT);
        JSONObject object;
        try {
            object = overlayPackageJson == null ? new JSONObject() : new JSONObject(overlayPackageJson);
            if (disable) {
                object.remove(category);
            } else {
                object.put(category, packageName);
            }
            Settings.Secure.putStringForUser(context.getContentResolver(),
                    Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES,
                    object.toString(), UserHandle.USER_CURRENT);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse THEME_CUSTOMIZATION_OVERLAY_PACKAGES.", e);
        }
    }

    public List<String> getOverlayPackagesForCategory(String category) {
        return getOverlayPackagesForCategory(category, "android");
    }

    public List<String> getOverlayPackagesForCategory(String category, String target) {
        List<String> overlays = new ArrayList<>();
        List<String> packages = new ArrayList<>();
        overlays.add(target);
        for (OverlayInfo info : getOverlayInfos(category, target)) {
            if (category.equals(info.getCategory())) {
                packages.add(info.getPackageName());
            }
        }
        Collections.sort(packages);
        overlays.addAll(packages);
        return overlays;
    }

    public List<OverlayInfo> getOverlayInfos(String category) {
        return getOverlayInfos(category, "android");
    }

    public List<OverlayInfo> getOverlayInfos(String category, String target) {
        List<OverlayInfo> filteredInfos = new ArrayList<>();
        try {
            List<OverlayInfo> overlayInfos = mOverlayManager.getOverlayInfosForTarget(target, USER_SYSTEM);
            for (OverlayInfo overlayInfo : overlayInfos) {
                if (category.equals(overlayInfo.category)) {
                    filteredInfos.add(overlayInfo);
                }
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error retrieving overlay info", e);
        }
        filteredInfos.sort(OVERLAY_INFO_COMPARATOR);
        return filteredInfos;
    }

    public List<Typeface> getFonts() {
        List<Typeface> fontList = new ArrayList<>();
        for (String overlayPackage : getOverlayPackagesForCategory(FONT_KEY)) {
            try {
                Resources overlayRes = getResourcesForPackage(overlayPackage);
                if (overlayRes != null) {
                    String font = overlayRes.getString(overlayRes.getIdentifier(
                            "config_bodyFontFamily", "string", overlayPackage));
                    fontList.add(Typeface.create(font, Typeface.NORMAL));
                }
            } catch (Exception e) {
                Log.e(TAG, "Error loading fonts", e);
            }
        }
        return fontList;
    }

    public List<ShapeDrawable> getShapeDrawables() {
        List<ShapeDrawable> shapeList = new ArrayList<>();
        for (String overlayPackage : getOverlayPackagesForCategory(ICON_SHAPE_KEY)) {
            ShapeDrawable drawable = createShapeDrawable(overlayPackage);
            if (drawable != null) {
                shapeList.add(drawable);
            }
        }
        return shapeList;
    }

    public ShapeDrawable createShapeDrawable(String overlayPackage) {
        try {
            Resources overlayRes = getResourcesForPackage(overlayPackage);
            if (overlayRes == null) return null;
            String shape = overlayRes.getString(overlayRes.getIdentifier(
                    "config_icon_mask", "string", overlayPackage));
            if (!TextUtils.isEmpty(shape)) {
                Path path = PathParser.createPathFromPathData(shape);
                PathShape pathShape = new PathShape(path, 100f, 100f);
                ShapeDrawable shapeDrawable = new ShapeDrawable(pathShape);
                int thumbSize = (int) (mContext.get().getResources().getDisplayMetrics().density * 72);
                shapeDrawable.setIntrinsicHeight(thumbSize);
                shapeDrawable.setIntrinsicWidth(thumbSize);
                return shapeDrawable;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error creating shape drawable", e);
        }
        return null;
    }

    public boolean isOverlayEnabled(String overlayPackage) {
        try {
            OverlayInfo info = mOverlayManager.getOverlayInfo(overlayPackage, USER_SYSTEM);
            return info != null && info.isEnabled();
        } catch (RemoteException e) {
            Log.e(TAG, "Error checking overlay status", e);
        }
        return false;
    }

    public boolean isDefaultOverlay(String category) {
        for (String overlayPackage : getOverlayPackagesForCategory(category)) {
            try {
                OverlayInfo info = mOverlayManager.getOverlayInfo(overlayPackage, USER_SYSTEM);
                if (info != null && info.isEnabled()) {
                    return false;
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Error checking default overlay", e);
            }
        }
        return true;
    }

    private Resources getResourcesForPackage(String overlayPackage) throws NameNotFoundException {
        return overlayPackage.equals("android") ? Resources.getSystem() : pm.getResourcesForApplication(overlayPackage);
    }
}
