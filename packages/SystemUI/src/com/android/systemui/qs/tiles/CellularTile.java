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

package com.android.systemui.qs.tiles;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.android.systemui.R;
import com.android.systemui.qs.QSTile;
import com.android.systemui.qs.QSTileView;
import com.android.systemui.qs.SignalTileView;
import com.android.systemui.statusbar.policy.NetworkController;
import com.android.systemui.statusbar.policy.NetworkController.DataUsageInfo;
import com.android.systemui.statusbar.policy.NetworkController.NetworkSignalChangedCallback;

//[FEATURE]-Add-BEGIN by TSNJ,yu.dong,01/03/2015,CR-885362
import com.android.internal.telephony.PhoneConstants;
import android.telephony.SubscriptionManager;
import android.util.Log;
import com.android.systemui.qs.QSPanel;
import android.telephony.TelephonyManager;
import com.android.systemui.statusbar.policy.MSimNetworkControllerImpl;

//[FEATURE]-Add-END by TSNJ,yu.dong,01/03/2015,CR-885362
/** Quick settings tile: Cellular **/
public class CellularTile extends QSTile<QSTile.SignalState> {
    private static final Intent CELLULAR_SETTINGS = new Intent().setComponent(new ComponentName(
            "com.android.settings", "com.android.settings.Settings$DataUsageSummaryActivity"));

    private final NetworkController mController;
    private final CellularDetailAdapter mDetailAdapter;

    public CellularTile(Host host) {
        super(host);
        mController = host.getNetworkController();
        mDetailAdapter = new CellularDetailAdapter();
    }
	
    //[FEATURE]-Add-BEGIN by TSNJ,yu.dong,01/03/2015,CR-885362
    private static long mSubId;
    public static long mPhoneId;
    private static String mCarrier;
    public static void setSubId(long subId) {
        mSubId = subId;
    }

    public static void setPhoneId(long phoneId) {
        mPhoneId = phoneId;
    }

    public static void setCarrier(String carrier) {
        mCarrier = carrier;
    }
    //[FEATURE]-Add-END by TSNJ,yu.dong,01/03/2015,CR-885362
    
    @Override
    protected SignalState newTileState() {
        return new SignalState();
    }

    @Override
    public DetailAdapter getDetailAdapter() {
        return mDetailAdapter;
    }

    @Override
    public void setListening(boolean listening) {
        if (listening) {
            mController.addNetworkSignalChangedCallback(mCallback);
        } else {
            mController.removeNetworkSignalChangedCallback(mCallback);
        }
    }

    @Override
    public QSTileView createTileView(Context context) {
        return new SignalTileView(context);
    }

    @Override
    protected void handleClick() {
        //[FEATURE]-MOD-BEGIN by TSNJ,yu.dong,01/03/2015,CR-885362
        int phoneCount = TelephonyManager.getDefault().getPhoneCount();
        if (phoneCount == 1) {
            if (mController.isMobileDataSupported()) {
                showDetail(true);
            }else {
                mHost.startSettingsActivity(CELLULAR_SETTINGS);
            }
        }else if (phoneCount > 1) {
            if (mController.isMobileDataSupportedMultiCard()) {
                int readyCount = 0;
                int phoneId = -1;
                for (int i=0; i<phoneCount; i++) {
                    if (TelephonyManager.getDefault().getSimState(i) == TelephonyManager.SIM_STATE_READY) {
                        phoneId = i;
                        readyCount++;
                    }
                }
                if (readyCount == 2) {
                    phoneId = 0;
                }
                if (phoneId != -1) {
                    mPhoneId = phoneId;
                    mCarrier = MSimNetworkControllerImpl.mMSimNetworkName[phoneId];
                    long[] subIdSet1 = SubscriptionManager.getSubId(phoneId);
                    mSubId = subIdSet1[0];
                }
                Log.d(TAG,"handleClick phoneId: "+phoneId+"; subId: "+mSubId);
                showDetail(true);
            }else {
                mHost.startSettingsActivity(CELLULAR_SETTINGS);
            }
        }
        //[FEATURE]-MOD-END by TSNJ,yu.dong,01/03/2015,CR-885362
    }

