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

package com.android.systemui.statusbar.policy;

import static android.net.ConnectivityManager.TYPE_MOBILE;
import static android.net.NetworkStatsHistory.FIELD_RX_BYTES;
import static android.net.NetworkStatsHistory.FIELD_TX_BYTES;
import static android.telephony.TelephonyManager.SIM_STATE_READY;
import static android.text.format.DateUtils.FORMAT_ABBREV_MONTH;
import static android.text.format.DateUtils.FORMAT_SHOW_DATE;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.INetworkStatsService;
import android.net.INetworkStatsSession;
import android.net.NetworkPolicy;
import android.net.NetworkPolicyManager;
import android.net.NetworkStatsHistory;
import android.net.NetworkTemplate;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.telephony.TelephonyManager;
import android.text.format.DateUtils;
import android.text.format.Time;
import android.util.Log;

import com.android.systemui.statusbar.policy.NetworkController.DataUsageInfo;

import java.util.Date;
import java.util.Locale;
//Add BEGIN by qingyang.yi for PR-919533
//[FEATURE]-Add-BEGIN by TSNJ,yu.dong,01/03/2015,CR-885362
import android.telephony.SubscriptionManager;
import com.android.internal.telephony.PhoneConstants;
import com.android.systemui.qs.tiles.CellularTile;
//[FEATURE]-Add-END by TSNJ,yu.dong,01/03/2015,CR-885362
////Add END by qingyang.yi for PR-919533
public class MobileDataController {
    private static final String TAG = "MobileDataController";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final long DEFAULT_WARNING_LEVEL = 2L * 1024 * 1024 * 1024;
    private static final int FIELDS = FIELD_RX_BYTES | FIELD_TX_BYTES;
    private static final StringBuilder PERIOD_BUILDER = new StringBuilder(50);
    private static final java.util.Formatter PERIOD_FORMATTER = new java.util.Formatter(
            PERIOD_BUILDER, Locale.getDefault());

    private final Context mContext;
    private final TelephonyManager mTelephonyManager;
    private final ConnectivityManager mConnectivityManager;
    private final INetworkStatsService mStatsService;
    private final NetworkPolicyManager mPolicyManager;

    private INetworkStatsSession mSession;
    private Callback mCallback;

    public MobileDataController(Context context) {
        mContext = context;
        mTelephonyManager = TelephonyManager.from(context);
        mConnectivityManager = ConnectivityManager.from(context);
        mStatsService = INetworkStatsService.Stub.asInterface(
                ServiceManager.getService(Context.NETWORK_STATS_SERVICE));
        mPolicyManager = NetworkPolicyManager.from(mContext);
    }

    private INetworkStatsSession getSession() {
        if (mSession == null) {
            try {
                mSession = mStatsService.openSession();
            } catch (RemoteException e) {
                Log.w(TAG, "Failed to open stats session", e);
            } catch (RuntimeException e) {
                Log.w(TAG, "Failed to open stats session", e);
            }
        }
        return mSession;
    }

    public void setCallback(Callback callback) {
        mCallback = callback;
    }

    private DataUsageInfo warn(String msg) {
        Log.w(TAG, "Failed to get data usage, " + msg);
        return null;
    }

    private static Time addMonth(Time t, int months) {
        final Time rt = new Time(t);
        rt.set(t.monthDay, t.month + months, t.year);
        rt.normalize(false);
        return rt;
    }

