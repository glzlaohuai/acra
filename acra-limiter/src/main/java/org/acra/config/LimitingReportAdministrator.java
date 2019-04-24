/*
 * Copyright (c) 2017
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.acra.config;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.widget.Toast;
import com.google.auto.service.AutoService;
import org.acra.ACRA;
import org.acra.builder.LastActivityManager;
import org.acra.builder.ReportBuilder;
import org.acra.data.CrashReportData;
import org.acra.file.ReportLocator;
import org.acra.plugins.HasConfigPlugin;
import org.acra.util.ToastSender;
import org.json.JSONException;

import java.io.IOException;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.acra.ACRA.LOG_TAG;

/**
 * @author F43nd1r
 * @since 26.10.2017
 */
@AutoService(ReportingAdministrator.class)
public class LimitingReportAdministrator extends HasConfigPlugin implements ReportingAdministrator {

    public LimitingReportAdministrator() {
        super(LimiterConfiguration.class);
    }

    @Override
    public boolean shouldStartCollecting(@NonNull Context context, @NonNull CoreConfiguration config, @NonNull ReportBuilder reportBuilder) {
        try {
            final LimiterConfiguration limiterConfiguration = ConfigUtils.getPluginConfiguration(config, LimiterConfiguration.class);
            final ReportLocator reportLocator = new ReportLocator(context);
            if (reportLocator.getApprovedReports().length + reportLocator.getUnapprovedReports().length >= limiterConfiguration.failedReportLimit()) {
                if (ACRA.DEV_LOGGING) ACRA.log.d(LOG_TAG, "Reached failedReportLimit, not collecting");
                return false;
            }
            final List<LimiterData.ReportMetadata> reportMetadata = loadLimiterData(context, limiterConfiguration).getReportMetadata();
            if (reportMetadata.size() >= limiterConfiguration.overallLimit()) {
                if (ACRA.DEV_LOGGING) ACRA.log.d(LOG_TAG, "Reached overallLimit, not collecting");
                return false;
            }
        } catch (IOException e) {
            ACRA.log.w(LOG_TAG, "Failed to load LimiterData", e);
        }
        return true;
    }

    @Override
    public boolean shouldSendReport(@NonNull Context context, @NonNull CoreConfiguration config, @NonNull CrashReportData crashReportData) {
        try {
            final LimiterConfiguration limiterConfiguration = ConfigUtils.getPluginConfiguration(config, LimiterConfiguration.class);
            final LimiterData limiterData = loadLimiterData(context, limiterConfiguration);
            int sameTrace = 0;
            int sameClass = 0;
            final LimiterData.ReportMetadata m = new LimiterData.ReportMetadata(crashReportData);
            for (LimiterData.ReportMetadata metadata : limiterData.getReportMetadata()) {
                if (m.getStacktrace().equals(metadata.getStacktrace())) {
                    sameTrace++;
                }
                if (m.getExceptionClass().equals(metadata.getExceptionClass())) {
                    sameClass++;
                }
            }
            if (sameTrace >= limiterConfiguration.stacktraceLimit()) {
                if (ACRA.DEV_LOGGING) ACRA.log.d(LOG_TAG, "Reached stacktraceLimit, not sending");
                return false;
            }
            if (sameClass >= limiterConfiguration.exceptionClassLimit()) {
                if (ACRA.DEV_LOGGING) ACRA.log.d(LOG_TAG, "Reached exceptionClassLimit, not sending");
                return false;
            }
            limiterData.getReportMetadata().add(m);
            limiterData.store(context);
        } catch (IOException | JSONException e) {
            ACRA.log.w(LOG_TAG, "Failed to load LimiterData", e);
        }
        return true;
    }

    @Override
    public void notifyReportDropped(@NonNull final Context context, @NonNull final CoreConfiguration config) {
        final LimiterConfiguration limiterConfiguration = ConfigUtils.getPluginConfiguration(config, LimiterConfiguration.class);
        if (limiterConfiguration.ignoredCrashToast() != null) {
            final Future<?> future = Executors.newSingleThreadExecutor().submit(() -> {
                Looper.prepare();
                ToastSender.sendToast(context, limiterConfiguration.ignoredCrashToast(), Toast.LENGTH_LONG);
                final Looper looper = Looper.myLooper();
                if (looper != null) {
                    new Handler(looper).postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                                looper.quitSafely();
                            } else {
                                looper.quit();
                            }
                        }
                    }, 4000);
                    Looper.loop();
                }
            });
            while (!future.isDone()) {
                try {
                    future.get();
                } catch (InterruptedException ignored) {
                } catch (ExecutionException e) {
                    //ReportInteraction crashed, so ignore it
                    break;
                }
            }
        }
    }

    @Override
    public boolean shouldFinishActivity(@NonNull Context context, @NonNull CoreConfiguration config, LastActivityManager lastActivityManager) {
        return true;
    }

    @Override
    public boolean shouldKillApplication(@NonNull Context context, @NonNull CoreConfiguration config, @NonNull ReportBuilder reportBuilder, @Nullable CrashReportData crashReportData) {
        return true;
    }

    @NonNull
    private LimiterData loadLimiterData(@NonNull Context context, @NonNull LimiterConfiguration limiterConfiguration) throws IOException {
        final LimiterData limiterData = LimiterData.load(context);
        final Calendar keepAfter = Calendar.getInstance();
        keepAfter.add(Calendar.MINUTE, (int) -limiterConfiguration.periodUnit().toMinutes(limiterConfiguration.period()));
        if (ACRA.DEV_LOGGING) ACRA.log.d(LOG_TAG, "purging reports older than " + keepAfter.getTime().toString());
        limiterData.purgeOldData(keepAfter);
        limiterData.store(context);
        return limiterData;
    }
}