    @Override
    protected void handleUpdateState(SignalState state, Object arg) {
        state.visible = mController.hasMobileDataFeature();
        if (!state.visible) return;
        final CallbackInfo cb = (CallbackInfo) arg;
        if (cb == null) return;

        final Resources r = mContext.getResources();
        state.iconId = cb.noSim ? R.drawable.ic_qs_no_sim
                : !cb.enabled || cb.airplaneModeEnabled ? R.drawable.ic_qs_signal_disabled
                : cb.mobileSignalIconId > 0 ? cb.mobileSignalIconId
                : R.drawable.ic_qs_signal_no_signal;
        state.isOverlayIconWide = cb.isDataTypeIconWide;
        state.autoMirrorDrawable = !cb.noSim;
        state.overlayIconId = cb.enabled && (cb.dataTypeIconId > 0) && !cb.wifiConnected
                ? cb.dataTypeIconId
                : 0;
        state.filter = state.iconId != R.drawable.ic_qs_no_sim;
        state.activityIn = cb.enabled && cb.activityIn;
        state.activityOut = cb.enabled && cb.activityOut;

        state.label = cb.enabled
                ? removeTrailingPeriod(cb.enabledDesc)
                : r.getString(R.string.quick_settings_rssi_emergency_only);

        final String signalContentDesc = cb.enabled && (cb.mobileSignalIconId > 0)
                ? cb.signalContentDescription
                : r.getString(R.string.accessibility_no_signal);
        final String dataContentDesc = cb.enabled && (cb.dataTypeIconId > 0) && !cb.wifiEnabled
                ? cb.dataContentDescription
                : r.getString(R.string.accessibility_no_data);
        state.contentDescription = r.getString(
                R.string.accessibility_quick_settings_mobile,
                signalContentDesc, dataContentDesc,
                state.label);
    }

    // Remove the period from the network name
    public static String removeTrailingPeriod(String string) {
        if (string == null) return null;
        final int length = string.length();
        if (string.endsWith(".")) {
            return string.substring(0, length - 1);
        }
        return string;
    }

    private static final class CallbackInfo {
        boolean enabled;
        boolean wifiEnabled;
        boolean wifiConnected;
        boolean airplaneModeEnabled;
        int mobileSignalIconId;
        String signalContentDescription;
        int dataTypeIconId;
        String dataContentDescription;
        boolean activityIn;
        boolean activityOut;
        String enabledDesc;
        boolean noSim;
        boolean isDataTypeIconWide;
    }

    private final NetworkSignalChangedCallback mCallback = new NetworkSignalChangedCallback() {
        private boolean mWifiEnabled;
        private boolean mWifiConnected;
        private boolean mAirplaneModeEnabled;

        @Override
        public void onWifiSignalChanged(boolean enabled, boolean connected, int wifiSignalIconId,
                boolean activityIn, boolean activityOut,
                String wifiSignalContentDescriptionId, String description) {
            mWifiEnabled = enabled;
            mWifiConnected = connected;
        }

        @Override
        public void onMobileDataSignalChanged(boolean enabled,
                int mobileSignalIconId,
                String mobileSignalContentDescriptionId, int dataTypeIconId,
                boolean activityIn, boolean activityOut,
                String dataTypeContentDescriptionId, String description, boolean noSim,
                boolean isDataTypeIconWide) {
            final CallbackInfo info = new CallbackInfo();  // TODO pool?
            
            //[FEATURE]-ADD-BEGIN by TSNJ,yu.dong,01/03/2015,CR-885362
            int phoneId = -1;
            int phoneCount = TelephonyManager.getDefault().getPhoneCount();
            if (phoneCount > 1) {
                int readyCount = 0;
                for (int i=0; i<phoneCount; i++) {
                    if (TelephonyManager.getDefault().getSimState(i) == TelephonyManager.SIM_STATE_READY) {
                        phoneId = i;
                        readyCount++;
                    }
                }
                if (readyCount == 2) {
                    phoneId = 0;
                }
                if (readyCount >= 1) {
                    info.mobileSignalIconId = MSimNetworkControllerImpl.mMSimQSPhoneSignalIconId[phoneId];
                    info.enabledDesc = MSimNetworkControllerImpl.mMSimNetworkName[phoneId];
                }else {
                    info.mobileSignalIconId = mobileSignalIconId;
                    info.enabledDesc = description;
                }
            }else if (phoneCount == 1){
                info.mobileSignalIconId = mobileSignalIconId;
                info.enabledDesc = description;
            }
            //[FEATURE]-ADD-END by TSNJ,yu.dong,01/03/2015,CR-885362

            info.enabled = enabled;
            info.wifiEnabled = mWifiEnabled;
            info.wifiConnected = mWifiConnected;
            info.airplaneModeEnabled = mAirplaneModeEnabled;
            info.mobileSignalIconId = mobileSignalIconId;
            info.signalContentDescription = mobileSignalContentDescriptionId;
            info.dataTypeIconId = dataTypeIconId;
            info.dataContentDescription = dataTypeContentDescriptionId;
            info.activityIn = activityIn;
            info.activityOut = activityOut;
            info.enabledDesc = description;
            info.noSim = noSim;
            info.isDataTypeIconWide = isDataTypeIconWide;
            refreshState(info);
        }

        @Override
        public void onAirplaneModeChanged(boolean enabled) {
            mAirplaneModeEnabled = enabled;
        }

        public void onMobileDataEnabled(boolean enabled) {
            mDetailAdapter.setMobileDataEnabled(enabled);
        }
    };

//add BEGIN by qingyang.yi for PR-919533
//[FEATURE]-MOD by TSNJ,yu.dong,01/03/2015,CR-885362  mod private to public
    public final class CellularDetailAdapter implements DetailAdapter {