    public DataUsageInfo getDataUsageInfo() {
        final String subscriberId = getActiveSubscriberId(mContext);
        if (subscriberId == null) {
            return warn("no subscriber id");
        }
        final INetworkStatsSession session = getSession();
        if (session == null) {
            return warn("no stats session");
        }
        final NetworkTemplate template = NetworkTemplate.buildTemplateMobileAll(subscriberId);
        final NetworkPolicy policy = findNetworkPolicy(template);
        try {
            final NetworkStatsHistory history = mSession.getHistoryForNetwork(template, FIELDS);
            final long now = System.currentTimeMillis();
            final long start, end;
            if (policy != null && policy.cycleDay > 0) {
                // period = determined from cycleDay
                if (DEBUG) Log.d(TAG, "Cycle day=" + policy.cycleDay + " tz="
                        + policy.cycleTimezone);
                final Time nowTime = new Time(policy.cycleTimezone);
                nowTime.setToNow();
                final Time policyTime = new Time(nowTime);
                policyTime.set(policy.cycleDay, policyTime.month, policyTime.year);
                policyTime.normalize(false);
                if (nowTime.after(policyTime)) {
                    start = policyTime.toMillis(false);
                    end = addMonth(policyTime, 1).toMillis(false);
                } else {
                    start = addMonth(policyTime, -1).toMillis(false);
                    end = policyTime.toMillis(false);
                }
            } else {
                // period = last 4 wks
                end = now;
                start = now - DateUtils.WEEK_IN_MILLIS * 4;
            }
            final long callStart = System.currentTimeMillis();
            final NetworkStatsHistory.Entry entry = history.getValues(start, end, now, null);
            final long callEnd = System.currentTimeMillis();
            if (DEBUG) Log.d(TAG, String.format("history call from %s to %s now=%s took %sms: %s",
                    new Date(start), new Date(end), new Date(now), callEnd - callStart,
                    historyEntryToString(entry)));
            if (entry == null) {
                return warn("no entry data");
            }
            final long totalBytes = entry.rxBytes + entry.txBytes;
            final DataUsageInfo usage = new DataUsageInfo();
            usage.usageLevel = totalBytes;
            usage.period = formatDateRange(start, end);
            if (policy != null) {
                usage.limitLevel = policy.limitBytes > 0 ? policy.limitBytes : 0;
                usage.warningLevel = policy.warningBytes > 0 ? policy.warningBytes : 0;
            } else {
                usage.warningLevel = DEFAULT_WARNING_LEVEL;
            }
            return usage;
        } catch (RemoteException e) {
            return warn("remote call failed");
        }
    }
    //Add BEGIN by qingyang.yi for PR-919533
    //[FEATURE]-Add-BEGIN by TSNJ,yu.dong,01/03/2015,CR-885362
    public DataUsageInfo getDataUsageInfo(long subId) {
        final String subscriberId = getSubscriberId(mContext,subId);

        if (subscriberId == null) {
            return warn("no subscriber id");
        }
        final INetworkStatsSession session = getSession();
        if (session == null) {
            return warn("no stats session");
        }
        final NetworkTemplate template = NetworkTemplate.buildTemplateMobileAll(subscriberId);
        final NetworkPolicy policy = findNetworkPolicy(template);
        try {
            final NetworkStatsHistory history = mSession.getHistoryForNetwork(template, FIELDS);
            final long now = System.currentTimeMillis();
            final long start, end;
            if (policy != null && policy.cycleDay > 0) {
                // period = determined from cycleDay
                if (DEBUG) Log.d(TAG, "Cycle day=" + policy.cycleDay + " tz="
                        + policy.cycleTimezone);
                final Time nowTime = new Time(policy.cycleTimezone);
                nowTime.setToNow();
                final Time policyTime = new Time(nowTime);
                policyTime.set(policy.cycleDay, policyTime.month, policyTime.year);
                policyTime.normalize(false);
                if (nowTime.after(policyTime)) {
                    start = policyTime.toMillis(false);
                    end = addMonth(policyTime, 1).toMillis(false);
                } else {
                    start = addMonth(policyTime, -1).toMillis(false);
                    end = policyTime.toMillis(false);
                }
            } else {
                // period = last 4 wks
                end = now;
                start = now - DateUtils.WEEK_IN_MILLIS * 4;
            }
            final long callStart = System.currentTimeMillis();
            final NetworkStatsHistory.Entry entry = history.getValues(start, end, now, null);
            final long callEnd = System.currentTimeMillis();
            if (DEBUG) Log.d(TAG, String.format("history call from %s to %s now=%s took %sms: %s",
                    new Date(start), new Date(end), new Date(now), callEnd - callStart,
                    historyEntryToString(entry)));
            if (entry == null) {
                return warn("no entry data");
            }
            final long totalBytes = entry.rxBytes + entry.txBytes;
            final DataUsageInfo usage = new DataUsageInfo();
            usage.usageLevel = totalBytes;
            usage.period = formatDateRange(start, end);
            if (policy != null) {
                usage.limitLevel = policy.limitBytes > 0 ? policy.limitBytes : 0;
                usage.warningLevel = policy.warningBytes > 0 ? policy.warningBytes : 0;
            } else {
                usage.warningLevel = DEFAULT_WARNING_LEVEL;
            }
            return usage;
        } catch (RemoteException e) {
            return warn("remote call failed");
        }
    }
    //[FEATURE]-Add-END by TSNJ,yu.dong,01/03/2015,CR-885362
    //Add END by qingyang.yi for PR-919533
    private NetworkPolicy findNetworkPolicy(NetworkTemplate template) {
        if (mPolicyManager == null || template == null) return null;
        final NetworkPolicy[] policies = mPolicyManager.getNetworkPolicies();
        if (policies == null) return null;
        final int N = policies.length;
        for (int i = 0; i < N; i++) {
            final NetworkPolicy policy = policies[i];
            if (policy != null && template.equals(policy.template)) {
                return policy;
            }
        }
        return null;
    }

    private static String historyEntryToString(NetworkStatsHistory.Entry entry) {
        return entry == null ? null : new StringBuilder("Entry[")
                .append("bucketDuration=").append(entry.bucketDuration)
                .append(",bucketStart=").append(entry.bucketStart)
                .append(",activeTime=").append(entry.activeTime)
                .append(",rxBytes=").append(entry.rxBytes)
                .append(",rxPackets=").append(entry.rxPackets)
                .append(",txBytes=").append(entry.txBytes)
                .append(",txPackets=").append(entry.txPackets)
                .append(",operations=").append(entry.operations)
                .append(']').toString();
    }

    public void setMobileDataEnabled(boolean enabled) {
        mTelephonyManager.setDataEnabled(enabled);
        if (mCallback != null) {
            mCallback.onMobileDataEnabled(enabled);
        }
    }
//ADD BY QINGYANG.YI FOR PR-919533
    //[FEATURE]-Add-BEGIN by TSNJ,yu.dong,01/03/2015,CR-885362
    public void setMobileDataEnabledSubId(long subId,boolean enabled) {
        Log.i(TAG,"mobileDataController setMobileDataEnabledSubId subId: "+subId+"; enabled: "+enabled);
        if (enabled == true) {
            SubscriptionManager.setDefaultDataSubId(subId);
        }

        int phoneId1 = PhoneConstants.SUB1;
        int phoneId2 = PhoneConstants.SUB2;
        long[] subIdSet1 = SubscriptionManager.getSubId(phoneId1);
        if (enabled == true) {
            if (subIdSet1[0] == subId) {
                android.provider.Settings.Global.putInt(mContext.getContentResolver(),
                android.provider.Settings.Global.MOBILE_DATA + phoneId1, 1);

                android.provider.Settings.Global.putInt(mContext.getContentResolver(),
                android.provider.Settings.Global.MOBILE_DATA + phoneId2, 0);
            }else {
                android.provider.Settings.Global.putInt(mContext.getContentResolver(),
                android.provider.Settings.Global.MOBILE_DATA + phoneId1, 0);

                android.provider.Settings.Global.putInt(mContext.getContentResolver(),
                android.provider.Settings.Global.MOBILE_DATA + phoneId2, 1);
            }
        }else {
            if (subIdSet1[0] == subId) {
                android.provider.Settings.Global.putInt(mContext.getContentResolver(),
                            android.provider.Settings.Global.MOBILE_DATA + phoneId1, 0);
            }else {
                android.provider.Settings.Global.putInt(mContext.getContentResolver(),
                            android.provider.Settings.Global.MOBILE_DATA + phoneId2, 0);

            }
        }

        mTelephonyManager.setDataEnabledUsingSubId(subId,enabled);
        if (mCallback != null) {
            mCallback.onMobileDataEnabled(enabled);
        }
    }
    //[FEATURE]-Add-END by TSNJ,yu.dong,01/03/2015,CR-885362