        @Override
        public int getTitle() {
            return R.string.quick_settings_cellular_detail_title;
        }

        @Override
        public Boolean getToggleState() {
            int phoneCount = TelephonyManager.getDefault().getPhoneCount();
            if (phoneCount == 1) {
                return mController.isMobileDataSupported() ? mController.isMobileDataEnabled() : null;
            }else if (phoneCount > 1) {
                return mController.isMobileDataSupportedMultiCard() ? mController.isMobileDataEnabledMultiCard() : null;
            }
            return null;
        }

        @Override
        public Intent getSettingsIntent() {
            return CELLULAR_SETTINGS;
        }

        @Override
        public void setToggleState(boolean state) {
            //[FEATURE]-ADD-BEGIN by TSNJ,yu.dong,01/03/2015,CR-885362
            int phoneCount = TelephonyManager.getDefault().getPhoneCount();
            if (phoneCount == 1) {
                mController.setMobileDataEnabled(state);
            }else if (phoneCount > 1) {
                mController.setMobileDataEnabledSubId(mSubId,state);
            }
            //[FEATURE]-ADD-END by TSNJ,yu.dong,01/03/2015,CR-885362
        }

        @Override
        public View createDetailView(Context context, View convertView, ViewGroup parent) {
            final DataUsageDetailView v = (DataUsageDetailView) (convertView != null
                    ? convertView
                    : LayoutInflater.from(mContext).inflate(R.layout.data_usage, parent, false));
            int phoneCount = TelephonyManager.getDefault().getPhoneCount();
            //[FEATURE]-MOD-BEGIN by TSNJ,yu.dong,01/03/2015,CR-885362
            DataUsageInfo info = null;
            if (phoneCount == 1) {
                info = mController.getDataUsageInfo();
            }else if (phoneCount > 1) {
                info = mController.getDataUsageInfo(mSubId);
            }
            //[FEATURE]-MOD-END by TSNJ,yu.dong,01/03/2015,CR-885362

            if (info == null) return v;
            //[FEATURE]-ADD-BEGIN by TSNJ,yu.dong,01/03/2015,CR-885362
            if (phoneCount > 1) {
                info.carrier = mCarrier;
            }
            //[FEATURE]-ADD-END by TSNJ,yu.dong,01/03/2015,CR-885362
            v.bind(info);
            return v;
        }
//add END by qingyang.yi for PR-919533
        public void setMobileDataEnabled(boolean enabled) {
            fireToggleStateChanged(enabled);
        }
    }
}