    //[FEATURE]-Add-BEGIN by TSNJ,yu.dong,01/03/2015,CR-885362
    public boolean isMobileDataSupportedMultiCard() {
        boolean res = false;

        if (mConnectivityManager.isNetworkSupported(TYPE_MOBILE) == false) {
            res = false;
        }else {
            int phoneCount = TelephonyManager.getDefault().getPhoneCount();
            if (phoneCount == 1) {
                if (TelephonyManager.getDefault().getSimState() == TelephonyManager.SIM_STATE_READY) {
                    res = true;
                }else {
                    res = false;
                }
            }else if (phoneCount > 1) {
                int readyCount = 0;
                for (int i=0; i<phoneCount; i++) {
                    if (TelephonyManager.getDefault().getSimState(i) == TelephonyManager.SIM_STATE_READY) {
                        readyCount++;
                    }
                }

                if (readyCount == 0) {
                    res = false;
                }else {
                    res = true;
                }
            }
        }
        Log.i(TAG,"isMobileDataSupported res: "+res);
        return res;
    }
    //[FEATURE]-Add-END by TSNJ,yu.dong,01/03/2015,CR-885362
//ADD END BY QINGYANG.YI FOR PR-919533

    public boolean isMobileDataEnabledMultiCard() {
        int s1Enable = android.provider.Settings.Global.getInt(mContext.getContentResolver(),
                            android.provider.Settings.Global.MOBILE_DATA + CellularTile.mPhoneId, 0);
        if (s1Enable == 0) {
            return false;
        }else {
            return true;
        }
    }
    public boolean isMobileDataSupported() {
        // require both supported network and ready SIM
        return mConnectivityManager.isNetworkSupported(TYPE_MOBILE)
                && mTelephonyManager.getSimState() == SIM_STATE_READY;
    }

    public boolean isMobileDataEnabled() {
        return mTelephonyManager.getDataEnabled();
    }

    private static String getActiveSubscriberId(Context context) {
        final TelephonyManager tele = TelephonyManager.from(context);
        final String actualSubscriberId = tele.getSubscriberId();
        return actualSubscriberId;
    }
//ADD BEGIN BY QINGYANG.YI FOR PR-919533
    //[FEATURE]-Add-BEGIN by TSNJ,yu.dong,01/03/2015,CR-885362
    private static String getSubscriberId(Context context,long subId) {
        final TelephonyManager tele = TelephonyManager.from(context);
        final String subscriberId = tele.getSubscriberId(subId);
        return subscriberId;
    }
    //[FEATURE]-Add-END by TSNJ,yu.dong,01/03/2015,CR-885362
////ADD END BY QINGYANG.YI FOR PR-919533
    private String formatDateRange(long start, long end) {
        final int flags = FORMAT_SHOW_DATE | FORMAT_ABBREV_MONTH;
        synchronized (PERIOD_BUILDER) {
            PERIOD_BUILDER.setLength(0);
            return DateUtils.formatDateRange(mContext, PERIOD_FORMATTER, start, end, flags, null)
                    .toString();
        }
    }

    public interface Callback {
        void onMobileDataEnabled(boolean enabled);
    }
}
